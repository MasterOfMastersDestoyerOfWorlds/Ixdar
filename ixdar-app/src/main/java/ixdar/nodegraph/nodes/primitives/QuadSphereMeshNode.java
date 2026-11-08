package ixdar.nodegraph.nodes.primitives;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

@MeshNodeAnnotation(id = "sphere", category = NodeCategory.PRIMITIVES)
public class QuadSphereMeshNode implements MeshNode {
    public static final InputPort SIZE = new InputPort("size", PortType.FLOAT, 1.0f, 0.001f, 100f);
    // Defines how many quads make up the circumference of the sphere
    public static final InputPort RESOLUTION = new InputPort("resolution", PortType.INT, 16, (float) 3, (float) 128);
    public static final OutputPort MESH = new OutputPort("mesh", PortType.GEOMETRY_BUNDLE);

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
                SIZE.name, "Diameter. sphere(size=s) has extent s on each axis (vertices at ±s/2). Note: this is diameter, NOT radius — unlike icosphere/uv_sphere which use radius.",
                RESOLUTION.name, "Quads around the circumference (equator). Higher = smoother. Default 16.",
                MESH.name, "All-quad closed sphere, one welded surface, centered at origin."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number sizeInput = ctx.getInput(SIZE.name, Number.class);
        float radius = (sizeInput == null ? 1.0f : sizeInput.floatValue()) * 0.5f;

        Number resInput = ctx.getInput(RESOLUTION.name, Number.class);
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
        Map<Long, Integer> vertexAtLatticePoint = new HashMap<>();

        for (float[][] face : faces) {
            for (int i = 0; i < divisions; i++) {
                for (int j = 0; j < divisions; j++) {
                    int v00 = vertexAt(face, divisions, i, j, radius, mesh, vertexAtLatticePoint);
                    int v10 = vertexAt(face, divisions, i + 1, j, radius, mesh, vertexAtLatticePoint);
                    int v11 = vertexAt(face, divisions, i + 1, j + 1, radius, mesh, vertexAtLatticePoint);
                    int v01 = vertexAt(face, divisions, i, j + 1, radius, mesh, vertexAtLatticePoint);

                    mesh.addFace(v00, v10, v11, v01);
                }
            }
        }

        mesh.computeNormals();
        ctx.setOutput(MESH.name, GeometryBundle.ofMesh(mesh));
    }

    /**
     * The sphere vertex over one grid corner of a cube face, added the first time any face reaches
     * that corner.
     *
     * @param face      the face's normal, right and up axes
     * @param divisions grid cells along a face edge
     * @param column    corner column, 0 to divisions
     * @param row       corner row, 0 to divisions
     * @param radius    sphere radius
     * @param mesh      mesh being built
     * @param vertexAt  vertex id by packed lattice point, shared by all six faces
     * @return the corner's vertex id
     */
    private static int vertexAt(float[][] face, int divisions, int column, int row, float radius,
            HalfEdgeMesh mesh, Map<Long, Integer> vertexAt) {
        int[] lattice = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            lattice[axis] = Math.round(face[0][axis] * divisions + face[1][axis] * (2 * column - divisions)
                    + face[2][axis] * (2 * row - divisions));
        }
        int span = 2 * divisions + 1;
        long key = ((long) (lattice[0] + divisions) * span + lattice[1] + divisions) * span + lattice[2]
                + divisions;
        return vertexAt.computeIfAbsent(key, unused -> {
            // Spherize the cube point by normalizing it and scaling by the radius.
            float x = lattice[0];
            float y = lattice[1];
            float z = lattice[2];
            float scale = radius / (float) Math.sqrt(x * x + y * y + z * z);
            return mesh.addVertex(x * scale, y * scale, z * scale);
        });
    }
}