package ixdar.nodegraph.nodes.modifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshLargestPart;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;

/** Keeps a mesh's edge-connected part with the most faces. */
@MeshNodeAnnotation(id = "keep_largest_part", category = NodeCategory.MODIFIERS)
public class KeepLargestPartNode implements MeshNode {
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Keeps the edge-connected part of a mesh with the most faces, dropping the specks and bubbles an "
                + "extraction or a weld leaves beside it.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(GEOMETRY.name, "Input/output. Vertex and face order of the kept part follow the input's; "
                + "slots are dropped, their indices no longer match.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class),
                GeometryBundle.empty());
        ctx.setOutput(GEOMETRY_OUT.name, base.mesh() == null || base.mesh().faceCount() == 0 ? base
                : GeometryBundle.ofMesh(MeshLargestPart.keep(base.mesh())));
    }
}
