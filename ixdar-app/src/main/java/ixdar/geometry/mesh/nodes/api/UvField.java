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
}
