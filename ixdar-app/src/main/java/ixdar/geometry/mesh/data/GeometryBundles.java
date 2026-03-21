package ixdar.geometry.mesh.data;

public final class GeometryBundles {

    private GeometryBundles() {
    }

    public static HalfEdgeMesh meshPart(Object o) {
        if (o instanceof HalfEdgeMesh m) {
            return m;
        }
        if (o instanceof GeometryBundle b) {
            return b.mesh();
        }
        return null;
    }

    public static GeometryBundle bundlePart(Object o) {
        if (o instanceof GeometryBundle b) {
            return b;
        }
        if (o instanceof HalfEdgeMesh m) {
            return GeometryBundle.ofMesh(m);
        }
        return null;
    }

    public static GeometryBundle requireBundle(Object o) {
        GeometryBundle b = bundlePart(o);
        if (b != null) {
            return b;
        }
        return GeometryBundle.empty();
    }
}
