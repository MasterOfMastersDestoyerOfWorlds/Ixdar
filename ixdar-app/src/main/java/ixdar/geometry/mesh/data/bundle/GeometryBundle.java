package ixdar.geometry.mesh.data.bundle;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Mesh plus named slots for stub fields (float arrays, flags, etc.) used by the
 * geometry-node graph.
 */
public final class GeometryBundle {

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
     * Bundle holding an empty mesh and no slots.
     *
     * @return a fresh "no geometry" bundle
     */
    public static GeometryBundle empty() {
        return new GeometryBundle(new HalfEdgeMesh(), Map.of());
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
     * Return a copy without {@code key}, for an op whose output cannot carry that slot.
     *
     * @param key slot name to drop, which need not be present
     * @return new bundle sharing this mesh
     */
    public GeometryBundle withoutSlot(String key) {
        if (!slots.containsKey(key)) {
            return this;
        }
        HashMap<String, Object> next = new HashMap<>(slots);
        next.remove(key);
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
