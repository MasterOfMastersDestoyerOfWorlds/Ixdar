package ixdar.geometry.mesh.csg;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * A boolean's triangle mesh, whose face layers say where each face came from: the operand in
 * {@link HalfEdgeMesh#SOURCE_OPERAND}, its face there in {@link HalfEdgeMesh#SOURCE_FACE}, and
 * whether it is an untouched copy in {@link #FACE_ORIGIN_LAYER}.
 *
 * <p>See also: NHE*19 Section 3.1
 */
public final class MeshBooleanResult {

    public static final int ORIGIN_A = 0;

    public static final int ORIGIN_B = 1;

    public static final int ORIGIN_NEW = -1;

    public static final String FACE_ORIGIN_LAYER = "_boolean_face_origin";

    /** The boolean's output, all triangles, carrying its provenance layers. */
    public final HalfEdgeMesh mesh;

    /**
     * Per output face corner, the {@code u} the kernel interpolated from the operands' UVs, or null
     * when neither operand carried any.
     */
    public final double[] cornerU;

    /** Per output face corner, the interpolated {@code v}, matching {@link #cornerU}. */
    public final double[] cornerV;

    /**
     * Store a boolean's output and its interpolated UVs.
     *
     * @param mesh the triangle mesh the boolean produced, its provenance layers written
     * @param cornerU interpolated {@code u} per face corner, or null when the operands had no UVs
     * @param cornerV interpolated {@code v} per face corner, or null alongside {@code cornerU}
     */
    public MeshBooleanResult(HalfEdgeMesh mesh, double[] cornerU, double[] cornerV) {
        this.mesh = mesh;
        this.cornerU = cornerU;
        this.cornerV = cornerV;
    }
}
