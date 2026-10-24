package ixdar.nodegraph.nodes.data;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.labels.MeshLabels;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;

/**
 * Names the parts of a surface by points on them: each point selects the ring region holding its
 * nearest face, wherever the rings put that region now.
 */
@MeshNodeAnnotation(id = AddLabelNode.NODE_ID, category = NodeCategory.FIELD_DATA)
public class AddLabelNode implements MeshNode {

    public static final String NODE_ID = "add_label";

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE,
            null);
    public static final InputPort LABEL = new InputPort("label", PortType.STRING, "");
    public static final InputPort POINTS = new InputPort("points", PortType.STRING, "");
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, LABEL, POINTS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Adds a named label selected by surface points: a point picks its nearest face and "
                + "the ring region holding it, resolved again whenever the rings change, so the "
                + "label survives ring edits, additions and reordering. The mesh passes through "
                + "unchanged; repeating a label adds its points to it.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: any surface, usually one carrying ring marks. Output: the same bundle "
                        + "with the label added to the " + MeshLabels.SLOT + " slot.",
                LABEL.name,
                "The label's name: a letter, then letters, digits and underscores, such as "
                        + "claw_left. Empty adds nothing.",
                POINTS.name,
                "Surface points selecting the labelled regions, `x,y,z` separated by ';', one "
                        + "inside each region.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = Objects.requireNonNullElse(
                ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        String label = ctx.getInput(LABEL.name, String.class);
        if (label == null || label.isBlank()) {
            ctx.setOutput(GEOMETRY.name, bundle);
            return;
        }
        MeshLabels labels = new MeshLabels(MeshLabels.inBundle(bundle));
        labels.add(label.strip(),
                SurfaceWaypoints.parse(ctx.getInput(POINTS.name, String.class)));
        ctx.setOutput(GEOMETRY.name, bundle.withSlot(MeshLabels.SLOT, labels));
    }
}
