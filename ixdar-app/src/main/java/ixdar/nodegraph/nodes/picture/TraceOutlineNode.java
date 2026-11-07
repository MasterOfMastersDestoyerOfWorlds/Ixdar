package ixdar.nodegraph.nodes.picture;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.graphics.image.PictureCurves;
import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.ReferenceOutlines;
import ixdar.graphics.image.SubjectOutline;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * A picture's subject, the largest blob standing out from its border's colour, as one closed curve
 * in the picture plane carrying its box and the view it was taken from.
 */
@MeshNodeAnnotation(id = TraceOutlineNode.NODE_ID, category = NodeCategory.CURVE_OPERATIONS)
public class TraceOutlineNode implements MeshNode {

    public static final String NODE_ID = "trace_outline";

    public static final int DEFAULT_TOLERANCE = 40;

    public static final InputPort IMAGE = new InputPort("image", PortType.IMAGE, null);

    public static final InputPort VIEW = new InputPort("view", PortType.STRING, "");

    public static final InputPort TOLERANCE = new InputPort("tolerance", PortType.INT, DEFAULT_TOLERANCE, 0f, 765f);

    public static final InputPort SIMPLIFY = new InputPort("simplify", PortType.FLOAT, 0f, 0f, 20f);

    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(IMAGE, VIEW, TOLERANCE, SIMPLIFY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "Traces a picture's subject, the largest blob standing out from the median colour of the picture's "
                + "border, as one closed curve in the picture plane: x right and y down in pixels, z = 0. The curve "
                + "carries the view it was taken from and the subject's bounding box as curve attributes, which "
                + "fit_references and shading_edges read.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                IMAGE.name, "The picture, from load_picture.",
                VIEW.name, "The multiview cell the picture shows, by label (Front, Right, Back, Left, Top, Bottom, "
                        + "3/4 Front-R, 3/4 Front-L) or index; stored as the curve attribute "
                        + PictureCurves.VIEW + ", -1 when empty.",
                TOLERANCE.name, "Largest summed red, green and blue difference from the border colour still "
                        + "counted as background.",
                SIMPLIFY.name, "Douglas-Peucker tolerance in pixels; 0 (default) keeps every boundary pixel, as "
                        + "fit_references wants, which simplifies when it writes.",
                GEOMETRY.name, "The outline as curve geometry, closed, with curve attributes " + PictureCurves.VIEW
                        + ", " + PictureCurves.BOX_X + ", " + PictureCurves.BOX_Y + ", " + PictureCurves.BOX_WIDTH
                        + ", " + PictureCurves.BOX_HEIGHT + ", " + PictureCurves.PICTURE_WIDTH + " and "
                        + PictureCurves.PICTURE_HEIGHT + "; empty when nothing stands out.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        PixelImage picture = ctx.getInput(IMAGE.name, PixelImage.class);
        if (picture == null) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }
        String view = ctx.getInputValue(VIEW.name) instanceof String text ? text.trim() : "";
        int cell = view.isEmpty() ? -1
                : ReferenceOutlines.cellNamed(NODE_ID + ReferenceOutlines.PICTURE_SEPARATOR + view,
                        ReferenceOutlines.VIEW_LABELS);
        if (!view.isEmpty() && cell < 0) {
            throw new IllegalArgumentException(NODE_ID + ": no multiview cell is named " + view);
        }
        int tolerance = FieldBroadcast.intScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, TOLERANCE.name, TOLERANCE.defaultValue), DEFAULT_TOLERANCE);
        float simplify = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, SIMPLIFY.name, SIMPLIFY.defaultValue), 0f);
        SubjectOutline subject = SubjectOutline.of(picture, SubjectOutline.borderMedian(picture), tolerance,
                Math.max(0f, simplify));
        if (subject == null) {
            ctx.reportFailure(NODE_ID + ": no subject stands out from the background" + (view.isEmpty() ? ""
                    : " of the " + view + " picture"));
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }
        ctx.setOutput(GEOMETRY.name, GeometryBundle.empty().withSlot(CurveGeometry.SLOT,
                PictureCurves.outline(subject, picture, cell)));
    }
}
