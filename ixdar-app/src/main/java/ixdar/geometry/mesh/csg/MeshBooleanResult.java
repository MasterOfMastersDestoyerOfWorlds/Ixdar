package ixdar.geometry.mesh.csg;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * A boolean's triangle mesh together with where each of its faces came from, which is what lets a
 * later stage keep the untouched quads of both inputs and re-quadrangulate only the seam.
 *
 * <p>See also: NHE*19 Section 3.1
 */
public final class MeshBooleanResult {

    /** {@link #faceOrigin} value for a face carried over from the first operand. */
    public static final int ORIGIN_A = 0;

    /** {@link #faceOrigin} value for a face carried over from the second operand. */
    public static final int ORIGIN_B = 1;

    /** {@link #faceOrigin} value for a face the boolean created along the intersection. */
    public static final int ORIGIN_NEW = -1;

    /** The boolean's output, all triangles. */
    public final HalfEdgeMesh mesh;

    /** Which operand each face came from: {@link #ORIGIN_A}, {@link #ORIGIN_B} or {@link #ORIGIN_NEW}. */
    public final int[] faceOrigin;

    /** Face id in the originating operand, or {@code -1} for faces the boolean created. */
    public final int[] faceSourceQuad;

    /**
     * Store a boolean's output and its provenance.
     *
     * @param mesh the triangle mesh the boolean produced
     * @param faceOrigin operand each face came from, one entry per face in active-face order
     * @param faceSourceQuad source face id per face, {@code -1} where the face is new
     */
    public MeshBooleanResult(HalfEdgeMesh mesh, int[] faceOrigin, int[] faceSourceQuad) {
        this.mesh = mesh;
        this.faceOrigin = faceOrigin;
        this.faceSourceQuad = faceSourceQuad;
    }
}
