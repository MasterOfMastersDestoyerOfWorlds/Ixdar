package ixdar.geometry.mesh.csg;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * A boolean's triangle mesh together with where each of its faces came from, which is what lets a
 * later stage keep the untouched quads of both inputs and re-quadrangulate only the seam.
 *
 * <p>See also: NHE*19 Section 3.1
 */
public final class MeshBooleanResult {

    public static final int ORIGIN_A = 0;

    public static final int ORIGIN_B = 1;

    public static final int ORIGIN_NEW = -1;

    /** The boolean's output, all triangles. */
    public final HalfEdgeMesh mesh;

    /**
     * Per face in active-face order: {@link #ORIGIN_A} or {@link #ORIGIN_B} when the face is an
     * untouched copy of an input triangle, {@link #ORIGIN_NEW} when the boolean split it.
     */
    public final int[] faceOrigin;

    /**
     * Per face: the operand whose surface the face lies on, {@link #ORIGIN_A} or {@link #ORIGIN_B},
     * for new faces as well as untouched ones.
     */
    public final int[] faceSourceOperand;

    /**
     * Per face: the face id in {@link #faceSourceOperand}'s mesh the face was copied or cut from,
     * or {@code -1} where the kernel gave nothing to trace it by.
     */
    public final int[] faceSourceQuad;

    /**
     * Per output face corner, the {@code u} the kernel interpolated from the operands' UVs, or null
     * when neither operand carried any.
     */
    public final double[] cornerU;

    /** Per output face corner, the interpolated {@code v}, matching {@link #cornerU}. */
    public final double[] cornerV;

    /**
     * Store a boolean's output, its provenance and its interpolated UVs.
     *
     * @param mesh the triangle mesh the boolean produced
     * @param faceOrigin operand per untouched face, {@link #ORIGIN_NEW} per split face
     * @param faceSourceOperand operand whose surface each face lies on
     * @param faceSourceQuad source face id per face, {@code -1} where untraceable
     * @param cornerU interpolated {@code u} per face corner, or null when the operands had no UVs
     * @param cornerV interpolated {@code v} per face corner, or null alongside {@code cornerU}
     */
    public MeshBooleanResult(HalfEdgeMesh mesh, int[] faceOrigin, int[] faceSourceOperand,
            int[] faceSourceQuad, double[] cornerU, double[] cornerV) {
        this.mesh = mesh;
        this.faceOrigin = faceOrigin;
        this.faceSourceOperand = faceSourceOperand;
        this.faceSourceQuad = faceSourceQuad;
        this.cornerU = cornerU;
        this.cornerV = cornerV;
    }
}
