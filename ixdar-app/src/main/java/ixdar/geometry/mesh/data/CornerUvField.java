package ixdar.geometry.mesh.data;

import ixdar.geometry.mesh.nodes.api.UvField;

/**
 * A {@link UvField} backed by flat per-corner arrays, indexed {@code faceId * 3 + corner}. The
 * generic array-backed implementation: a mesh file's texture coordinates and a seamless solve's
 * output are the same per-corner data, and both materialize into this.
 */
public final class CornerUvField implements UvField {

    /** Bundle slot a mesh's UV field rides on. */
    public static final String SLOT = "_uv";

    /** Corners per face; this field covers triangle meshes. */
    public static final int CORNERS_PER_FACE = 3;

    /** {@code u} per corner, {@code faceCount * 3} long. */
    public final double[] cornerU;

    /** {@code v} per corner, the same length and order as {@link #cornerU}. */
    public final double[] cornerV;

    /**
     * Wrap two equal-length per-corner arrays.
     *
     * @param cornerU {@code u} per corner, length divisible by three
     * @param cornerV {@code v} per corner, the same length as {@code cornerU}
     * @throws IllegalArgumentException when the arrays are null, differ in length, or are not whole
     *     faces
     */
    public CornerUvField(double[] cornerU, double[] cornerV) {
        if (cornerU == null || cornerV == null || cornerU.length != cornerV.length
                || cornerU.length % CORNERS_PER_FACE != 0) {
            throw new IllegalArgumentException(
                    "corner u and v must be equal-length arrays of whole faces");
        }
        this.cornerU = cornerU;
        this.cornerV = cornerV;
    }

    /**
     * Faces this field covers.
     *
     * @return face count
     */
    public int faceCount() {
        return cornerU.length / CORNERS_PER_FACE;
    }

    /**
     * Where one corner sits in {@link #cornerU} and {@link #cornerV}.
     *
     * @param faceId face id, which is the face index on a dense mesh
     * @param corner corner index in {@code [0, 3)}
     * @return index into the corner arrays
     */
    public int offset(int faceId, int corner) {
        return faceId * CORNERS_PER_FACE + corner;
    }

    @Override
    public double u(int faceId, int corner) {
        return cornerU[offset(faceId, corner)];
    }

    @Override
    public double v(int faceId, int corner) {
        return cornerV[offset(faceId, corner)];
    }
}
