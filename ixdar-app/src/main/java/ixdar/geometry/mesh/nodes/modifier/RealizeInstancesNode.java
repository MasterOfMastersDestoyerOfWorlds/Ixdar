package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;

@MeshNodeAnnotation(id = "realize_instances")
public class RealizeInstancesNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public String description() {
        return "Extracts the concrete mesh from a geometry bundle, converting instanced or bundled geometry into a plain mesh output.";
    }

    @Override
    public void evaluate(NodeContext ctx) {
        MeshTopology m = GeometryBundles.meshPart(ctx.getInput("geometry", Object.class));
        ctx.setOutput("mesh", m);
    }
}
