package ixdar.geometry.mesh.nodes.api;

/**
 * Implicit geometry domain for field input nodes (vertex positions/normals for the current mesh).
 */
public interface FieldContext {

    /**
     * Number of elements (e.g. vertices) in the active domain. Every field returned by this context
     * has exactly this many entries.
     *
     * @return element count of the active domain
     */
    int elementCount();

    /**
     * Per-element world-space positions for the active domain.
     *
     * @return packed xyz positions of length {@link #elementCount()}
     */
    Vector3Field positions();

    /**
     * Per-element surface normals for the active domain.
     *
     * @return packed xyz normals of length {@link #elementCount()}
     */
    Vector3Field normals();
}
