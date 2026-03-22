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
import ixdar.geometry.mesh.data.MeshDeleteEdges;
import ixdar.geometry.mesh.data.MeshDeleteVertices;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "delete_geometry")
public class DeleteGeometryNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, false);
    private static final InputPort DOMAIN = new InputPort("domain", PortType.STRING, "POINT");
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, SELECTION, DOMAIN);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        Object sel = FieldBroadcast.getInputOrDefault(ctx, "selection", SELECTION.defaultValue());
        String domain = ctx.getInput("domain", String.class);
        if (domain == null) {
            domain = "POINT";
        }

        var outMesh = switch (domain) {
            case "EDGE" -> MeshDeleteEdges.delete(base.mesh(), sel);
            default -> MeshDeleteVertices.delete(base.mesh(), sel);
        };
        ctx.setOutput("geometry", base.withMesh(outMesh));
    }
}
