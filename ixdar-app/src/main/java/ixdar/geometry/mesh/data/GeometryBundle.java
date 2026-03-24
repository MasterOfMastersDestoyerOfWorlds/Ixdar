package ixdar.geometry.mesh.data;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import ixdar.annotations.meshnode.GeometryBundleValue;

/**
 * Mesh plus named slots for stub fields (float arrays, flags, etc.) used by the
 * geometry-node graph.
 */
public final class GeometryBundle implements GeometryBundleValue {

    private final MeshTopology mesh;
    private final Map<String, Object> slots;

    public GeometryBundle(MeshTopology mesh, Map<String, Object> slots) {
        this.mesh = mesh;
        this.slots = Map.copyOf(slots);
    }

    public static GeometryBundle ofMesh(MeshTopology mesh) {
        Objects.requireNonNull(mesh, "mesh");
        return new GeometryBundle(mesh, Map.of());
    }

    public static GeometryBundle empty() {
        return new GeometryBundle(ArrayMeshEngine.emptyQuads(), Map.of());
    }

    public MeshTopology mesh() {
        return mesh;
    }

    /**
     * When the bundle holds a {@link HalfEdgeMesh} (mutable topology), returns it;
     * otherwise null. Dense {@link ArrayMesh} bundles are not mutable via this
     * accessor.
     */
    public HalfEdgeMesh mutableMesh() {
        return mesh instanceof HalfEdgeMesh h ? h : null;
    }

    public Map<String, Object> slots() {
        return slots;
    }

    public GeometryBundle withSlot(String key, Object value) {
        HashMap<String, Object> next = new HashMap<>(slots);
        next.put(key, value);
        return new GeometryBundle(mesh, next);
    }

    public GeometryBundle withMesh(MeshTopology newMesh) {
        return new GeometryBundle(newMesh, slots);
    }
}
