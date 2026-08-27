package ixdar.geometry.mesh.nodes.geometry;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.InputPort;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.ops.MeshDeleteEdges;
import ixdar.geometry.mesh.data.ops.MeshDeleteVertices;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "delete_geometry")
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
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY.name, Object.class));
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
