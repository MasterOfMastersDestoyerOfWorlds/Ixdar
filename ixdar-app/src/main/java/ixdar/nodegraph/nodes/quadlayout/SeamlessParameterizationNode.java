package ixdar.nodegraph.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;

/**
 * The {@code seamless_uv} node: builds the seamless parametrization of a cross field with
 * {@link SeamlessParameterization}.
 */
@MeshNodeAnnotation(id = "seamless_uv", desktopOnly = true, category = NodeCategory.QUAD_LAYOUT)
public final class SeamlessParameterizationNode implements MeshNode {

    public static final InputPort FIELD = new InputPort("field", PortType.CROSS_FIELD, null);
    public static final OutputPort UV = new OutputPort("uv", PortType.UV_FIELD);
    public static final OutputPort FLIPPED_TRIANGLES = new OutputPort("flipped_triangles", PortType.INT);
    public static final OutputPort INJECTIVE = new OutputPort("injective", PortType.BOOLEAN);
    public static final OutputPort DOFS = new OutputPort("dofs", PortType.DOF_SYSTEM);
    public static final OutputPort CHARTS = new OutputPort("charts", PortType.CHART_ATLAS);

    @Override
    public List<InputPort> inputs() {
        return List.of(FIELD);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(UV, FLIPPED_TRIANGLES, INJECTIVE, DOFS, CHARTS);
    }

    @Override
    public String description() {
        return "Builds the seamless parametrization over a cross field, reporting whether the"
                + " result is injective and how many UV triangles flipped.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                FIELD.name, "Cross field to parametrize, from a cross_field node.",
                UV.name, "The seamless parametrization with per-corner UVs.",
                FLIPPED_TRIANGLES.name, "Number of UV triangles with negative signed area.",
                INJECTIVE.name, "Whether the parametrization is injective.",
                DOFS.name, "The parametrization solve's DOF system.",
                CHARTS.name, "The per-face charts and the cut transitions between them."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        CrossField inputField = (CrossField) ctx.getInput(FIELD.name, Object.class);
        SeamlessParameterization stage = new SeamlessParameterization();
        SeamlessUv built = stage.build(inputField);
        ctx.setOutput(UV.name, built);
        ctx.setOutput(FLIPPED_TRIANGLES.name, stage.metrics.flippedTriangleCount);
        ctx.setOutput(INJECTIVE.name, built.injective);
        ctx.setOutput(DOFS.name, stage.dofSystem.system);
        ctx.setOutput(CHARTS.name, built.cutGraph.atlas);
    }
}
