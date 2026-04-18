package ixdar.annotations.meshnode;

import java.util.List;
import java.util.Map;

public interface MeshNode {
    List<InputPort> inputs();

    List<OutputPort> outputs();

    void evaluate(NodeContext ctx);

    default MeshNodeSchema schema() {
        return MeshNodeSchema.from(this);
    }

    default String description() {
        return "";
    }

    /**
     * True if this node consumes geometry bundle slots (e.g., bezier handle metadata)
     * and drops them from the output. Downstream nodes that depend on those slots
     * will not see them after a destructive node runs.
     */
    default boolean destructive() {
        return false;
    }

    /**
     * Names of geometry bundle slots this node consumes (removes from output).
     * Empty list if not destructive or if it only adds slots.
     */
    default List<String> consumes() {
        return List.of();
    }

    /**
     * Per-socket documentation: map from port name (as returned by
     * {@link #inputs()} or {@link #outputs()}) to a short description of what
     * that port does, what units it's in, and any non-obvious semantics.
     * <p>
     * Every port declared by a node must have a corresponding entry. This is
     * enforced by {@code unit.mesh.MeshNodePortDocumentationTest}, which
     * fails the build on missing or empty entries.
     * <p>
     * The default empty map is provided so the interface compile doesn't
     * break during migration; nodes without overrides will fail the
     * documentation test until each port is described.
     */
    default Map<String, String> socketDocs() {
        return Map.of();
    }
}