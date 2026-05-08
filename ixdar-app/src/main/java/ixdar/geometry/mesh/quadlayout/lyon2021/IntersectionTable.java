package ixdar.geometry.mesh.quadlayout.lyon2021;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;

/**
 * PATCH-66 Stage D: build the intersection table for a Tpatch from its
 * arcs1 (side-0 → side-2 traces) and arcs2 (side-1 → side-3 traces).
 * Mirrors metriko's {@code visualizer::gen_intersection_table}
 * (gen_qgp_mesh.h ~80 LOC).
 *
 * <p>Output: an {@code (arcs1.size() + 2) × (arcs2.size() + 2)} table of
 * 3D positions. Cell {@code (i, j)} is:
 * <ul>
 *   <li>The 4 patch corners at table corners.</li>
 *   <li>Arc endpoints on the patch boundary along the first / last row + col.</li>
 *   <li>Interior cells: the 3D intersection of {@code arcs1[i-1]} with
 *       {@code arcs2[j-1]}.</li>
 * </ul>
 *
 * <p>Stage E then walks the table emitting {@code (rows-1) × (cols-1)}
 * quad faces, one per cell.
 */
public final class IntersectionTable {
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;
    public static final float NUM_1 = 1f;
    public static final float NUM_3_2 = 3f;

    private static final double EPS = 1e-9;

    private IntersectionTable() {}

    /**
     * Build the intersection table for one Tpatch.
     *
     * @param tmesh    T-mesh
     * @param patch    Tpatch
     * @param arcs1    side 0 → side 2 split arcs (rows of the interior grid)
     * @param arcs2    side 1 → side 3 split arcs (cols of the interior grid)
     * @param mesh     underlying triangle mesh
     * @param uCorner  per-corner u
     * @param vCorner  per-corner v
     * @return populated table of {@code rows x cols} 3D positions
     */
    public static Result build(TMesh tmesh, TPatch patch,
                                List<SplitArcTracer.SplitArc> arcs1,
                                List<SplitArcTracer.SplitArc> arcs2,
                                ArrayMesh mesh,
                                float[] uCorner, float[] vCorner) {
        int rows = arcs1.size() + 2;
        int cols = arcs2.size() + 2;
        Vector3f[] positions = new Vector3f[rows * cols];

        // 4 corners — at the start of each Tpatch side.
        for (int side = 0; side < NUM_4; side++) {
            int arcId = patch.arcIds()[side];
            var arc = tmesh.arcs().get(arcId);
            int cornerNodeId = patch.cornerNodeIds()[side];
            boolean forward = arc.startNode() == cornerNodeId;
            float u, v;
            int faceId;
            if (forward && !arc.stepUvs().isEmpty()) {
                float[] s = arc.stepUvs().get(0);
                u = s[0]; v = s[1];
                faceId = arc.meshFaceCrossings().get(0)[0];
            } else if (!arc.stepUvs().isEmpty()) {
                int last = arc.stepUvs().size() - 1;
                float[] s = arc.stepUvs().get(last);
                u = s[2]; v = s[NUM_3];
                faceId = arc.meshFaceCrossings().get(last)[0];
            } else {
                continue;
            }
            int x = 0, y = 0;
            switch (side) {
                case 0 -> { x = 0;        y = 0; }
                case 1 -> { x = rows - 1; y = 0; }
                case 2 -> { x = rows - 1; y = cols - 1; }
                case NUM_3 -> { x = 0;        y = cols - 1; }
            }
            positions[x * cols + y] = baryToWorld(mesh, faceId, uCorner, vCorner, u, v);
        }

        // arcs1 endpoints on side-0 (y=0) and side-2 (y=cols-1).
        for (int i = 0; i < arcs1.size(); i++) {
            SplitArcTracer.SplitArc arc = arcs1.get(i);
            if (arc.edges().isEmpty()) continue;
            SplitEdge first = arc.edges().get(0);
            SplitEdge last = arc.edges().get(arc.edges().size() - 1);
            positions[(i + 1) * cols + 0] = baryToWorld(mesh, first.faceId(),
                    uCorner, vCorner, first.u1(), first.v1());
            positions[(i + 1) * cols + (cols - 1)] = baryToWorld(mesh, last.faceId(),
                    uCorner, vCorner, last.u2(), last.v2());
        }

        // arcs2 endpoints on side-1 (x=rows-1) and side-3 (x=0).
        for (int j = 0; j < arcs2.size(); j++) {
            SplitArcTracer.SplitArc arc = arcs2.get(j);
            if (arc.edges().isEmpty()) continue;
            SplitEdge first = arc.edges().get(0);
            SplitEdge last = arc.edges().get(arc.edges().size() - 1);
            positions[(rows - 1) * cols + (j + 1)] = baryToWorld(mesh, first.faceId(),
                    uCorner, vCorner, first.u1(), first.v1());
            positions[0 * cols + (j + 1)] = baryToWorld(mesh, last.faceId(),
                    uCorner, vCorner, last.u2(), last.v2());
        }

        // Interior intersections.
        for (int i = 0; i < arcs1.size(); i++) {
            for (int j = 0; j < arcs2.size(); j++) {
                Vector3f p = intersection3D(arcs1.get(i), arcs2.get(j),
                        mesh, uCorner, vCorner);
                positions[(i + 1) * cols + (j + 1)] = p;
            }
        }

        return new Result(rows, cols, positions);
    }

