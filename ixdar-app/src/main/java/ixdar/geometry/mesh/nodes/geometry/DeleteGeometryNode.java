package ixdar.geometry.mesh.nodes.geometry;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.ops.MeshDeleteEdges;
import ixdar.geometry.mesh.data.ops.MeshDeleteVertices;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "delete_geometry")
public class DeleteGeometryNode implements MeshNode {
    public static final String GEOMETRY_2 = "geometry";
    public static final String SELECTION_2 = "selection";
    public static final String DOMAIN_2 = "domain";
    public static final String POINT = "POINT";

    private static final InputPort GEOMETRY = new InputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort SELECTION = new InputPort(SELECTION_2, PortType.BOOLEAN, false);
    private static final InputPort DOMAIN = new InputPort(DOMAIN_2, PortType.STRING, POINT);
    private static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, SELECTION, DOMAIN);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Removes selected vertices or edges from the mesh based on a boolean selection mask and domain.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                GEOMETRY_2, "Input/output bundle. Elements matching `selection` in the chosen `domain` are deleted along with their incident geometry.",
                SELECTION_2, "BOOLEAN mask. Interpretation depends on `domain`.",
                DOMAIN_2, "Selection domain: POINT (delete vertices + incident edges/faces), EDGE (delete edges + dependent faces), FACE (delete faces)."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY_2, Object.class));
        Object sel = FieldBroadcast.getInputOrDefault(ctx, SELECTION_2, SELECTION.defaultValue());
        String domain = ctx.getInput(DOMAIN_2, String.class);
        if (domain == null) {
            domain = POINT;
        }

        var outMesh = switch (domain) {
            case "EDGE" -> MeshDeleteEdges.delete(base.mesh(), sel);
            default -> MeshDeleteVertices.delete(base.mesh(), sel);
        };
        ctx.setOutput(GEOMETRY_2, base.withMesh(outMesh));
    }
}
