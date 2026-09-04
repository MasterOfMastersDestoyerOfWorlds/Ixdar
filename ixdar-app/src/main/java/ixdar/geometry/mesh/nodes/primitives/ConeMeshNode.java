package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

@MeshNodeAnnotation(id = "cone")
public class ConeMeshNode implements MeshNode {
    public static final int NUM_16 = 16;
    public static final int NUM_3 = 3;
    public static final float NUM_2_0 = 2.0f;
    public static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 1.0f, 0.001f, 100f);
    public static final InputPort HEIGHT = new InputPort("height", PortType.FLOAT, 1.0f, 0.001f, 100f);
    public static final InputPort SEGMENTS = new InputPort("segments", PortType.INT, 16, (float) 3, (float) 128);
    public static final OutputPort MESH = new OutputPort("mesh", PortType.GEOMETRY_BUNDLE);

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
        return "Generates a cone with a circular base and a single apex point, controlled by radius, height, and segment count.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                RADIUS.name, "Base circle radius. cone(radius=r) base spans ±r in X and Z (extent 2r at the base).",
                HEIGHT.name, "Total Y-axis extent from base to apex. cone(height=h) base at y=-h/2, apex at y=+h/2.",
                SEGMENTS.name, "Number of divisions around the base circumference. Higher = smoother cone. Default 16.",
                MESH.name, "Cone aligned with Y-axis (apex up), centered at origin."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        float radius = ctx.getInput(RADIUS.name, Number.class) != null ? ctx.getInput(RADIUS.name, Number.class).floatValue()
                : 1.0f;
        float height = ctx.getInput(HEIGHT.name, Number.class) != null ? ctx.getInput(HEIGHT.name, Number.class).floatValue()
                : 1.0f;
        int segments = ctx.getInput(SEGMENTS.name, Number.class) != null
                ? ctx.getInput(SEGMENTS.name, Number.class).intValue()
                : NUM_16;
        segments = Math.max(NUM_3, segments);

        HalfEdgeMesh mesh = new HalfEdgeMesh();

        int topPole = mesh.addVertex(0, height / 2, 0);
        int bottomPole = mesh.addVertex(0, -height / 2, 0);

        int[] bottomRingVertices = new int[segments];

        for (int j = 0; j < segments; j++) {
            float phi = NUM_2_0 * (float) Math.PI * j / segments;
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
            mesh.addFace(bottomPole, bottomRingVertices[nextI], bottomRingVertices[i]);
        }

        mesh.computeNormals();
        ctx.setOutput(MESH.name, GeometryBundle.ofMesh(mesh));
    }
}
