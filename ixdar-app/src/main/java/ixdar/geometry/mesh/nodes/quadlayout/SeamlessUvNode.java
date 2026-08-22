package ixdar.geometry.mesh.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.ParameterizationMetrics;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Builds the seamless parametrization on top of a cross field, scaled so one
 * parametric unit spans one quad edge.
 *
 * <p>See also: Lyon 2021 stage 3
 */
@MeshNodeAnnotation(id = "seamless_uv", desktopOnly = true)
public class SeamlessUvNode implements MeshNode {

    public static final InputPort FIELD = new InputPort("field", PortType.CROSS_FIELD, null);
    public static final OutputPort UV = new OutputPort("uv", PortType.UV_FIELD);
    public static final OutputPort FLIPPED_TRIANGLES = new OutputPort("flipped_triangles", PortType.INT);
    public static final OutputPort INJECTIVE = new OutputPort("injective", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(FIELD);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(UV, FLIPPED_TRIANGLES, INJECTIVE);
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
                INJECTIVE.name, "Whether the parametrization is injective."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        CrossField field = (CrossField) ctx.getInput(FIELD.name, Object.class);
        SeamlessParameterization seamless = new SeamlessParameterization(field);
        ParameterizationMetrics metrics = seamless.build();
        ctx.setOutput(UV.name, seamless);
        ctx.setOutput(FLIPPED_TRIANGLES.name, metrics.flippedTriangleCount);
        ctx.setOutput(INJECTIVE.name, seamless.injective);
    }
}
