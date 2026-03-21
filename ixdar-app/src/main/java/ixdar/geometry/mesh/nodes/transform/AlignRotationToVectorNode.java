package ixdar.geometry.mesh.nodes.transform;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.RotationValue;
import ixdar.annotations.meshnode.Vector3Value;

@MeshNodeAnnotation(id = "align_rotation_to_vector")
public class AlignRotationToVectorNode implements MeshNode {

    private static final InputPort VECTOR = new InputPort("vector", PortType.VECTOR3, new Vector3Value(0f, 1f, 0f));
    private static final OutputPort ROTATION = new OutputPort("rotation", PortType.ROTATION);

    @Override
    public List<InputPort> inputs() {
        return List.of(VECTOR);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(ROTATION);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ctx.setOutput("rotation", new RotationValue(0f, 0f, 0f, 1f));
    }
}
