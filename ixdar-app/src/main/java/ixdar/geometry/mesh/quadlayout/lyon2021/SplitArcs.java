package ixdar.geometry.mesh.quadlayout.lyon2021;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;

/**
 * PATCH-64 Stage A: subdivide each T-arc into integer-quantized split
 * vertices. Mirrors metriko's {@code visualizer::construct_verts_on_tedge}
 * (gen_qgp_vert.h).
 *
 * <p>For T-arc {@code i} with quantization {@code X[i] = n} and parametric
 * length {@code R[i] = sum}, place {@code n + 1} split vertices at
 * parametric distances {@code (0, sum/n, 2*sum/n, ..., (n-1)*sum/n, sum)}
 * along the arc. Each split vertex carries its UV in the local face frame
 * plus the 3D mesh position (barycentric inverse-image).
 *
 * <p>This is the foundation of the Lyon 2021 / Bommes 2013 split-arc quad
 * mesh extraction: every T-arc gets {@code n + 1} sub-points; the resulting
 * grid of points across all arcs is wired into quads in subsequent stages
 * (see {@code QuadMeshAssembler}).
 */
public final class SplitArcs {
    public static final int NUM_3 = 3;
    public static final double NUM_1e_9 = 1e-9;
    public static final double NUM_1e_30 = 1e-30;
    public static final double NUM_1e_20 = 1e-20;
    public static final float NUM_1 = 1f;
    public static final float NUM_3_2 = 3f;

    private SplitArcs() {}

    /**
     * Subdivide every T-arc into split vertices. Returns parallel lists:
     * {@code splitsByArc[i]} = the (X[i] + 1) split vertices on arc {@code i}.
     *
     * @param tmesh   T-mesh from PATCH-41
     * @param mesh    underlying triangle mesh (for barycentric interpolation)
     * @param uCorner per-corner u, length {@code 3 * F}
     * @param vCorner per-corner v, length {@code 3 * F}
     * @param X       integer quantization vector indexed by arc id;
     *                {@code X.length == tmesh.arcs().size()}
     * @throws IllegalArgumentException if {@code X.length} does not match the
     *                                   T-mesh arc count
     * @return per-arc list of split vertices
     */
    public static List<List<SplitVert>> generate(TMesh tmesh, ArrayMesh mesh,
                                                  float[] uCorner, float[] vCorner,
                                                  int[] X) {
        List<TArc> arcs = tmesh.arcs();
        if (X.length != arcs.size()) {
            throw new IllegalArgumentException(
                    "X length (" + X.length + ") != arc count (" + arcs.size() + ")");
        }
        ArrayList<List<SplitVert>> result = new ArrayList<>(arcs.size());
        for (int aid = 0; aid < arcs.size(); aid++) {
            result.add(generateForArc(arcs.get(aid), aid, X[aid], mesh, uCorner, vCorner));
        }
        return result;
    }

