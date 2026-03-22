package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.HalfEdgeMesh;

@MeshNodeAnnotation(id = "sphere")
public class QuadSphereMeshNode implements MeshNode {
    private static final InputPort SIZE = new InputPort("size", PortType.FLOAT, 1.0f);
    // Defines how many quads make up the circumference of the sphere
    private static final InputPort RESOLUTION = new InputPort("resolution", PortType.INT, 16);
    private static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(SIZE, RESOLUTION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number sizeInput = ctx.getInput("size", Number.class);
        float radius = (sizeInput == null ? 1.0f : sizeInput.floatValue()) * 0.5f;

        Number resInput = ctx.getInput("resolution", Number.class);
        int resolution = resInput == null ? 16 : resInput.intValue();

        // Since 4 cube faces wrap around the sphere's great circle,
        // we divide the resolution by 4 to get the subdivisions per face.
        int divisions = Math.max(1, resolution / 4);

        // Define the 6 faces of a cube by their normal, right, and up vectors
        float[][][] faces = {
                { { 0, 0, 1 }, { 1, 0, 0 }, { 0, 1, 0 } },
                { { 0, 0, -1 }, { -1, 0, 0 }, { 0, 1, 0 } },
                { { 1, 0, 0 }, { 0, 0, -1 }, { 0, 1, 0 } },
                { { -1, 0, 0 }, { 0, 0, 1 }, { 0, 1, 0 } },
                { { 0, 1, 0 }, { 1, 0, 0 }, { 0, 0, -1 } }, 
                { { 0, -1, 0 }, { 1, 0, 0 }, { 0, 0, 1 } } 
        };

        HalfEdgeMesh mesh = new HalfEdgeMesh();

        for (float[][] face : faces) {
            float[] n = face[0];
            float[] r = face[1];
            float[] u = face[2];

            for (int i = 0; i < divisions; i++) {
                for (int j = 0; j < divisions; j++) {
                    float u0 = (i / (float) divisions) * 2.0f - 1.0f;
                    float u1 = ((i + 1) / (float) divisions) * 2.0f - 1.0f;
                    float v0 = (j / (float) divisions) * 2.0f - 1.0f;
                    float v1 = ((j + 1) / (float) divisions) * 2.0f - 1.0f;

                    int v00 = addVertex(n, r, u, u0, v0, radius, mesh);
                    int v10 = addVertex(n, r, u, u1, v0, radius, mesh);
                    int v11 = addVertex(n, r, u, u1, v1, radius, mesh);
                    int v01 = addVertex(n, r, u, u0, v1, radius, mesh);

                    mesh.addFace(v00, v10, v11, v01);
                }
            }
        }

        mesh.computeNormals();
        ctx.setOutput("mesh", mesh);
    }

    private int addVertex(float[] n, float[] r, float[] u, float uCoord, float vCoord, float radius, HalfEdgeMesh mesh) {

        // Map 2D face coordinate to 3D cube surface
        float x = n[0] + r[0] * uCoord + u[0] * vCoord;
        float y = n[1] + r[1] * uCoord + u[1] * vCoord;
        float z = n[2] + r[2] * uCoord + u[2] * vCoord;

        // Spherize by normalizing the vector and scaling by radius
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        float nx = (x / len) * radius;
        float ny = (y / len) * radius;
        float nz = (z / len) * radius;

        return mesh.addVertex(nx, ny, nz);
    }
}