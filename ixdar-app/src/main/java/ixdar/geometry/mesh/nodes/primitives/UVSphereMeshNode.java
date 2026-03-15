package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.HalfEdgeMesh;

@MeshNodeAnnotation(id = "uv_sphere")
public class UVSphereMeshNode implements MeshNode {
    private static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 1.0f);
    private static final InputPort SEGMENTS = new InputPort("segments", PortType.INT, 32);
    private static final InputPort RINGS = new InputPort("rings", PortType.INT, 16);
    private static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(RADIUS, SEGMENTS, RINGS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        float radius = ctx.getInput("radius", Number.class) != null ? ctx.getInput("radius", Number.class).floatValue() : 1.0f;
        int segments = ctx.getInput("segments", Number.class) != null ? ctx.getInput("segments", Number.class).intValue() : 32;
        int rings = ctx.getInput("rings", Number.class) != null ? ctx.getInput("rings", Number.class).intValue() : 16;

        // Ensure minimum viable geometry
        segments = Math.max(3, segments);
        rings = Math.max(2, rings);

        HalfEdgeMesh mesh = new HalfEdgeMesh();

        int topPole = mesh.addVertex(0, radius, 0);
        int bottomPole = mesh.addVertex(0, -radius, 0);

        // Store vertex IDs to easily build faces later.
        // Array maps [ring][segment]
        int[][] ringVertices = new int[rings - 1][segments];

        // Generate inner ring vertices
        for (int i = 1; i < rings; i++) {
            float theta = (float) Math.PI * i / rings;
            float sinTheta = (float) Math.sin(theta);
            float cosTheta = (float) Math.cos(theta);

            for (int j = 0; j < segments; j++) {
                float phi = 2.0f * (float) Math.PI * j / segments;
                float sinPhi = (float) Math.sin(phi);
                float cosPhi = (float) Math.cos(phi);

                float x = radius * sinTheta * cosPhi;
                float y = radius * cosTheta;
                float z = radius * sinTheta * sinPhi;

                ringVertices[i - 1][j] = mesh.addVertex(x, y, z);
            }
        }

        for (int j = 0; j < segments; j++) {
            int nextJ = (j + 1) % segments;
            mesh.addFace(topPole, ringVertices[0][j], ringVertices[0][nextJ]); 
        }

        // Build Middle Bands (Quads)
        for (int i = 0; i < rings - 2; i++) {
            for (int j = 0; j < segments; j++) {
                int nextJ = (j + 1) % segments;
                int v0 = ringVertices[i][j];
                int v1 = ringVertices[i + 1][j];
                int v2 = ringVertices[i + 1][nextJ];
                int v3 = ringVertices[i][nextJ];
                mesh.addFace(v0, v1, v2, v3);
            }
        }

        // Build Bottom Cap (Triangles)
        for (int j = 0; j < segments; j++) {
            int nextJ = (j + 1) % segments;
            mesh.addFace(bottomPole, ringVertices[rings - 2][nextJ], ringVertices[rings - 2][j]);
        }
        mesh.computeNormals();
        ctx.setOutput("mesh", mesh);
    }
}