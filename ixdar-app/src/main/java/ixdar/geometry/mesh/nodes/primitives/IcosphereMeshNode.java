package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.HalfEdgeMesh;

@MeshNodeAnnotation(id = "icosphere")
public class IcosphereMeshNode implements MeshNode {
    private static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 1.0f);
    private static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(RADIUS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        float radius = ctx.getInput("radius", Number.class) != null ? ctx.getInput("radius", Number.class).floatValue()
                : 1.0f;

        HalfEdgeMesh mesh = new HalfEdgeMesh();

        float pi = (float) Math.PI;
        float horizontalOffset = pi / 180 * 72; // 72 degrees between ring vertices
        float elevation = (float) Math.atan(1.0 / 2.0); // Elevation angle for rings

        int topPole = mesh.addVertex(0, radius, 0);

        // Note: segments should be explicitly 5 for an icosahedron
        int[] topRingVertices = new int[5];
        int[] bottomRingVertices = new int[5];

        // 1. GENERATE VERTICES
        // Upper Ring (Indices 0 to 4)
        for (int i = 0; i < 5; i++) {
            float hAngle = i * horizontalOffset;
            // Apply radius to x, y, and z
            float x = (float) (radius * Math.cos(elevation) * Math.cos(hAngle));
            float y = (float) (radius * Math.sin(elevation));
            float z = (float) (radius * Math.cos(elevation) * Math.sin(hAngle));
            topRingVertices[i] = mesh.addVertex(x, y, z);
        }

        // Lower Ring (Indices 0 to 4) - Offset by 36 degrees (H_ANGLE / 2)
        for (int i = 0; i < 5; i++) {
            float hAngle = i * horizontalOffset + (horizontalOffset / 2);
            // Apply radius to x, y, and z
            float x = (float) (radius * Math.cos(elevation) * Math.cos(hAngle));
            float y = (float) (radius * -Math.sin(elevation));
            float z = (float) (radius * Math.cos(elevation) * Math.sin(hAngle));
            bottomRingVertices[i] = mesh.addVertex(x, y, z);
        }

        int bottomPole = mesh.addVertex(0, -radius, 0);

        // 2. STITCH FACES (Ensuring Counter-Clockwise Winding for Outward Normals)
        for (int i = 0; i < 5; i++) {
            int next = (i + 1) % 5;

            // Top Cap Triangles
            mesh.addFace(topPole, topRingVertices[i], topRingVertices[next]);

            // Middle Band Triangles (Pointing UP)
            // Connects: Current Top -> Current Bottom -> Next Top
            mesh.addFace(topRingVertices[i], bottomRingVertices[i], topRingVertices[next]);

            // Middle Band Triangles (Pointing DOWN)
            // Connects: Current Bottom -> Next Bottom -> Next Top
            mesh.addFace(bottomRingVertices[i], bottomRingVertices[next], topRingVertices[next]);

            // Bottom Cap Triangles
            // Winding reversed (next, then i) so the normal points outward from the bottom
            mesh.addFace(bottomPole, bottomRingVertices[next], bottomRingVertices[i]);
        }

        mesh.computeNormals();
        ctx.setOutput("mesh", mesh);
    }
}