    /**
     * 3D point at the UV-space intersection of two SplitArcs (one of arc1's
     *  edges and one of arc2's edges share a face and the segments cross).
     *
     * @param arc1    side-0-to-side-2 split arc
     * @param arc2    side-1-to-side-3 split arc
     * @param mesh    underlying triangle mesh
     * @param uCorner per-corner u
     * @param vCorner per-corner v
     * @return 3D intersection point, or {@code null} if the arcs share no
     *         face whose UV segments cross strictly
     */
    private static Vector3f intersection3D(SplitArcTracer.SplitArc arc1,
                                            SplitArcTracer.SplitArc arc2,
                                            ArrayMesh mesh,
                                            float[] uCorner, float[] vCorner) {
        for (SplitEdge ea : arc1.edges()) {
            for (SplitEdge eb : arc2.edges()) {
                if (ea.faceId() != eb.faceId()) continue;
                double[] rs = strictIntersect(
                        ea.u1(), ea.v1(), ea.u2(), ea.v2(),
                        eb.u1(), eb.v1(), eb.u2(), eb.v2());
                if (rs == null) continue;
                float u = (float) (ea.u1() + rs[0] * (ea.u2() - ea.u1()));
                float v = (float) (ea.v1() + rs[0] * (ea.v2() - ea.v1()));
                return baryToWorld(mesh, ea.faceId(), uCorner, vCorner, u, v);
            }
        }
        return null;
    }

    /**
     * Strict segment-segment intersection: returns [tA, tB] both in (EPS, 1-EPS) or null.
     *
     * @param a1u u of segment A start
     * @param a1v v of segment A start
     * @param a2u u of segment A end
     * @param a2v v of segment A end
     * @param b1u u of segment B start
     * @param b1v v of segment B start
     * @param b2u u of segment B end
     * @param b2v v of segment B end
     * @return two-element {@code [tA, tB]} when both fall in
     *         {@code (EPS, 1-EPS)}; {@code null} otherwise
     */
    private static double[] strictIntersect(double a1u, double a1v,
                                             double a2u, double a2v,
                                             double b1u, double b1v,
                                             double b2u, double b2v) {
        double dxA = a2u - a1u, dyA = a2v - a1v;
        double dxB = b2u - b1u, dyB = b2v - b1v;
        double denom = dxA * dyB - dyA * dxB;
        if (Math.abs(denom) < EPS) return null;
        double tA = ((b1u - a1u) * dyB - (b1v - a1v) * dxB) / denom;
        double tB = ((b1u - a1u) * dyA - (b1v - a1v) * dxA) / denom;
        if (tA <= EPS || tA >= 1 - EPS) return null;
        if (tB <= EPS || tB >= 1 - EPS) return null;
        return new double[]{tA, tB};
    }

    private static Vector3f baryToWorld(ArrayMesh mesh, int faceId,
                                        float[] uCorner, float[] vCorner,
                                        double u, double v) {
        float u0 = uCorner[faceId * NUM_3];
        float v0 = vCorner[faceId * NUM_3];
        float u1 = uCorner[faceId * NUM_3 + 1];
        float v1 = vCorner[faceId * NUM_3 + 1];
        float u2 = uCorner[faceId * NUM_3 + 2];
        float v2 = vCorner[faceId * NUM_3 + 2];
        double denom = (u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0);
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), p0);
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 1), p1);
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 2), p2);
        if (Math.abs(denom) < EPS) return new Vector3f(p0).add(p1).add(p2).mul(NUM_1 / NUM_3_2);
        double l1 = ((u - u0) * (v2 - v0) - (v - v0) * (u2 - u0)) / denom;
        double l2 = ((u1 - u0) * (v - v0) - (v1 - v0) * (u - u0)) / denom;
        double l0 = 1.0 - l1 - l2;
        return new Vector3f(
                (float) (l0 * p0.x + l1 * p1.x + l2 * p2.x),
                (float) (l0 * p0.y + l1 * p1.y + l2 * p2.y),
                (float) (l0 * p0.z + l1 * p1.z + l2 * p2.z));
    }

    /**
     * Result: parallel arrays. {@code positions[i*cols + j]} = 3D point at
     *  table cell (i, j). {@code rows} and {@code cols} give the table
     */
    public record Result(int rows, int cols, Vector3f[] positions) {}
}
