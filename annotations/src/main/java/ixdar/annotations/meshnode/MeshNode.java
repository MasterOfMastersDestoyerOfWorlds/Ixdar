package ixdar.annotations.meshnode;

import java.util.List;

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
}