package ixdar.nodegraph.nodes.modifier;

import java.io.IOException;
import java.util.List;
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
    public static final String SVG_TEXT_START = "<";
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort SVG = new InputPort("svg", PortType.STRING, "");
    public static final InputPort STROKES = new InputPort("strokes", PortType.STRING, "");
    public static final InputPort STRENGTH = new InputPort("strength", PortType.FLOAT, 1f);
    public static final InputPort SYMMETRY = new InputPort("symmetry", PortType.STRING, NO_SYMMETRY);
    public static final InputPort DISPLACE = new InputPort("displace", PortType.BOOLEAN, true);
    public static final InputPort SCALE = new InputPort("scale", PortType.FLOAT, 0.2f);
    public static final InputPort FALLOFF = new InputPort("falloff", PortType.FLOAT, 0.4f);
    public static final InputPort CREASE = new InputPort("crease", PortType.FLOAT, 2f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, SVG, STROKES, STRENGTH, SYMMETRY, DISPLACE, SCALE, FALLOFF, CREASE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "One sculpt pass: lifts the strokes drawn on its SVG onto the input mesh through the camera stored on the SVG, refines the faces they reach, then displaces, drags or smooths with a falloff and creases the chains.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.ofEntries(
                Map.entry(GEOMETRY.name, "Input/output. The output carries the strokes' crease marks merged into the float \"crease\" edge marks for subdivision_surface."),
                Map.entry(SVG.name, "The pass's drawing: an SVG written by `ixdar-cli multiview --svg` (its root records the camera) with <polyline>/<polygon> strokes added. data-strength in [-1, 1], stroke-width the falloff width in pixels, data-kind silhouette/ridge/valley, data-mirror=\"x\", data-op=\"smooth\". Resolved as a file, then under src/main/resources/, then on the classpath (e.g. dsl/skull/pass1.svg). A value starting with < is the SVG text itself, as the mesh viewer's stroke-sculpt tool sets an unsaved pass's."),
                Map.entry(STROKES.name, "Already-lifted strokes in the text form `stroke-lift` prints, applied with the SVG's: \"s r=R [fill] [mirror] [smooth]: x y z w, ...; ...\"."),
                Map.entry(STRENGTH.name, "Multiplier on the whole pass (displacement, drag, smoothing, crease), the knob dsl_optimizer tunes."),
                Map.entry(SYMMETRY.name, "\"x\", \"y\" or \"z\": every stroke also applies reflected across that axis and the pass's displacement is averaged with its mirror; needs a symmetric input. \"none\" (default) leaves it alone."),
                Map.entry(DISPLACE.name, "true moves vertices and creases (mode B); false only creases (mode A)."),
                Map.entry(SCALE.name, "Distance along the normal a strength-1 stroke moves its chain."),
                Map.entry(FALLOFF.name, "Falloff radius, as an edge-path distance, of strokes whose header names none."),
                Map.entry(CREASE.name, "Crease weight a strength-1 stroke writes on its chain's edges.")
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        StrokeSculpt sculpt = new StrokeSculpt();
        sculpt.displace = !(FieldBroadcast.getInputOrDefault(ctx, DISPLACE.name, DISPLACE.defaultValue) instanceof Boolean b) || b;
        sculpt.displacementScale = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, SCALE.name, SCALE.defaultValue), sculpt.displacementScale);
        sculpt.falloffRadius = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, FALLOFF.name, FALLOFF.defaultValue), sculpt.falloffRadius);
        sculpt.creaseScale = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, CREASE.name, CREASE.defaultValue), sculpt.creaseScale);
        sculpt.strength = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, STRENGTH.name, STRENGTH.defaultValue), sculpt.strength);
        String symmetry = ctx.getInputValue(SYMMETRY.name) instanceof String text ? text.trim() : NO_SYMMETRY;
        sculpt.symmetryAxis = symmetry.isEmpty() || NO_SYMMETRY.equals(symmetry) ? -1
                : symmetry.length() == 1 ? AXES.indexOf(symmetry.charAt(0)) : -2;
        if (sculpt.symmetryAxis < -1) {
            throw new IllegalArgumentException("stroke_sculpt symmetry must be x, y, z or none: " + symmetry);
        }
        String strokes = ctx.getInputValue(STROKES.name) instanceof String text ? text : "";
        String svgPath = ctx.getInputValue(SVG.name) instanceof String text ? text.trim() : "";
        if (!svgPath.isEmpty() && base.mesh().faceCount() > 0) {
            try {
                StrokeSketch sketch = svgPath.startsWith(SVG_TEXT_START) ? StrokeSketch.parse(svgPath, SVG.name)
                        : StrokeSketch.read(svgPath);
                String lifted = sketch.lift(base.mesh());
                strokes = strokes.isBlank() ? lifted : lifted.isEmpty() ? strokes
                        : strokes + StrokeSculpt.STROKE_SEPARATOR + lifted;
            } catch (IOException failure) {
                throw new IllegalStateException("stroke_sculpt cannot read " + svgPath + ": "
                        + failure.getMessage(), failure);
            }
        }
        ctx.setOutput(GEOMETRY.name, sculpt.apply(base, strokes));
    }
}
