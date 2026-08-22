package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.representation.ArrayMesh;

@MeshNodeAnnotation(id = "cube")
public class CubeMeshNode implements MeshNode {
    public static final float NUM_0_5 = 0.5f;
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final int NUM_5 = 5;
    public static final int NUM_6 = 6;
    public static final int NUM_7 = 7;
    public static final InputPort SIZE = new InputPort("size", PortType.FLOAT, 1.0f, 0.001f, 100f);
    public static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

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
        float c = (sizeInput == null ? 1.0f : sizeInput.floatValue()) * NUM_0_5;
        float[] positions = {
                -c, -c, -c,  c, -c, -c,  c,  c, -c, -c,  c, -c,
                -c, -c,  c,  c, -c,  c,  c,  c,  c, -c,  c,  c,
        };
        int[] quads = {
                0, NUM_3, 2, 1,   // Back  (-Z)
                NUM_4, NUM_5, NUM_6, NUM_7,   // Front (+Z)
                0, 1, NUM_5, NUM_4,   // Bottom (-Y)
                NUM_3, NUM_7, NUM_6, 2,   // Top   (+Y)
                1, 2, NUM_6, NUM_5,   // Right (+X)
                0, NUM_4, NUM_7, NUM_3,   // Left  (-X)
        };
        ArrayMesh mesh = ArrayMesh.fromQuads(positions, quads);
        mesh.computeNormals();
        ctx.setOutput(MESH.name, mesh);
    }
}
