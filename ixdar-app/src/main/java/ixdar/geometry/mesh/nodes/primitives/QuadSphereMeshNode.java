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

@MeshNodeAnnotation(id = "sphere")
public class QuadSphereMeshNode implements MeshNode {
    public static final String SIZE_2 = "size";
    public static final String RESOLUTION_2 = "resolution";
    public static final String MESH_2 = "mesh";
    public static final float NUM_0_5 = 0.5f;
    public static final int NUM_16 = 16;
    public static final int NUM_4 = 4;
    public static final float NUM_2_0 = 2.0f;
    private static final InputPort SIZE = new InputPort(SIZE_2, PortType.FLOAT, 1.0f, 0.001f, 100f);
    // Defines how many quads make up the circumference of the sphere
    private static final InputPort RESOLUTION = new InputPort(RESOLUTION_2, PortType.INT, 16, (float) 3, (float) 128);
    private static final OutputPort MESH = new OutputPort(MESH_2, PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(SIZE, RESOLUTION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public String description() {
        return "Generates an all-quad sphere by projecting a subdivided cube onto a sphere surface, controlled by size and resolution (quads around the circumference); ideal for Catmull-Clark subdivision.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                SIZE_2, "Diameter. sphere(size=s) has extent s on each axis (vertices at ±s/2). Note: this is diameter, NOT radius — unlike icosphere/uv_sphere which use radius.",
                RESOLUTION_2, "Quads around the circumference (equator). Higher = smoother. Default 16.",
                MESH_2, "All-quad sphere, centered at origin."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number sizeInput = ctx.getInput(SIZE_2, Number.class);
        float radius = (sizeInput == null ? 1.0f : sizeInput.floatValue()) * NUM_0_5;

        Number resInput = ctx.getInput(RESOLUTION_2, Number.class);
        int resolution = resInput == null ? NUM_16 : resInput.intValue();

        // Since 4 cube faces wrap around the sphere's great circle,
        // we divide the resolution by 4 to get the subdivisions per face.
        int divisions = Math.max(1, resolution / NUM_4);

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
                    float u0 = (i / (float) divisions) * NUM_2_0 - 1.0f;
                    float u1 = ((i + 1) / (float) divisions) * NUM_2_0 - 1.0f;
                    float v0 = (j / (float) divisions) * NUM_2_0 - 1.0f;
                    float v1 = ((j + 1) / (float) divisions) * NUM_2_0 - 1.0f;

                    int v00 = addVertex(n, r, u, u0, v0, radius, mesh);
                    int v10 = addVertex(n, r, u, u1, v0, radius, mesh);
                    int v11 = addVertex(n, r, u, u1, v1, radius, mesh);
                    int v01 = addVertex(n, r, u, u0, v1, radius, mesh);

                    mesh.addFace(v00, v10, v11, v01);
                }
            }
        }

        mesh.computeNormals();
        ctx.setOutput(MESH_2, mesh);
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