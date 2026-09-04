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
import ixdar.geometry.mesh.data.representation.ArrayMesh;

@MeshNodeAnnotation(id = "cube")
public class CubeMeshNode implements MeshNode {
    public static final InputPort SIZE = new InputPort("size", PortType.FLOAT, 1.0f, 0.001f, 100f);
    public static final OutputPort MESH = new OutputPort("mesh", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(SIZE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public String description() {
        return "Generates an axis-aligned cube mesh centered at the origin.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                SIZE.name, "Edge length. cube(size=s) has vertices at ±s/2 and extent = s on each axis."
                        + " To match a reference with bounding-box extent <X,Y,Z> from a unit cube, use"
                        + " transform_geometry(scale=<X,Y,Z>).",
                MESH.name, "Axis-aligned cube mesh (8 verts, 6 quad faces), centered at origin."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number sizeInput = ctx.getInput(SIZE.name, Number.class);
        float c = (sizeInput == null ? 1.0f : sizeInput.floatValue()) * 0.5f;
        float[] positions = {
                -c, -c, -c,  c, -c, -c,  c,  c, -c, -c,  c, -c,
                -c, -c,  c,  c, -c,  c,  c,  c,  c, -c,  c,  c,
        };
        int[] quads = {
                0, 3, 2, 1,   // Back  (-Z)
                4, 5, 6, 7,   // Front (+Z)
                0, 1, 5, 4,   // Bottom (-Y)
                3, 7, 6, 2,   // Top   (+Y)
                1, 2, 6, 5,   // Right (+X)
                0, 4, 7, 3,   // Left  (-X)
        };
        ArrayMesh mesh = ArrayMesh.fromQuads(positions, quads);
        mesh.computeNormals();
        ctx.setOutput(MESH.name, GeometryBundle.ofMesh(mesh));
    }
}
