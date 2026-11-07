package ixdar.nodegraph.nodes.picture;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.sculpt.ReferenceStrokes;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.graphics.image.PictureCurves;
import ixdar.graphics.image.ReferenceOutlines;
import ixdar.graphics.image.ShadingEdges;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;

/**
 * Reference pictures' shading as the stroke SVG a sculpt starts from: every edge a ridge or valley
 * chain and every hollow a bowl fill, cast through the pictures' fit onto the base and drawn
 * through a template's cameras, ready for stroke_sculpt.
 */
@MeshNodeAnnotation(id = ReferenceStrokesNode.NODE_ID, category = NodeCategory.CURVE_OPERATIONS)
public class ReferenceStrokesNode implements MeshNode {

    public static final String NODE_ID = "reference_strokes";

    public static final InputPort FIT = new InputPort("fit", PortType.GEOMETRY_BUNDLE, null);

    public static final InputPort EDGES = new InputPort("edges", PortType.GEOMETRY_BUNDLE, null);

    public static final InputPort BASE = new InputPort("base", PortType.GEOMETRY_BUNDLE, null);

    public static final InputPort TEMPLATE = new InputPort("template", PortType.STRING, "");

    public static final OutputPort SVG = new OutputPort("svg", PortType.STRING);

    public static final OutputPort REPORT = new OutputPort("report", PortType.STRING);

    @Override
    public List<InputPort> inputs() {
        return List.of(FIT, EDGES, BASE, TEMPLATE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(SVG, REPORT);
    }

    @Override
    public String description() {
        return "Turns reference pictures' shading into the strokes a sculpt starts from: every shading edge of "
                + "enough contrast becomes a ridge or valley chain (radius from its width, depth from its contrast, "
                + "under the terrace limit) and every hollow a bowl fill about as deep as its mouth is wide when "
                + "round and dark. Each is cast along its picture's view, through the fit, onto the base where the "
                + "base faces it within 60 degrees, axis pictures first and strongest lines first, a point dropped "
                + "where an earlier mark lies, and drawn through the template's camera in a <g id=\"edges_<view>\"> "
                + "group per picture.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                FIT.name, "fit_references' geometry: the pictures' shared-scale fit and its template.",
                EDGES.name, "Every picture's shading_edges, merged with merge_curves; each curve's view picks its "
                        + "picture.",
                BASE.name, "The base mesh the strokes land on, in the fit's world (the hull built from its outlines).",
                TEMPLATE.name, "SVG whose cameras the strokes are drawn through, text or path, such as a multiview "
                        + "--svg capture of the base; empty uses the fit's orthographic template.",
                SVG.name, "The template with the strokes added, for stroke_sculpt's svg input or a file to edit "
                        + "and sculpt-sync.",
                REPORT.name, "One line per picture: its view, the template cell drawn in, ridges, valleys and fills "
                        + "written, and the edges and dark regions found.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle fit = ctx.getInput(FIT.name, GeometryBundle.class);
        GeometryBundle edges = ctx.getInput(EDGES.name, GeometryBundle.class);
        GeometryBundle base = ctx.getInput(BASE.name, GeometryBundle.class);
        if (fit == null || !(fit.slots().get(ReferenceOutlines.SLOT) instanceof ReferenceOutlines traced)) {
            throw new IllegalArgumentException(NODE_ID + ": fit must be fit_references' geometry");
        }
        if (base == null || base.mesh() == null || base.mesh().faceCount() == 0) {
            ctx.setOutput(SVG.name, "");
            ctx.setOutput(REPORT.name, NODE_ID + ": no base mesh");
            return;
        }
        CurveGeometry shadingCurves = edges != null && edges.slots().get(CurveGeometry.SLOT) instanceof CurveGeometry
                curves ? curves : null;
        String template = ctx.getInputValue(TEMPLATE.name) instanceof String text ? text.trim() : "";
        StrokeSketch sketch = template.isEmpty() ? StrokeSketch.parse((String) fit.slots().get(StrokeSketch.TEMPLATE_SLOT),
                NODE_ID) : StrokeSketch.load(template, NODE_ID);
        String[] names = new String[traced.views];
        ShadingEdges[] shading = new ShadingEdges[traced.views];
        for (int view = 0; view < traced.views; view++) {
            int cell = traced.cellByView[view];
            names[view] = ReferenceOutlines.viewName(ReferenceOutlines.VIEW_LABELS[cell]);
            shading[view] = shadingCurves == null ? new ShadingEdges() : PictureCurves.shadingOf(shadingCurves, cell);
        }
        ReferenceStrokes strokes = ReferenceStrokes.of(traced, shading, names, base.mesh(), sketch);
        StringBuilder report = new StringBuilder();
        for (int view = 0; view < traced.views; view++) {
            report.append(String.format(Locale.ROOT, "%s%s: cell %d, %d ridges, %d valleys, %d fills from %d edges "
                    + "and %d dark regions", view == 0 ? "" : "\n", ReferenceOutlines.VIEW_LABELS[traced.cellByView[view]],
                    strokes.templateCellByView[view], strokes.ridgesByView[view], strokes.valleysByView[view],
                    strokes.fillsByView[view], shading[view].lines.size(), shading[view].regions.size()));
        }
        ctx.setOutput(SVG.name, strokes.svg);
        ctx.setOutput(REPORT.name, report.toString());
    }
}
