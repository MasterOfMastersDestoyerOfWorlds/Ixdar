package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.InputPort;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;

@MeshNodeAnnotation(id = "realize_instances")
public class RealizeInstancesNode implements MeshNode {
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final OutputPort MESH = new OutputPort("mesh", PortType.GEOMETRY_BUNDLE);

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
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input bundle (possibly with instances) to flatten into a single concrete mesh.",
                MESH.name, "Concrete mesh with all instances materialized into real vertices and faces."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle in = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        ctx.setOutput(MESH.name, in == null ? null : GeometryBundle.ofMesh(in.mesh()));
    }
}
