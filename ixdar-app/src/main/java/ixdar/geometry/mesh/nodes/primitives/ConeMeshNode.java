package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.HalfEdgeMesh;

@MeshNodeAnnotation(id = "cone")
public class ConeMeshNode implements MeshNode {
    private static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 1.0f);
    private static final InputPort HEIGHT = new InputPort("height", PortType.FLOAT, 1.0f);
    private static final InputPort SEGMENTS = new InputPort("segments", PortType.INT, 16);
    private static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(RADIUS, HEIGHT, SEGMENTS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        float radius = ctx.getInput("radius", Number.class) != null ? ctx.getInput("radius", Number.class).floatValue()
                : 1.0f;
        float height = ctx.getInput("height", Number.class) != null ? ctx.getInput("height", Number.class).floatValue()
                : 1.0f;
        int segments = ctx.getInput("segments", Number.class) != null
                ? ctx.getInput("segments", Number.class).intValue()
                : 16;
        segments = Math.max(3, segments);

        HalfEdgeMesh mesh = new HalfEdgeMesh();

        int topPole = mesh.addVertex(0, height / 2, 0);
        int bottomPole = mesh.addVertex(0, -height / 2, 0);

        int[] bottomRingVertices = new int[segments];

        for (int j = 0; j < segments; j++) {
            float phi = 2.0f * (float) Math.PI * j / segments;
            float sinPhi = (float) Math.sin(phi);
            float cosPhi = (float) Math.cos(phi);

            float x = radius * sinPhi;
            float z = radius * cosPhi;
            float y = -height / 2;
            bottomRingVertices[j] = mesh.addVertex(x, y, z);
        }
        for (int i = 0; i < segments; i++) {
            int nextI = (i + 1) % segments;
            mesh.addFace(topPole, bottomRingVertices[nextI], bottomRingVertices[i]);
            mesh.addFace(bottomPole, bottomRingVertices[i], bottomRingVertices[nextI]);
        }

        mesh.computeNormals();
        ctx.setOutput("mesh", mesh);
    }
}
