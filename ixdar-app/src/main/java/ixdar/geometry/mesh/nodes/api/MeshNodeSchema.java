package ixdar.geometry.mesh.nodes.api;

import java.util.List;
import java.util.Map;

public record MeshNodeSchema(
        List<InputPort> inputs,
        List<OutputPort> outputs,
        boolean destructive,
        List<String> consumes,
        Map<String, String> socketDocs) {
    /**
     * Capture an immutable snapshot of {@code node}'s port shape and lifecycle flags.
     *
     * @param node node to snapshot; its lists and maps are defensively copied
     * @return schema holding copies of the node's inputs, outputs, destructive flag, consumed slots, and socket docs
     */
    public static MeshNodeSchema from(MeshNode node) {
        return new MeshNodeSchema(
                List.copyOf(node.inputs()),
                List.copyOf(node.outputs()),
                node.destructive(),
                List.copyOf(node.consumes()),
                Map.copyOf(node.socketDocs()));
    }
}
