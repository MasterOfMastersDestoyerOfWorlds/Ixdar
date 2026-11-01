package ixdar.nodegraph.nodes.modifier;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.sculpt.StrokeSculpt;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * One stroke-sculpt pass: lifts its SVG through the camera stored on the SVG onto the input mesh,
 * then sculpts with the strokes, so chained passes re-lift onto whatever the earlier ones made.
 */
@MeshNodeAnnotation(id = StrokeSculptNode.NODE_ID, category = NodeCategory.MODIFIERS)
public class StrokeSculptNode implements MeshNode {
    public static final String NO_SYMMETRY = "none";
    public static final String AXES = "xyz";
    public static final String NODE_ID = "stroke_sculpt";
    public static final String DROPPED = "DROPPED";
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort SVG = new InputPort("svg", PortType.STRING, "");
    public static final InputPort STROKES = new InputPort("strokes", PortType.STRING, "");
    public static final InputPort SYMMETRY = new InputPort("symmetry", PortType.STRING, NO_SYMMETRY);
    public static final InputPort DISPLACE = new InputPort("displace", PortType.BOOLEAN, true);
    public static final InputPort CREASE = new InputPort("crease", PortType.FLOAT, 2f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);
    public static final OutputPort REPORT = new OutputPort("report", PortType.STRING);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, SVG, STROKES, SYMMETRY, DISPLACE, CREASE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, REPORT);
    }

    @Override
    public String description() {
        return "One sculpt pass: lifts the strokes drawn on its SVG onto the input mesh through the camera stored on "
                + "the SVG, refines the faces they reach, then displaces, drags or smooths and creases the chains. "
                + "The stroke alone sets what moves, in world units: a ridge, valley or fill moves each vertex it "
                + "sees along the stroke's mean normal by data-depth x w x (1 - (d / data-radius)^2)^2, d the "
                + "vertex's distance across that normal to the stroke (a fill: the union of its samples' bumps), w "
                + "1 except inside a data-profile=\"bowl\" fill, where it is t(2 - t) of the sample's depth in from "
                + "the outline over the deepest's. A silhouette drags the outline it reaches, within data-radius, "
                + "all the way onto the stroke; data-op=\"smooth\" smooths within data-radius. Mirror copies of a "
                + "stroke never add to it; the report output lists every stroke's effect.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.ofEntries(
                Map.entry(GEOMETRY.name, "Input/output. The output carries the strokes' crease marks merged into the float \"crease\" edge marks for subdivision_surface."),
                Map.entry(SVG.name, "The pass's drawing: an SVG written by `ixdar-cli multiview --svg` (its root records the camera) with <polyline>/<polygon> strokes added, each with data-radius (falloff radius, world units) and, unless a silhouette or smooth, data-depth (signed displacement at the stroke, world units, + raises). Optional data-kind silhouette/ridge/valley (ridge and valley only label the sign), data-mirror=\"x\", data-op=\"smooth\", data-profile=\"bowl\" on a polygon; stroke-width is only drawn. Resolved as a file, then under ixdar-app and src/main/resources/, then on the classpath (e.g. dsl/skull/pass1.svg). A value starting with < is the SVG text itself, as the mesh viewer's stroke-sculpt tool sets an unsaved pass's."),
                Map.entry(STROKES.name, "Already-lifted strokes in the text form `stroke-lift` prints, applied with the SVG's: \"d r=R [fill] [mirror] [smooth] [silhouette] [eye=ex/ey/ez/fx/fy/fz]: x y z w, ...; ...\", d and R in world units. A stroke with an eye moves only the surface that eye sees; one without moves everything in reach."),
                Map.entry(SYMMETRY.name, "\"x\", \"y\" or \"z\": every stroke also applies reflected across that axis, and each vertex with a mirror twin averages its displacement with the twin's. \"none\" (default) leaves it alone."),
                Map.entry(DISPLACE.name, "true moves vertices and creases (mode B); false only creases (mode A)."),
                Map.entry(CREASE.name, "Crease weight every ridge or valley chain writes on its edges."),
                Map.entry(REPORT.name, "One line for the pass (vertices in and after refinement), then one per stroke: its kind, cell, radius, depth, points lifted and dropped (grazing, off-surface, out of cell), vertices moved and peak displacement; a stroke that moved nothing is marked " + DROPPED + " with the reason.")
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        StrokeSculpt sculpt = new StrokeSculpt();
        sculpt.displace = !(FieldBroadcast.getInputOrDefault(ctx, DISPLACE.name, DISPLACE.defaultValue) instanceof Boolean b) || b;
        sculpt.creaseScale = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, CREASE.name, CREASE.defaultValue), sculpt.creaseScale);
        String symmetry = ctx.getInputValue(SYMMETRY.name) instanceof String text ? text.trim() : NO_SYMMETRY;
        sculpt.symmetryAxis = symmetry.isEmpty() || NO_SYMMETRY.equals(symmetry) ? -1
                : symmetry.length() == 1 ? AXES.indexOf(symmetry.charAt(0)) : -2;
        if (sculpt.symmetryAxis < -1) {
            throw new IllegalArgumentException("stroke_sculpt symmetry must be x, y, z or none: " + symmetry);
        }
        String strokes = ctx.getInputValue(STROKES.name) instanceof String text ? text : "";
        int textStrokes = strokes.isBlank() ? 0 : strokes.split(StrokeSculpt.STROKE_SEPARATOR).length;
        String svgPath = ctx.getInputValue(SVG.name) instanceof String text ? text.trim() : "";
        StrokeSketch sketch = null;
        if (!svgPath.isEmpty() && base.mesh().faceCount() > 0) {
            sketch = StrokeSketch.load(svgPath, NODE_ID);
            String lifted = sketch.lift(base.mesh());
            strokes = strokes.isBlank() ? lifted : lifted.isEmpty() ? strokes
                    : strokes + StrokeSculpt.STROKE_SEPARATOR + lifted;
        }
        GeometryBundle sculpted = sculpt.apply(base, strokes);
        ctx.setOutput(GEOMETRY.name, sculpted);

        // The report: the pass, then every stroke text by text then shape by shape, as the strokes
        // were joined, each with what it moved.
        StringBuilder report = new StringBuilder(String.format(Locale.ROOT, "%s: %d vertices in, %d after refinement",
                ctx.nodeAssignmentId() == null ? NODE_ID : ctx.nodeAssignmentId(),
                base.mesh() == null ? 0 : base.mesh().vertexCount(), sculpt.refinedVertices));
        int strokeIndex = 0;
        for (; strokeIndex < textStrokes; strokeIndex++) {
            report.append(String.format(Locale.ROOT, "%nstrokes text %d: moved %d vertices, peak displacement %.4f%s",
                    strokeIndex, sculpt.movedVerticesByStroke[strokeIndex],
                    sculpt.peakDisplacementByStroke[strokeIndex],
                    sculpt.movedVerticesByStroke[strokeIndex] == 0 ? " " + DROPPED + ": it reaches no surface it sees" : ""));
        }
        for (int shape = 0; sketch != null && shape < sketch.shapes.size(); shape++) {
            boolean lifted = !sketch.strokeByShape[shape].isEmpty();
            int moved = lifted ? sculpt.movedVerticesByStroke[strokeIndex] : 0;
            float peak = lifted ? sculpt.peakDisplacementByStroke[strokeIndex++] : 0f;
            String dropped = !lifted ? " " + DROPPED + ": no point lifted onto surface the cell sees face-on enough"
                    : moved == 0 ? " " + DROPPED + ": its points lifted but every vertex in reach is hidden from or edge-on to "
                            + "its camera" : "";
            report.append(String.format(Locale.ROOT, "%nshape %d (%s, cell %d): radius %.4f, depth %.4f, lifted %d, "
                    + "dropped %d grazing %d off-surface %d out of cell, moved %d vertices, peak displacement %.4f%s",
                    shape, sketch.kindByShape[shape], sketch.viewByShape[shape], sketch.radiusByShape[shape],
                    sketch.depthByShape[shape], sketch.liftedByShape[shape], sketch.grazingByShape[shape],
                    sketch.missesByShape[shape], sketch.outOfCellByShape[shape], moved, peak, dropped));
            if (!dropped.isEmpty() && !StrokeSketch.SILHOUETTE.equals(sketch.kindByShape[shape])) {
                report.append("; near a cell's outline the surface is seen edge-on, so move the outline itself with a "
                        + "data-kind=\"silhouette\" stroke drawn where it should be");
            }
        }
        ctx.setOutput(REPORT.name, report.toString());
    }
}
