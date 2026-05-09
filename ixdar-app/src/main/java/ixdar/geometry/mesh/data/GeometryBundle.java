package ixdar.geometry.mesh.data;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import ixdar.annotations.meshnode.GeometryBundleValue;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Mesh plus named slots for stub fields (float arrays, flags, etc.) used by the
 * geometry-node graph.
 */
public final class GeometryBundle implements GeometryBundleValue {

    private final MeshTopology mesh;
    private final Map<String, Object> slots;

    /**
     * Wrap a mesh and a slot map; the slot map is defensively copied as immutable.
     *
     * @param mesh underlying topology
     * @param slots arbitrary named auxiliary data
     */
    public GeometryBundle(MeshTopology mesh, Map<String, Object> slots) {
        this.mesh = mesh;
        this.slots = Map.copyOf(slots);
    }

    /**
     * Build a bundle wrapping {@code mesh} with no slots.
     *
     * @param mesh non-null topology to wrap
     * @return bundle with empty slot map
     */
    public static GeometryBundle ofMesh(MeshTopology mesh) {
        Objects.requireNonNull(mesh, "mesh");
        return new GeometryBundle(mesh, Map.of());
    }

    /**
     * Bundle holding an empty quad mesh and no slots.
     *
     * @return shared sentinel for "no geometry"
     */
    public static GeometryBundle empty() {
        return new GeometryBundle(ArrayMeshEngine.emptyQuads(), Map.of());
    }

    /**
     * Underlying topology.
     *
     * @return mesh passed at construction
     */
    public MeshTopology mesh() {
        return mesh;
    }

    /**
     * When the bundle holds a {@link HalfEdgeMesh} (mutable topology), returns it;
     * otherwise null. Dense {@link ArrayMesh} bundles are not mutable via this
     * accessor.
     *
     * @return wrapped half-edge mesh, or {@code null} if this is an array mesh
     */
    public HalfEdgeMesh mutableMesh() {
        return mesh instanceof HalfEdgeMesh h ? h : null;
    }

    /**
     * Immutable view of the bundle's named auxiliary data.
     *
     * @return slot map (read-only)
     */
    public Map<String, Object> slots() {
        return slots;
    }

    /**
     * Return a copy with {@code key} mapped to {@code value} (other slots preserved).
     *
     * @param key slot name
     * @param value slot value (any object)
     * @return new bundle sharing this mesh
     */
    public GeometryBundle withSlot(String key, Object value) {
        HashMap<String, Object> next = new HashMap<>(slots);
        next.put(key, value);
        return new GeometryBundle(mesh, next);
    }

    /**
     * Return a copy that swaps in {@code newMesh} but keeps the existing slots.
     *
     * @param newMesh replacement topology
     * @return new bundle sharing this slot map
     */
    public GeometryBundle withMesh(MeshTopology newMesh) {
        return new GeometryBundle(newMesh, slots);
    }
}
