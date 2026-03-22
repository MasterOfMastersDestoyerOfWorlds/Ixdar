package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.HalfEdgeMesh;

@MeshNodeAnnotation(id = "cube")
public class CubeMeshNode implements MeshNode {
    private static final InputPort SIZE = new InputPort("size", PortType.FLOAT, 1.0f);
    private static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(SIZE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number sizeInput = ctx.getInput("size", Number.class);
        float c = (sizeInput == null ? 1.0f : sizeInput.floatValue()) * 0.5f;
        float[] positions = {
                -c, -c, -c,  c, -c, -c,  c,  c, -c, -c,  c, -c,
                -c, -c,  c,  c, -c,  c,  c,  c,  c, -c,  c,  c,
        };
        int[] quads = {
                0, 1, 2, 3,
                4, 7, 6, 5,
                0, 4, 5, 1,
                3, 2, 6, 7,
                1, 5, 6, 2,
                0, 3, 7, 4,
        };
        HalfEdgeMesh mesh = HalfEdgeMesh.bulkAllocate(positions, quads, 4);
        mesh.computeNormals();
        ctx.setOutput("mesh", mesh);
    }
}
