package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.HalfEdgeMesh;

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
        float halfExtent = (sizeInput == null ? 1.0f : sizeInput.floatValue()) * 0.5f;
        HalfEdgeMesh mesh = HalfEdgeMesh.buildFromIndexedMesh(
                new float[] {
                        -halfExtent, -halfExtent, -halfExtent,
                        halfExtent, -halfExtent, -halfExtent,
                        halfExtent, halfExtent, -halfExtent,
                        -halfExtent, halfExtent, -halfExtent,
                        -halfExtent, -halfExtent, halfExtent,
                        halfExtent, -halfExtent, halfExtent,
                        halfExtent, halfExtent, halfExtent,
                        -halfExtent, halfExtent, halfExtent,
                },
                new int[] {
                        0, 1, 2, 2, 3, 0,
                        4, 7, 6, 6, 5, 4,
                        0, 4, 5, 5, 1, 0,
                        3, 2, 6, 6, 7, 3,
                        1, 5, 6, 6, 2, 1,
                        0, 3, 7, 7, 4, 0,
                });
        ctx.setOutput("mesh", mesh);
    }
}
