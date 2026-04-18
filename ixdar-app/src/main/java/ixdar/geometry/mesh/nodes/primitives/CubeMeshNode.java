package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.ArrayMesh;

@MeshNodeAnnotation(id = "cube")
public class CubeMeshNode implements MeshNode {
    private static final InputPort SIZE = new InputPort("size", PortType.FLOAT, 1.0f, 0.001f, 100f);
    private static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

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
                "size", "Edge length. cube(size=s) has vertices at ±s/2 and extent = s on each axis."
                        + " To match a reference with bounding-box extent <X,Y,Z> from a unit cube, use"
                        + " transform_geometry(scale=<X,Y,Z>).",
                "mesh", "Axis-aligned cube mesh (8 verts, 6 quad faces), centered at origin."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number sizeInput = ctx.getInput("size", Number.class);
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
        ctx.setOutput("mesh", mesh);
    }
}
