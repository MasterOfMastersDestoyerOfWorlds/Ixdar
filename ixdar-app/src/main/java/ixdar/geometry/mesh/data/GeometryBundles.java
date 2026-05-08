package ixdar.geometry.mesh.data;

/**
 * Helpers for unwrapping mesh-node graph values that may be either a raw
 * {@link MeshTopology} or a {@link GeometryBundle} carrying one.
 */
public final class GeometryBundles {

    private GeometryBundles() {
    }

    /**
     * Extract the mesh component, accepting either a raw mesh or a bundle.
     *
     * @param o value of unknown shape
     * @return underlying topology, or {@code null} if {@code o} is neither
     */
    public static MeshTopology meshPart(Object o) {
        if (o instanceof MeshTopology m) {
            return m;
        }
        if (o instanceof GeometryBundle b) {
            return b.mesh();
        }
        return null;
    }

    /**
     * Coerce a raw mesh or existing bundle into a {@link GeometryBundle}.
     *
     * @param o value of unknown shape
     * @return bundle view, or {@code null} if {@code o} is neither a bundle nor a mesh
     */
    public static GeometryBundle bundlePart(Object o) {
        if (o instanceof GeometryBundle b) {
            return b;
        }
        if (o instanceof MeshTopology m) {
            return GeometryBundle.ofMesh(m);
        }
        return null;
    }

    /**
     * Like {@link #bundlePart} but substitutes {@link GeometryBundle#empty()} for null.
     *
     * @param o value of unknown shape
     * @return bundle view, never {@code null}
     */
    public static GeometryBundle requireBundle(Object o) {
        GeometryBundle b = bundlePart(o);
        if (b != null) {
            return b;
        }
        return GeometryBundle.empty();
    }
}