    /**
     * Subdivide one T-arc into {@code num + 1} split vertices.
     * If {@code num <= 0}, returns just the single endpoint vertex (start).
     *
     * @param arc      T-arc to subdivide; provides {@code stepUvs} and
     *                 {@code meshFaceCrossings}
     * @param arcId    arc id stamped onto each emitted {@link SplitVert}
     * @param num      quantization {@code X[arcId]}; produces {@code num + 1}
     *                 split vertices when {@code num >= 1}
     * @param mesh     underlying triangle mesh used for barycentric inverse
     * @param uCorner  per-corner u (length {@code 3 * F})
     * @param vCorner  per-corner v (length {@code 3 * F})
     * @return ordered split vertices from arc start to arc end
     */
    static List<SplitVert> generateForArc(TArc arc, int arcId, int num,
                                          ArrayMesh mesh,
                                          float[] uCorner, float[] vCorner) {
        ArrayList<SplitVert> out = new ArrayList<>(Math.max(num + 1, 1));
        List<float[]> stepUvs = arc.stepUvs();
        List<int[]> faceCrossings = arc.meshFaceCrossings();
        if (stepUvs.isEmpty()) return out;

        boolean uAxis = (arc.direction() & 1) == 0;
        double sum = arc.parametricLength();

        // First split vertex: the arc's start point.
        {
            float[] uv0 = stepUvs.get(0);
            int faceId = faceCrossings.get(0)[0];
            Vector3f pos = baryToWorld(mesh, faceId, uCorner, vCorner, uv0[0], uv0[1]);
            out.add(new SplitVert(arcId, 0, uv0[0], uv0[1], pos));
        }

        if (num <= 1) {
            // Single split (just endpoints) — add the trailing endpoint.
            int last = stepUvs.size() - 1;
            float[] uvL = stepUvs.get(last);
            int faceId = faceCrossings.get(last)[0];
            Vector3f pos = baryToWorld(mesh, faceId, uCorner, vCorner, uvL[2], uvL[NUM_3]);
            out.add(new SplitVert(arcId, last, uvL[2], uvL[NUM_3], pos));
            return out;
        }

        double div = sum / num;

        // Walk steps, placing a split vertex every `div` units of cumulative
        // parametric length. Each step contributes |uOut - uIn| (u-axis) or
        // |vOut - vIn| (v-axis) of length.
        int curStep = 0;
        double curU = stepUvs.get(0)[0];
        double curV = stepUvs.get(0)[1];
        double accum = 0.0;        // length accumulated in the current step from curU/curV to step end
        double total = 0.0;        // total length placed in split verts so far (excluding first)
        int count = 1;
        // Tiny initial bias to avoid double-emission at the start vertex.
        accum = NUM_1e_9;

        while (count < num && curStep < stepUvs.size()) {
            float[] s = stepUvs.get(curStep);
            int faceId = faceCrossings.get(curStep)[0];
            // step end relative to (curU, curV)
            double dx = s[2] - curU;
            double dy = s[NUM_3] - curV;
            double stepRemainingLen = uAxis ? Math.abs(dx) : Math.abs(dy);
            if (accum + stepRemainingLen < div) {
                accum += stepRemainingLen;
                curStep++;
                if (curStep >= stepUvs.size()) break;
                curU = stepUvs.get(curStep)[0];
                curV = stepUvs.get(curStep)[1];
            } else {
                // Place split vertex at distance (div - accum) into the
                // remaining part of this step.
                double advance = div - accum;
                double fullLen = uAxis ? Math.abs(dx) : Math.abs(dy);
                double ratio = (fullLen > NUM_1e_30) ? advance / fullLen : 0.0;
                float newU = (float) (curU + ratio * dx);
                float newV = (float) (curV + ratio * dy);
                Vector3f pos = baryToWorld(mesh, faceId, uCorner, vCorner, newU, newV);
                out.add(new SplitVert(arcId, curStep, newU, newV, pos));
                count++;
                total += div;
                curU = newU;
                curV = newV;
                accum = 0.0;
            }
        }

        // Last split vertex: the arc's endpoint (clamped, even if the loop
        // above hadn't quite reached it due to float drift).
        int last = stepUvs.size() - 1;
        float[] uvL = stepUvs.get(last);
        int faceId = faceCrossings.get(last)[0];
        Vector3f endPos = baryToWorld(mesh, faceId, uCorner, vCorner, uvL[2], uvL[NUM_3]);
        out.add(new SplitVert(arcId, last, uvL[2], uvL[NUM_3], endPos));
        return out;
    }

    /**
     * Barycentric inverse-image: given (u, v) in face's UV frame, compute 3D position.
     *
     * @param mesh    triangle mesh holding the world-space vertex positions
     * @param faceId  index of the triangle whose UV frame contains (u, v)
     * @param uCorner per-corner u (length {@code 3 * F})
     * @param vCorner per-corner v (length {@code 3 * F})
     * @param u       u-coordinate in the face's UV frame
     * @param v       v-coordinate in the face's UV frame
     * @return interpolated world-space position; falls back to the centroid
     *          when the face's UV frame is degenerate
     */
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
        if (Math.abs(denom) < NUM_1e_20) {
            return new Vector3f(p0).add(p1).add(p2).mul(NUM_1 / NUM_3_2);
        }
        double l1 = ((u - u0) * (v2 - v0) - (v - v0) * (u2 - u0)) / denom;
        double l2 = ((u1 - u0) * (v - v0) - (v1 - v0) * (u - u0)) / denom;
        double l0 = 1.0 - l1 - l2;
        return new Vector3f(
                (float) (l0 * p0.x + l1 * p1.x + l2 * p2.x),
                (float) (l0 * p0.y + l1 * p1.y + l2 * p2.y),
                (float) (l0 * p0.z + l1 * p1.z + l2 * p2.z));
    }
}
