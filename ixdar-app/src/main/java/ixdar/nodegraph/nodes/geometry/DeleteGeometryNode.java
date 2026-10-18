package ixdar.nodegraph.nodes.geometry;

import java.util.Objects;
import java.util.List;

import ixdar.nodegraph.api.InputPort;

import java.util.Map;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshDeleteEdges;
import ixdar.geometry.mesh.data.ops.MeshDeleteVertices;
import ixdar.nodegraph.field.FieldBroadcast;

@MeshNodeAnnotation(id = "delete_geometry", category = NodeCategory.GEOMETRY_OPERATIONS)
public class DeleteGeometryNode implements MeshNode {
    public static final String POINT = "POINT";

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, false);
    public static final InputPort DOMAIN = new InputPort("domain", PortType.STRING, POINT);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

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
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output bundle. Elements matching `selection` in the chosen `domain` are deleted along with their incident geometry.",
                SELECTION.name, "BOOLEAN mask. Interpretation depends on `domain`.",
                DOMAIN.name, "Selection domain: POINT (delete vertices + incident edges/faces), EDGE (delete edges + dependent faces), FACE (delete faces)."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        Object sel = FieldBroadcast.getInputOrDefault(ctx, SELECTION.name, SELECTION.defaultValue);
        String domain = ctx.getInput(DOMAIN.name, String.class);
        if (domain == null) {
            domain = POINT;
        }

        var outMesh = switch (domain) {
            case "EDGE" -> MeshDeleteEdges.delete(base.mesh(), sel);
            default -> MeshDeleteVertices.delete(base.mesh(), sel);
        };
        ctx.setOutput(GEOMETRY.name, base.withMesh(outMesh));
    }
}
