package ixdar.nodegraph.nodes.modifier;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.sculpt.SculptStroke;
import ixdar.geometry.mesh.data.sculpt.SculptSync;
import ixdar.geometry.mesh.data.sculpt.StrokeAnchors;
import ixdar.geometry.mesh.data.sculpt.StrokeSculpt;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * A sculpt's one stroke pass on its base mesh: every stroke, held on the base by the SVG's sidecar
 * (or lifted from the SVG through its stored camera), summed on the base in one pass.
 */
@MeshNodeAnnotation(id = StrokeSculptNode.NODE_ID, category = NodeCategory.MODIFIERS)
public class StrokeSculptNode implements MeshNode {
    public static final String NO_SYMMETRY = "none";
    public static final String AXES = "xyz";
    public static final String NODE_ID = "stroke_sculpt";
    public static final String DROPPED = "DROPPED";
    public static final String STEEP = "TOO STEEP";
    public static final float STEEPNESS_LIMIT = SculptStroke.FALLOFF_STEEPEST_SLOPE * 3f / 2f;
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
        return "A sculpt's one stroke pass on its base: every stroke is a curve held on the base mesh by the sidecar "
                + "beside the SVG (ixdar-cli sculpt-sync writes both; without one the SVG's strokes are lifted onto the "
                + "base through the camera the SVG stores), the faces they reach are refined once, then every stroke's "
                + "move is summed on the base, distinct strokes adding. It refuses an input that is already a "
                + "stroke_sculpt output: a second pass is a new DSL on the sculpt saved as a new base. "
                + "The stroke alone sets what moves, in world units: a ridge, valley or fill moves each vertex it "
                + "sees along the stroke's mean normal by data-depth x w x (1 - (d / data-radius)^2)^2, d the "
                + "vertex's distance across that normal to the stroke (a fill: the union of its samples' bumps), w "
                + "1 except inside a data-profile=\"bowl\" fill, where it is t(2 - t) of the sample's depth in from "
                + "the outline over the deepest's. A silhouette drags the outline it reaches, within data-radius, "
                + "all the way onto the stroke; data-op=\"smooth\" smooths within data-radius. Mirror copies of a "
                + "stroke never add to it; the report output lists every stroke's effect. A chain's points may lie "
                + "far apart: its falloff and refinement follow the segments between them. Refinement stops at "
                + "edges of 0.005 (or the base's mean edge / 16), and a falloff of slope 1.54 x |data-depth| / "
                + "data-radius stairs on edges more than twice data-radius / (3 x that slope), so keep |data-depth| <= "
                + "data-radius^2 / (2.31 x 0.005): about 0.14 at radius 0.04, 0.55 at 0.08; a steeper stroke "
                + "terraces and the report marks it " + STEEP + ".";
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
        if (base.slots().containsKey(StrokeSculpt.SCULPT_SLOT)) {
            throw new IllegalArgumentException("stroke_sculpt's geometry is already a stroke_sculpt output: one pass "
                    + "sums every stroke on its base, so draw these strokes into that pass's SVG (a <g id> group per "
                    + "layer), or save the sculpt as a new base (ixdar-cli mesh-export) and start a new DSL that loads it");
        }
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
        boolean unsynced = false;
        if (!svgPath.isEmpty() && base.mesh().faceCount() > 0) {
            // With a sidecar the strokes are its curves on the base, whatever the SVG now shows; an
            // SVG sync wrote cannot be read without one, since it draws every stroke in every cell.
            sketch = StrokeSketch.load(svgPath, NODE_ID);
            StrokeAnchors anchors = StrokeAnchors.beside(svgPath);
            if (anchors != null) {
                anchors.checkBase(base.mesh(), StrokeAnchors.sidecarPath(svgPath));
                unsynced = !anchors.written.equals(sketch.text);
                sketch = anchors.sketch(sketch, base.mesh());
            } else if (sketch.shapes.stream().anyMatch(shape -> shape.contains(SculptSync.ID_ATTRIBUTE + "="))) {
                throw new IllegalArgumentException(svgPath + " was written by sculpt-sync, but its stroke sidecar "
                        + StrokeAnchors.sidecarPath(svgPath) + " is missing: restore it from git");
            } else {
                // Named by the ids the first sculpt-sync will give them, so a report reads the same
                // before and after the sidecar exists.
                sketch.idByShape = SculptSync.stableIds(sketch.text, Set.of()).toArray(new String[0]);
            }
            String lifted = sketch.lift(base.mesh());
            strokes = strokes.isBlank() ? lifted : lifted.isEmpty() ? strokes
                    : strokes + StrokeSculpt.STROKE_SEPARATOR + lifted;
        }
        GeometryBundle sculpted = sculpt.apply(base, strokes);
        ctx.setOutput(GEOMETRY.name, sculpted.withSlot(StrokeSculpt.SCULPT_SLOT, sculpt));

        // The report: the pass, then every stroke text by text then shape by shape, as the strokes
        // were joined, each with what it moved.
        StringBuilder report = new StringBuilder(String.format(Locale.ROOT, "%s: %d vertices in, %d after refinement%s",
                ctx.nodeAssignmentId() == null ? NODE_ID : ctx.nodeAssignmentId(),
                base.mesh() == null ? 0 : base.mesh().vertexCount(), sculpt.refinedVertices, unsynced
                        ? "; the SVG was edited since the last sculpt-sync, which these strokes are from: run "
                                + "ixdar-cli sculpt-sync on the DSL to fold the edits in" : ""));
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
            report.append(String.format(Locale.ROOT, "%nstroke %s (%s, cell %d): radius %.4f, depth %.4f, lifted %d, "
                    + "dropped %d grazing %d off-surface %d out of cell, moved %d vertices, peak displacement %.4f%s",
                    sketch.idByShape[shape], sketch.kindByShape[shape], sketch.viewByShape[shape], sketch.radiusByShape[shape],
                    sketch.depthByShape[shape], sketch.liftedByShape[shape], sketch.grazingByShape[shape],
                    sketch.missesByShape[shape], sketch.outOfCellByShape[shape], moved, peak, dropped));
            if (!dropped.isEmpty() && !StrokeSketch.SILHOUETTE.equals(sketch.kindByShape[shape])) {
                report.append("; near a cell's outline the surface is seen edge-on, so move the outline itself with a "
                        + "data-kind=\"silhouette\" stroke drawn where it should be");
            }
            // A falloff of slope 1.54 |depth| / radius needs about three edges per radius / slope;
            // refinement stops at finestEdgeLength, and twice too coarse stairs the slope.
            float radius = sketch.radiusByShape[shape];
            float steepest = radius * radius / (STEEPNESS_LIMIT * sculpt.finestEdgeLength);
            if (moved > 0 && Math.abs(sketch.depthByShape[shape]) > steepest) {
                report.append(String.format(Locale.ROOT, "; %s: depth %.4f at radius %.4f is too steep for edges of "
                        + "%.4f, the finest refinement reaches, so it terraces: keep |data-depth| <= data-radius^2 / "
                        + "(%.2f x %.4f) = %.4f, or widen data-radius", STEEP, sketch.depthByShape[shape], radius,
                        sculpt.finestEdgeLength, STEEPNESS_LIMIT, sculpt.finestEdgeLength, steepest));
            }
        }
        ctx.setOutput(REPORT.name, report.toString());
    }
}
