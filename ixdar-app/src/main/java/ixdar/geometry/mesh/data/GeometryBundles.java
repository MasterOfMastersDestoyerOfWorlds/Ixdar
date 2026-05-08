package ixdar.geometry.mesh.data;

public final class GeometryBundles {

    private GeometryBundles() {
    }

    /**
     * TODO: document {@code meshPart}.
     *
     * @param o TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code bundlePart}.
     *
     * @param o TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code requireBundle}.
     *
     * @param o TODO: describe
     * @return TODO: describe
     */
    public static GeometryBundle requireBundle(Object o) {
        GeometryBundle b = bundlePart(o);
        if (b != null) {
            return b;
        }
        return GeometryBundle.empty();
    }
}
