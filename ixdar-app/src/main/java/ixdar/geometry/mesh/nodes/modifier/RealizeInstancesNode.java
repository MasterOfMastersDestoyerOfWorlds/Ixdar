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
    public static final String GEOMETRY_2 = "geometry";
    public static final String MESH_2 = "mesh";

    private static final InputPort GEOMETRY = new InputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE, null);
    private static final OutputPort MESH = new OutputPort(MESH_2, PortType.MESH);

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
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                GEOMETRY_2, "Input bundle (possibly with instances) to flatten into a single concrete mesh.",
                MESH_2, "Concrete mesh with all instances materialized into real vertices and faces."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        MeshTopology m = GeometryBundles.meshPart(ctx.getInput(GEOMETRY_2, Object.class));
        ctx.setOutput(MESH_2, m);
    }
}
