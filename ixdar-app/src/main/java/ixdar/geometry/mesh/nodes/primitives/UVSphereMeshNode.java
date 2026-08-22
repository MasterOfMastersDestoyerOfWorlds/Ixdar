package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

@MeshNodeAnnotation(id = "uv_sphere")
public class UVSphereMeshNode implements MeshNode {
    public static final int NUM_32 = 32;
    public static final int NUM_16 = 16;
    public static final int NUM_3 = 3;
    public static final float NUM_2_0 = 2.0f;
    public static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 1.0f, 0.001f, 100f);
    public static final InputPort SEGMENTS = new InputPort("segments", PortType.INT, 32, (float) 3, (float) 128);
    public static final InputPort RINGS = new InputPort("rings", PortType.INT, 16, (float) 1, (float) 64);
    public static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(RADIUS, SEGMENTS, RINGS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public String description() {
        return "Generates a latitude/longitude sphere with quad bands and triangle-fan poles, controlled by radius, segments (longitude), and rings (latitude).";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                RADIUS.name, "Distance from center to surface. uv_sphere(radius=r) has extent 2r on each axis (vertices at ±r). For a reference of extent <X,Y,Z>, start with radius=1 and apply transform_geometry(scale=<X/2, Y/2, Z/2>).",
                SEGMENTS.name, "Longitudinal divisions (meridians). Higher = smoother around the equator. Default 32.",
                RINGS.name, "Latitudinal bands between the two poles. Higher = smoother pole-to-pole. Default 16.",
                MESH.name, "Quad-banded sphere with triangle-fan poles, centered at origin."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        float radius = ctx.getInput(RADIUS.name, Number.class) != null ? ctx.getInput(RADIUS.name, Number.class).floatValue() : 1.0f;
        int segments = ctx.getInput(SEGMENTS.name, Number.class) != null ? ctx.getInput(SEGMENTS.name, Number.class).intValue() : NUM_32;
        int rings = ctx.getInput(RINGS.name, Number.class) != null ? ctx.getInput(RINGS.name, Number.class).intValue() : NUM_16;

        // Ensure minimum viable geometry
        segments = Math.max(NUM_3, segments);
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
                float phi = NUM_2_0 * (float) Math.PI * j / segments;
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
        ctx.setOutput(MESH.name, mesh);
    }
}