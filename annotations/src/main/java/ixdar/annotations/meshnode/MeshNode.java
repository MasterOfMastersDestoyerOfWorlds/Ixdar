package ixdar.annotations.meshnode;

import java.util.List;

public interface MeshNode {
    List<InputPort> inputs();

    List<OutputPort> outputs();

    void evaluate(NodeContext ctx);

    default MeshNodeSchema schema() {
        return MeshNodeSchema.from(this);
    }
}