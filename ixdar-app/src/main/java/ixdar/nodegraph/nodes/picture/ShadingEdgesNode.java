package ixdar.nodegraph.nodes.picture;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.grid2d.PolygonScan;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.graphics.image.PictureCurves;
import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.ShadingEdges;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.Vector3Value;

/**
 * A picture's shading inside its traced subject as curves: every luminance edge an open line, every
 * dark region a closed loop, measured and tagged with the outline's view.
 */
@MeshNodeAnnotation(id = ShadingEdgesNode.NODE_ID, category = NodeCategory.CURVE_OPERATIONS)
public class ShadingEdgesNode implements MeshNode {

    public static final String NODE_ID = "shading_edges";

    public static final InputPort IMAGE = new InputPort("image", PortType.IMAGE, null);

    public static final InputPort OUTLINE = new InputPort("outline", PortType.GEOMETRY_BUNDLE, null);

    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    public static final OutputPort LIGHT = new OutputPort("light", PortType.VECTOR3);

    @Override
    public List<InputPort> inputs() {
        return List.of(IMAGE, OUTLINE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY, LIGHT);
    }

    @Override
    public String description() {
        return "Reads a picture's shading inside its subject: thin luminance edges linked into lines, each signed "
                + "ridge or valley by the light the outline's rim shows, and dark regions (patches and blobs). "
                + "Outputs them as curves in the picture plane for reference_strokes.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                IMAGE.name, "The picture, from load_picture.",
                OUTLINE.name, "The picture's subject from trace_outline; only shading inside it is read, and its "
                        + PictureCurves.VIEW + " attribute tags every curve.",
                GEOMETRY.name, "Edge lines (open) then dark regions (closed), in picture pixels at z = 0. Curve "
                        + "attributes: " + PictureCurves.VIEW + ", " + PictureCurves.REGION + " (1 for a region), "
                        + PictureCurves.LINE_WIDTH + " (a line's transition width, pixels), " + PictureCurves.CONTRAST
                        + " (its step over the lit luminance), " + PictureCurves.RIDGE + " (1 when its bright side "
                        + "faces the light), " + PictureCurves.DARKNESS + ", " + PictureCurves.AREA + " (pixels), "
                        + PictureCurves.BLOB + ", " + PictureCurves.LIGHT_X + ", " + PictureCurves.LIGHT_Y + ", "
                        + PictureCurves.PICTURE_WIDTH + " and " + PictureCurves.PICTURE_HEIGHT + ".",
                LIGHT.name, "The light's direction across the picture, x right and y down, over the lit luminance; "
                        + "zero when it shines along the view.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        PixelImage picture = ctx.getInput(IMAGE.name, PixelImage.class);
        GeometryBundle outlineBundle = ctx.getInput(OUTLINE.name, GeometryBundle.class);
        CurveGeometry outline = outlineBundle == null ? null
                : outlineBundle.slots().get(CurveGeometry.SLOT) instanceof CurveGeometry curve ? curve : null;
        if (picture == null || outline == null || outline.curveCount() == 0) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            ctx.setOutput(LIGHT.name, new Vector3Value(0f, 0f, 0f));
            return;
        }
        ShadingEdges shading = ShadingEdges.of(picture, PolygonScan.covered(outline.drawnPlanar(0), 0f, 0f, 1f,
                picture.width, picture.height));
        ctx.setOutput(GEOMETRY.name, GeometryBundle.empty().withSlot(CurveGeometry.SLOT,
                PictureCurves.shading(shading, (int) PictureCurves.value(outline, PictureCurves.VIEW, 0))));
        ctx.setOutput(LIGHT.name, new Vector3Value(shading.light[0], shading.light[1], 0f));
    }
}
