package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.HalfEdgeMesh;

@MeshNodeAnnotation(id = "cylinder")
public class CylinderMeshNode implements MeshNode {
    public static final String RADIUS_2 = "radius";
    public static final String HEIGHT_2 = "height";
    public static final String SEGMENTS_2 = "segments";
    public static final String MESH_2 = "mesh";
    public static final int NUM_16 = 16;
    public static final int NUM_3 = 3;
    public static final float NUM_2_0 = 2.0f;
    private static final InputPort RADIUS = new InputPort(RADIUS_2, PortType.FLOAT, 1.0f, 0.001f, 100f);
    private static final InputPort HEIGHT = new InputPort(HEIGHT_2, PortType.FLOAT, 1.0f, 0.001f, 100f);
    private static final InputPort SEGMENTS = new InputPort(SEGMENTS_2, PortType.INT, 16, (float) 3, (float) 128);
    private static final OutputPort MESH = new OutputPort(MESH_2, PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(RADIUS, HEIGHT, SEGMENTS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public String description() {
        return "Generates a capped cylinder with triangle-fan caps, controlled by radius, height, and segment count around the circumference.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                RADIUS_2, "Distance from the central Y-axis to the side surface. cylinder(radius=r) spans ±r in X and Z (extent 2r on those axes).",
                HEIGHT_2, "Total Y-axis extent. cylinder(height=h) spans from y=-h/2 to y=+h/2 (extent = h, vertices at ±h/2).",
                SEGMENTS_2, "Number of divisions around the circumference. Higher = smoother cylinder. Default 16.",
                MESH_2, "Capped cylinder aligned with Y-axis, centered at origin."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        float radius = ctx.getInput(RADIUS_2, Number.class) != null ? ctx.getInput(RADIUS_2, Number.class).floatValue()
                : 1.0f;
        float height = ctx.getInput(HEIGHT_2, Number.class) != null ? ctx.getInput(HEIGHT_2, Number.class).floatValue()
                : 1.0f;
        int segments = ctx.getInput(SEGMENTS_2, Number.class) != null
                ? ctx.getInput(SEGMENTS_2, Number.class).intValue()
                : NUM_16;
        segments = Math.max(NUM_3, segments);

        HalfEdgeMesh mesh = new HalfEdgeMesh();

        int topPole = mesh.addVertex(0, height / 2, 0);
        int bottomPole = mesh.addVertex(0, -height / 2, 0);

        int[] topRingVertices = new int[segments];
        int[] bottomRingVertices = new int[segments];

        for (int j = 0; j < segments; j++) {
            float phi = NUM_2_0 * (float) Math.PI * j / segments;
            float sinPhi = (float) Math.sin(phi);
            float cosPhi = (float) Math.cos(phi);

            float x = radius * sinPhi;
            float z = radius * cosPhi;
            float y = height / 2;
            topRingVertices[j] = mesh.addVertex(x, y, z);
            y = -height / 2;
            bottomRingVertices[j] = mesh.addVertex(x, y, z);
        }
        for (int i = 0; i < segments; i++) {
            int nextI = (i + 1) % segments;
            mesh.addFace(topRingVertices[i], topRingVertices[nextI], bottomRingVertices[nextI], bottomRingVertices[i]);
            mesh.addFace(topPole, topRingVertices[nextI], topRingVertices[i]);
            mesh.addFace(bottomPole, bottomRingVertices[i], bottomRingVertices[nextI]);
        }

        mesh.computeNormals();
        ctx.setOutput(MESH_2, mesh);
    }
}
