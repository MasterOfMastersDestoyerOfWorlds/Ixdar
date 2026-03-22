package ixdar.annotations.meshnode;

import java.util.List;

public record MeshNodeSchema(List<InputPort> inputs, List<OutputPort> outputs) {
    public static MeshNodeSchema from(MeshNode node) {
        return new MeshNodeSchema(List.copyOf(node.inputs()), List.copyOf(node.outputs()));
    }
}
