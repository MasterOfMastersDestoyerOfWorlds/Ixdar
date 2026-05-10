package ixdar.geometry.mesh.quadlayout.extraction;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;

import java.util.Arrays;

/**
 * Per-half-edge rotation + translation describing how a parametric point
 * {@code (u, v)} transforms when crossed across the half-edge into the
 * neighbouring face's UV frame. Mirrors metriko's
 * {@code compute_trs_matrix} (qex/common.h):
 *
 * <pre>
 *   uv_neighbour = R(matching) * uv_self + T
 * </pre>
 *
 * where {@code R} is one of the four 90° rotations indexed by
 * {@code matching ∈ {0, 1, 2, 3}}, and {@code T} is the integer translation
 * needed to make the formula exact at the shared vertex (the {@code (j, k)}
 * translation from the IGM stage, recovered by rounding the residual).
 *
 * <p>Stored compactly as four parallel arrays indexed by half-edge id:
 * <ul>
 *   <li>{@code matching[h]} — rotation index, {@code 0..3}</li>
 *   <li>{@code translateU[h]} / {@code translateV[h]} — integer translation</li>
 *   <li>{@code interiorEdgeId[h]} — interior-edge id (if the half-edge's
 *       edge is interior; -1 otherwise)</li>
 * </ul>
 *
 * <p>Boundary half-edges have {@code matching = 0, T = (0, 0)}; their
 * iso-line tracer should never advance past them.
 */
public final class TransitionMatrix {
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;

    public final int[] matching;     // [halfEdgeCount]
    public final int[] translateU;   // [halfEdgeCount]
    public final int[] translateV;   // [halfEdgeCount]
    public final int[] interiorEdgeId; // [halfEdgeCount], -1 for boundary

    private TransitionMatrix(int[] matching, int[] translateU,
                             int[] translateV, int[] interiorEdgeId) {
        this.matching = matching;
        this.translateU = translateU;
        this.translateV = translateV;
        this.interiorEdgeId = interiorEdgeId;
    }

    /**
     * Compute TRS data for every half-edge.
     *
     * @param mesh underlying triangle mesh
     * @param uCorner per-corner u, length {@code 3 * F}
     * @param vCorner per-corner v, length {@code 3 * F}
     * @param combed combed cross field (matching values per interior edge);
     *               may be {@code null} for tests where matching is identically 0
     * @throws AssertionError if internal corner/face bookkeeping is inconsistent
     * @return populated transition matrix; boundary half-edges receive zeroed entries
     */
    public static TransitionMatrix compute(ArrayMesh mesh,
                                           float[] uCorner, float[] vCorner,
                                           CombedField combed) {
        int H = mesh.halfEdgeCount();
        int[] m = new int[H];
        int[] tx = new int[H];
        int[] ty = new int[H];
        int[] ie = new int[H];
        Arrays.fill(ie, -1);

        // Build interior-edge -> matching map up front.
        int[] interiorMatching = null;
        if (combed != null) {
            interiorMatching = combed.copyMatching();
        }
        // mesh-edge id -> interior-edge id (combed/matching arrays use the
        // INTERIOR-only enumeration). Without a CombedField we treat every
        // edge as matching=0.
        int[] meshEdgeToInterior = null;
        if (combed != null) {
            int Ei = combed.field().interiorEdgeCount();
            meshEdgeToInterior = new int[mesh.edgeCount()];
            Arrays.fill(meshEdgeToInterior, -1);
            for (int e = 0; e < Ei; e++) {
                meshEdgeToInterior[combed.field().edgeMeshId(e)] = e;
            }
        }

        for (int h = 0; h < H; h++) {
            if (!mesh.hasHalfEdge(h)) continue;
            int twin = mesh.halfEdgeTwin(h);
            int faceA = mesh.halfEdgeFace(h);
            if (twin < 0 || faceA < 0) continue;
            int faceB = mesh.halfEdgeFace(twin);
            if (faceB < 0) continue;
            int eMesh = mesh.halfEdgeEdge(h);
            int eInt = (meshEdgeToInterior != null && eMesh >= 0
                    && eMesh < meshEdgeToInterior.length)
                    ? meshEdgeToInterior[eMesh] : -1;
            ie[h] = eInt;

            int rawMatching = (eInt >= 0 && interiorMatching != null)
                    ? interiorMatching[eInt] : 0;
            // Sign flip when h is the non-canonical side of its edge so the
            // twin gets the inverse rotation. "Canonical" = h has the smaller
            // half-edge id of the pair.
            boolean canonical = h < twin;
            int signed = canonical ? rawMatching : -rawMatching;
            int rot = ((signed % NUM_4) + NUM_4) % NUM_4;
            m[h] = rot;

            // UV at the SHARED vertex from each face's perspective:
            //   uv1 = uv at h's HEAD on face A (head = next corner of h within faceA)
            //   uv2 = uv at twin's TAIL on face B (twin's tail = same physical vertex)
            int cornerA = h % NUM_3;
            int headCornerA = mesh.halfEdgeNext(h) % NUM_3;
            int twinCornerB = twin % NUM_3;
            float u1 = uCorner[faceA * NUM_3 + headCornerA];
            float v1 = vCorner[faceA * NUM_3 + headCornerA];
            float u2 = uCorner[faceB * NUM_3 + twinCornerB];
            float v2 = vCorner[faceB * NUM_3 + twinCornerB];

            // Rotate uv1 by rot * 90° CCW.
            double ru, rv;
            switch (rot) {
                case 0:  ru =  u1; rv =  v1; break;
                case 1:  ru = -v1; rv =  u1; break;
                case 2:  ru = -u1; rv = -v1; break;
                default: ru =  v1; rv = -u1; break;
            }
            tx[h] = (int) Math.round(u2 - ru);
            ty[h] = (int) Math.round(v2 - rv);
            // Suppress unused-variable warnings.
            if (cornerA < 0 || faceA < 0) throw new AssertionError();
        }
        return new TransitionMatrix(m, tx, ty, ie);
    }

    /**
     * Apply this half-edge's transition to a (u, v) point: returns rotated + translated point.
     *
     * @param h  half-edge id whose TRS is applied
     * @param uv length-2 in/out array {@code [u, v]} updated in place
     */
    public void transformPoint(int h, float[] uv) {
        applyRotation(matching[h], uv);
        uv[0] += translateU[h];
        uv[1] += translateV[h];
    }

    /**
     * Apply this half-edge's rotation (no translation) to a direction vector.
     *
     * @param h   half-edge id whose rotation is applied
     * @param dir length-2 in/out direction vector updated in place
     */
    public void transformDirection(int h, float[] dir) {
        applyRotation(matching[h], dir);
    }

    private static void applyRotation(int rot, float[] xy) {
        float x = xy[0], y = xy[1];
        switch (rot) {
            case 0:  xy[0] =  x; xy[1] =  y; break;
            case 1:  xy[0] = -y; xy[1] =  x; break;
            case 2:  xy[0] = -x; xy[1] = -y; break;
            default: xy[0] =  y; xy[1] = -x; break;
        }
    }
}
