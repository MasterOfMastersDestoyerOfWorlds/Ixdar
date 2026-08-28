package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.Arrays;
import java.util.Map;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.api.UvField;

/**
 * The seamless parametrization's data: per-corner (u, v) over the active faces,
 * the active-index tables the corners are addressed through, and the cut graph
 * with its chart atlas. The field's cone points and feature edges flow as their
 * own values, not through this class; solve scratch stays on the producing
 * stage.
 */
public final class SeamlessUv implements UvField {

    /** Triangle corner count. */
    public static final int CORNERS_PER_FACE = 3;

    /** Number of cross-field branches (a 4-RoSy field has 4). */
    public static final int BRANCH_COUNT = 4;

    private static final double HALF = 0.5;

    /** Active face count. */
    public final int faceCount;

    /** Active edge count. */
    public final int edgeCount;

    /**
     * Active-edge to active-face index on the "A" side; -1 if that side is
     * boundary.
     */
    public final int[] edgeFaceA;

    /**
     * Active-edge to active-face index on the "B" side; -1 if that side is
     * boundary.
     */
    public final int[] edgeFaceB;

    /**
     * Active-edge to corner index of {@code halfEdgeVertex(edgeHalfEdge)} in face
     * A.
     */
    public final int[] edgeCornerInA;

    /**
     * Active-edge to corner index of {@code halfEdgeVertex(edgeHalfEdge)} in face
     * B.
     */
    public final int[] edgeCornerInB;

    /** Face id to active-face index; the key to reading the per-corner arrays. */
    public Map<Integer, Integer> faceIdToActive;

    /** Edge id to active-edge index; the key to reading the per-edge tables. */
    public Map<Integer, Integer> edgeIdToActive;

    /** Per-corner u, length {@code 3 * faceCount} (active-face order). */
    public double[] uCorner;

    /** Per-corner v, length {@code 3 * faceCount}. */
    public double[] vCorner;

    /**
     * Cut transition translation s<sub>e</sub>; only valid for INTERIOR cut edges.
     * Aliases the cut-graph atlas's u translations.
     */
    public double[] cutTranslationS;

    /**
     * Cut transition translation t<sub>e</sub>; only valid for INTERIOR cut edges.
     * Aliases the cut-graph atlas's v translations.
     */
    public double[] cutTranslationT;

    /** True iff every triangle has positive UV signed area. */
    public boolean injective;

    /** The cut graph: per-face charts and the seam transitions between them. */
    public CutGraph cutGraph;

    /** Target quad edge length in parametric units. */
    public float targetQuadEdgeLength;

    /**
     * Allocates the per-edge tables.
     *
     * @param faceCount active face count of the parametrized mesh
     * @param edgeCount active edge count of the parametrized mesh
     */
    public SeamlessUv(int faceCount, int edgeCount) {
        this.faceCount = faceCount;
        this.edgeCount = edgeCount;
        this.edgeFaceA = new int[edgeCount];
        this.edgeFaceB = new int[edgeCount];
        this.edgeCornerInA = new int[edgeCount];
        this.edgeCornerInB = new int[edgeCount];
        Arrays.fill(edgeFaceA, -1);
        Arrays.fill(edgeFaceB, -1);
        Arrays.fill(edgeCornerInA, -1);
        Arrays.fill(edgeCornerInB, -1);
    }

    /**
     * Per-corner u accessor.
     *
     * @param faceId    mesh face id
     * @param cornerIdx corner index in {@code [0, 3)}
     * @return u-coordinate at the given corner
     */
    @Override
    public double u(int faceId, int cornerIdx) {
        int activeFace = faceIdToActive.get(faceId);
        return uCorner[activeFace * CORNERS_PER_FACE + cornerIdx];
    }

    /**
     * Per-corner v accessor.
     *
     * @param faceId    mesh face id
     * @param cornerIdx corner index in {@code [0, 3)}
     * @return v-coordinate at the given corner
     */
    @Override
    public double v(int faceId, int cornerIdx) {
        int activeFace = faceIdToActive.get(faceId);
        return vCorner[activeFace * CORNERS_PER_FACE + cornerIdx];
    }

    /**
     * All three corner UVs of one face in one read.
     *
     * @param faceId mesh face id
     * @param out    length-6 buffer receiving {@code [u0,v0,u1,v1,u2,v2]}
     */
    @Override
    public void faceCornerUv(int faceId, double[] out) {
        int base = faceIdToActive.get(faceId) * CORNERS_PER_FACE;
        out[0] = uCorner[base];
        out[1] = vCorner[base];
        out[2] = uCorner[base + 1];
        out[3] = vCorner[base + 1];
        out[4] = uCorner[base + 2];
        out[5] = vCorner[base + 2];
    }

    /**
     * Signed UV area of a face; positive iff orientation is preserved.
     *
     * @param faceId mesh face id
     * @return signed UV-space triangle area
     */
    public double uvSignedArea(int faceId) {
        int activeFace = faceIdToActive.get(faceId);
        int o = activeFace * CORNERS_PER_FACE;
        double u0 = uCorner[o];
        double v0 = vCorner[o];
        double u1 = uCorner[o + 1];
        double v1 = vCorner[o + 1];
        double u2 = uCorner[o + 2];
        double v2 = vCorner[o + 2];
        return HALF * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
    }

    /**
     * Returns [u_p, v_p, u_q, v_q] for face's corners at vStart and vEnd.
     *
     * @param mesh   the parametrized mesh, for the corner-vertex lookup
     * @param faceId the face id
     * @param vStart the start vertex id
     * @param vEnd   the end vertex id
     * @return the corners coordinates
     */
    public double[] lookupCorners(HalfEdgeMesh mesh, int faceId, int vStart, int vEnd) {
        int cStart = -1;
        int cEnd = -1;
        for (int c = 0; c < CORNERS_PER_FACE; c++) {
            int vertex = mesh.faceVertexAt(faceId, c);
            if (vertex == vStart) {
                cStart = c;
            } else if (vertex == vEnd) {
                cEnd = c;
            }
        }
        return new double[] {
                u(faceId, cStart), v(faceId, cStart),
                u(faceId, cEnd), v(faceId, cEnd),
        };
    }
}
