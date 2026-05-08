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

    /**
     * TODO: document {@code GeometryBundle}.
     *
     * @param mesh TODO: describe
     * @param slots TODO: describe
     */
    public GeometryBundle(MeshTopology mesh, Map<String, Object> slots) {
        this.mesh = mesh;
        this.slots = Map.copyOf(slots);
    }

    /**
     * TODO: document {@code ofMesh}.
     *
     * @param mesh TODO: describe
     * @return TODO: describe
     */
    public static GeometryBundle ofMesh(MeshTopology mesh) {
        Objects.requireNonNull(mesh, "mesh");
        return new GeometryBundle(mesh, Map.of());
    }

    /**
     * TODO: document {@code empty}.
     *
     * @return TODO: describe
     */
    public static GeometryBundle empty() {
        return new GeometryBundle(ArrayMeshEngine.emptyQuads(), Map.of());
    }

    /**
     * TODO: document {@code mesh}.
     *
     * @return TODO: describe
     */
    public MeshTopology mesh() {
        return mesh;
    }

    /**
     * When the bundle holds a {@link HalfEdgeMesh} (mutable topology), returns it;
     * otherwise null. Dense {@link ArrayMesh} bundles are not mutable via this
     * accessor.
     *
     * @return TODO: describe
     */
    public HalfEdgeMesh mutableMesh() {
        return mesh instanceof HalfEdgeMesh h ? h : null;
    }

    /**
     * TODO: document {@code slots}.
     *
     * @return TODO: describe
     */
    public Map<String, Object> slots() {
        return slots;
    }

    /**
     * TODO: document {@code withSlot}.
     *
     * @param key TODO: describe
     * @param value TODO: describe
     * @return TODO: describe
     */
    public GeometryBundle withSlot(String key, Object value) {
        HashMap<String, Object> next = new HashMap<>(slots);
        next.put(key, value);
        return new GeometryBundle(mesh, next);
    }

    /**
     * TODO: document {@code withMesh}.
     *
     * @param newMesh TODO: describe
     * @return TODO: describe
     */
    public GeometryBundle withMesh(MeshTopology newMesh) {
        return new GeometryBundle(newMesh, slots);
    }
}
