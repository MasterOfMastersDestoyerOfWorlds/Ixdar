package ixdar.geometry.mesh.nodes.api;

import java.util.List;
import java.util.Map;

public interface MeshNode {
    /**
     * Input ports this node reads from during {@link #evaluate}.
     *
     * @return ordered list of declared input ports
     */
    List<InputPort> inputs();

    /**
     * Output ports this node publishes to during {@link #evaluate}.
     *
     * @return ordered list of declared output ports
     */
    List<OutputPort> outputs();

    /**
     * Run the node: read inputs from {@code ctx}, compute, and publish outputs back
     * to {@code ctx}.
     *
     * @param ctx runtime context bound to this node's port shape
     */
    void evaluate(NodeContext ctx);

    /**
     * Static metadata snapshot of this node's port shape and lifecycle flags.
     *
     * @return schema derived from {@link #inputs()}, {@link #outputs()},
     *         {@link #destructive()}, {@link #consumes()}, and
     *         {@link #socketDocs()}
     */
    default MeshNodeSchema schema() {
        return MeshNodeSchema.from(this);
    }

    /**
     * Human-readable summary of what this node does, shown in editor tooltips and
     * documentation.
     *
     * @return short description; empty string when none is supplied
     */
    default String description() {
        return "";
    }

    /**
     * True if this node consumes geometry bundle slots (e.g., bezier handle
     * metadata) and drops them from the output. Downstream nodes that depend on
     * those slots will not see them after a destructive node runs.
     *
     * @return {@code true} if the node strips slots from its output bundle
     */
    default boolean destructive() {
        return false;
    }

    /**
     * Names of geometry bundle slots this node consumes (removes from output).
     * Empty list if not destructive or if it only adds slots.
     *
     * @return slot names dropped from the output bundle; empty when nothing is
     *         consumed
     */
    default List<String> consumes() {
        return List.of();
    }

    /**
     * Documentation for every declared input and output port, including units and
     * non-obvious semantics. Undocumented ports fail the registry test.
     *
     * @return port name to description map; empty by default
     */
    default Map<String, String> socketDocs() {
        return Map.of();
    }
}
