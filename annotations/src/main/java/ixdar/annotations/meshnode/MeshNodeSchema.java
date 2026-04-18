package ixdar.annotations.meshnode;

import java.util.List;
import java.util.Map;

public record MeshNodeSchema(
        List<InputPort> inputs,
        List<OutputPort> outputs,
        boolean destructive,
        List<String> consumes,
        Map<String, String> socketDocs) {
    public static MeshNodeSchema from(MeshNode node) {
        return new MeshNodeSchema(
                List.copyOf(node.inputs()),
                List.copyOf(node.outputs()),
                node.destructive(),
                List.copyOf(node.consumes()),
                Map.copyOf(node.socketDocs()));
    }
}
