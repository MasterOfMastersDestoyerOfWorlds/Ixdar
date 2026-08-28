package ixdar.geometry.mesh.nodes.api;

/**
 * Per-corner UV assignment over a mesh: the value behind
 * {@link PortType#UV_FIELD} ports. Producers include the seamless
 * parametrization and the integer grid map.
 */
public interface UvField {

    /**
     * The u-coordinate at a face corner.
     *
     * @param faceId mesh face id
     * @param corner corner index in {@code [0, 3)}
     * @return u-coordinate at the given corner
     */
    double u(int faceId, int corner);

    /**
     * The v-coordinate at a face corner.
     *
     * @param faceId mesh face id
     * @param corner corner index in {@code [0, 3)}
     * @return v-coordinate at the given corner
     */
    double v(int faceId, int corner);

    /**
     * All three corner UVs of one face in one read.
     *
     * @param faceId mesh face id
     * @param out    length-6 buffer receiving {@code [u0,v0,u1,v1,u2,v2]}
     */
    default void faceCornerUv(int faceId, double[] out) {
        for (int corner = 0; corner < 3; corner++) {
            out[corner * 2] = u(faceId, corner);
            out[corner * 2 + 1] = v(faceId, corner);
        }
    }
}
