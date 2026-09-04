package ixdar.geometry.mesh.data;

/**
 * The one unwrap from a geometry port value to its topology.
 */
public final class GeometryBundles {

    private GeometryBundles() {
    }

    /**
     * Topology carried by a geometry port value.
     *
     * @param bundle value read from a {@code GEOMETRY_BUNDLE} port; null when the port is unset
     * @return the bundle's mesh, or {@code null} when {@code bundle} is null
     */
    public static MeshTopology meshPart(GeometryBundle bundle) {
        return bundle == null ? null : bundle.mesh();
    }
}
