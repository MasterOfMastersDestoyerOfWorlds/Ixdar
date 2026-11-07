package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.volume.VoxelGrid;
import ixdar.graphics.image.PictureCurves;
import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.ReferenceOutlines;
import ixdar.graphics.image.ShadingEdges;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.curve.MergeCurvesNode;
import ixdar.nodegraph.nodes.patch.SilhouetteHullNode;
import ixdar.nodegraph.nodes.picture.CurvesToCellsNode;
import ixdar.nodegraph.nodes.picture.FitReferencesNode;
import ixdar.nodegraph.nodes.picture.PlaceInCellNode;
import ixdar.nodegraph.nodes.picture.SvgToCurvesNode;
import ixdar.nodegraph.nodes.picture.TraceOutlineNode;

/**
 * The picture nodes on three silhouettes of an ellipsoid half as wide as tall: tracing, merging,
 * the shared-scale fit, the hull from fitted curves against the hull from the fitted SVG, and the
 * round trips between world curves and template cells.
 */
class PictureNodesTest {

    private static final String FRONT = "Front";

    private static final int PICTURE_SIZE = 240;

    private static final float PIXELS_PER_UNIT = 80f;

    private static final float HALF_WIDTH = 0.6f;

    private static final float HALF_HEIGHT = 1f;

    private static final float HALF_DEPTH = 0.8f;

    private static final float HEIGHT = 2f;

    private static final int CELL_SIZE = 256;

    private static final int HULL_RESOLUTION = 24;

    private static final float SHARE = 0.03f;

    private static final Pattern POINTS = Pattern.compile("points=\"([^\"]*)\"");

    @Test
    void traceOutlineTagsItsViewAndBox() {
        CurveGeometry outline = curves(trace(ellipse(HALF_WIDTH, HALF_HEIGHT), FRONT));

        assertTrue(outline.closed[0], "an outline is a loop");
        assertEquals(0f, PictureCurves.value(outline, PictureCurves.VIEW, 0), "Front is cell 0");
        assertEquals(2 * HALF_HEIGHT * PIXELS_PER_UNIT + 1, PictureCurves.value(outline, PictureCurves.BOX_HEIGHT, 0),
                2f, "the box spans the ellipse");
        assertEquals(0f, outline.positions[2], "the outline lies in z = 0");
    }

    @Test
    void mergeCurvesKeepsEveryCurveAndZeroFillsMissingAttributes() {
        GeometryBundle front = trace(ellipse(HALF_WIDTH, HALF_HEIGHT), FRONT);
        GeometryBundle loose = GeometryBundle.empty().withSlot(CurveGeometry.SLOT,
                CurveGeometry.planar(new float[] { 0f, 0f, 1f, 1f }, false));

        CurveGeometry merged = curves(new MapNodeContext(new MergeCurvesNode())
                .with(MergeCurvesNode.CURVE_INPUTS.get(0), front)
                .with(MergeCurvesNode.CURVE_INPUTS.get(2), loose)
                .eval()
                .output(MergeCurvesNode.GEOMETRY, GeometryBundle.class));

        assertEquals(2, merged.curveCount());
        assertTrue(merged.closed[0] && !merged.closed[1]);
        assertEquals(curves(front).pointCount() + 2, merged.pointCount());
        assertEquals(0f, PictureCurves.value(merged, PictureCurves.BOX_HEIGHT, 1), "the loose line has no box");
    }

    @Test
    void fitReferencesRecoversTheEllipsoidsExtents() {
        MapNodeContext fit = fit();
        ReferenceOutlines traced = (ReferenceOutlines) fit.output(FitReferencesNode.GEOMETRY, GeometryBundle.class)
                .slots().get(ReferenceOutlines.SLOT);
        String svg = fit.output(FitReferencesNode.SVG, String.class);

        assertEquals(2 * HALF_WIDTH, traced.extents[0], 2 * HALF_WIDTH * SHARE, "width from front and top");
        assertEquals(HEIGHT, traced.extents[1], HEIGHT * SHARE, "height as given");
        assertEquals(2 * HALF_DEPTH, traced.extents[2], 2 * HALF_DEPTH * SHARE, "depth from side and top");
        assertEquals(3, svg.split("data-kind=\"silhouette\"").length - 1, "one silhouette per picture");
    }

    @Test
    void hullFromFittedCurvesMatchesTheHullFromTheFittedSvg() {
        MapNodeContext fit = fit();
        VoxelGrid fromCurves = new MapNodeContext(new SilhouetteHullNode())
                .with(SilhouetteHullNode.OUTLINES, fit.output(FitReferencesNode.GEOMETRY, GeometryBundle.class))
                .with(SilhouetteHullNode.RESOLUTION, HULL_RESOLUTION)
                .eval()
                .output(SilhouetteHullNode.VOLUME_OUT, VoxelGrid.class);
        VoxelGrid fromSvg = new MapNodeContext(new SilhouetteHullNode())
                .with(SilhouetteHullNode.SVG, fit.output(FitReferencesNode.SVG, String.class))
                .with(SilhouetteHullNode.RESOLUTION, HULL_RESOLUTION)
                .eval()
                .output(SilhouetteHullNode.VOLUME_OUT, VoxelGrid.class);

        int inside = solid(fromSvg);
        assertTrue(inside > 0, "the hull has a solid");
        assertEquals(inside, solid(fromCurves), inside * SHARE, "outline curves carve what their SVG carves");
    }

    @Test
    void svgToCurvesAndCurvesToCellsRoundTripTheFittedOutlines() {
        MapNodeContext fit = fit();
        String svg = fit.output(FitReferencesNode.SVG, String.class);
        GeometryBundle placed = fit.output(FitReferencesNode.GEOMETRY, GeometryBundle.class);

        CurveGeometry read = curves(new MapNodeContext(new SvgToCurvesNode())
                .with(SvgToCurvesNode.SVG, svg)
                .eval()
                .output(SvgToCurvesNode.GEOMETRY, GeometryBundle.class));
        String redrawn = new MapNodeContext(new CurvesToCellsNode())
                .with(CurvesToCellsNode.CURVES, placed)
                .eval()
                .output(CurvesToCellsNode.SVG, String.class);

        CurveGeometry fitted = curves(placed);
        assertEquals(fitted.pointCount(), read.pointCount(), "every outline point read back");
        float worldPerPixel = HEIGHT / (FitReferencesNode.DEFAULT_FILL * CELL_SIZE);
        for (int coordinate = 0; coordinate < fitted.positions.length; coordinate++) {
            assertEquals(fitted.positions[coordinate], read.positions[coordinate], worldPerPixel,
                    "the SVG's tenth-pixel rounding is all that separates them");
        }
        List<Float> written = numbers(svg);
        List<Float> drawn = numbers(redrawn.substring(redrawn.indexOf("fill=\"none\" stroke=\"blue\"")));
        assertEquals(written.size(), drawn.size());
        for (int index = 0; index < written.size(); index++) {
            assertEquals(written.get(index), drawn.get(index), 0.11f, "drawn back into the same cell pixels");
        }
    }

    @Test
    void placeInCellScalesTheSubjectToTheWorldHeight() {
        String template = fit().output(FitReferencesNode.SVG, String.class);
        CurveGeometry placed = curves(new MapNodeContext(new PlaceInCellNode())
                .with(PlaceInCellNode.CURVES, trace(ellipse(HALF_WIDTH, HALF_HEIGHT), FRONT))
                .with(PlaceInCellNode.TEMPLATE, template)
                .with(PlaceInCellNode.CELL, FRONT)
                .with(PlaceInCellNode.HEIGHT, HEIGHT)
                .eval()
                .output(PlaceInCellNode.GEOMETRY, GeometryBundle.class));

        float low = Float.MAX_VALUE;
        float high = -Float.MAX_VALUE;
        for (int point = 0; point < placed.pointCount(); point++) {
            low = Math.min(low, placed.positions[point * CurveGeometry.XYZ + 1]);
            high = Math.max(high, placed.positions[point * CurveGeometry.XYZ + 1]);
        }
        assertEquals(HEIGHT, high - low, HEIGHT * SHARE, "the box's height is the world height");
        assertEquals(0f, (high + low) / 2f, HEIGHT * SHARE, "centred on the orbit centre");
    }

    @Test
    void shadingCurvesRoundTripEveryMeasurement() {
        ShadingEdges shading = new ShadingEdges();
        shading.lines.add(new float[] { 1f, 2f, 3f, 4f, 5f, 7f });
        shading.widthByLine = new float[] { 2.5f };
        shading.contrastByLine = new float[] { -0.3f };
        shading.ridgeByLine = new boolean[] { true };
        shading.regions.add(new float[] { 0f, 0f, 4f, 0f, 4f, 4f, 0f, 0f });
        shading.darknessByRegion = new float[] { 0.4f };
        shading.areaByRegion = new int[] { 8 };
        shading.blobByRegion = new boolean[] { true };
        shading.width = PICTURE_SIZE;
        shading.height = PICTURE_SIZE;

        ShadingEdges back = PictureCurves.shadingOf(PictureCurves.shading(shading, 1), 1);

        assertEquals(1, back.lines.size());
        assertArrayEquals(shading.lines.get(0), back.lines.get(0));
        assertArrayEquals(shading.regions.get(0), back.regions.get(0), "drawn closed again");
        assertEquals(-0.3f, back.contrastByLine[0]);
        assertTrue(back.ridgeByLine[0] && back.blobByRegion[0]);
        assertEquals(8, back.areaByRegion[0]);
        assertEquals(PICTURE_SIZE, back.width);
    }

    private static MapNodeContext fit() {
        GeometryBundle front = trace(ellipse(HALF_WIDTH, HALF_HEIGHT), FRONT);
        GeometryBundle right = trace(ellipse(HALF_DEPTH, HALF_HEIGHT), "Right");
        GeometryBundle top = trace(ellipse(HALF_WIDTH, HALF_DEPTH), "Top");
        GeometryBundle outlines = new MapNodeContext(new MergeCurvesNode())
                .with(MergeCurvesNode.CURVE_INPUTS.get(0), front)
                .with(MergeCurvesNode.CURVE_INPUTS.get(1), right)
                .with(MergeCurvesNode.CURVE_INPUTS.get(2), top)
                .eval()
                .output(MergeCurvesNode.GEOMETRY, GeometryBundle.class);
        return new MapNodeContext(new FitReferencesNode())
                .with(FitReferencesNode.OUTLINES, outlines)
                .with(FitReferencesNode.HEIGHT, HEIGHT)
                .with(FitReferencesNode.CELL_SIZE, CELL_SIZE)
                .eval();
    }

    private static PixelImage ellipse(float halfAcross, float halfUp) {
        PixelImage picture = new PixelImage(PICTURE_SIZE, PICTURE_SIZE);
        for (int pixel = 0; pixel < picture.pixels.length; pixel++) {
            float u = (pixel % PICTURE_SIZE - PICTURE_SIZE / 2f) / (halfAcross * PIXELS_PER_UNIT);
            float v = (pixel / PICTURE_SIZE - PICTURE_SIZE / 2f) / (halfUp * PIXELS_PER_UNIT);
            picture.pixels[pixel] = u * u + v * v <= 1f ? PixelImage.OPAQUE : PixelImage.OPAQUE | PixelImage.RGB_MASK;
        }
        return picture;
    }

    private static GeometryBundle trace(PixelImage picture, String view) {
        return new MapNodeContext(new TraceOutlineNode())
                .with(TraceOutlineNode.IMAGE, picture)
                .with(TraceOutlineNode.VIEW, view)
                .eval()
                .output(TraceOutlineNode.GEOMETRY, GeometryBundle.class);
    }

    private static CurveGeometry curves(GeometryBundle bundle) {
        return (CurveGeometry) bundle.slots().get(CurveGeometry.SLOT);
    }

    private static int solid(VoxelGrid grid) {
        int inside = 0;
        for (float value : grid.values) {
            inside += value > 0f ? 1 : 0;
        }
        return inside;
    }

    private static List<Float> numbers(String svg) {
        List<Float> numbers = new ArrayList<>();
        Matcher points = POINTS.matcher(svg);
        while (points.find()) {
            for (String pair : points.group(1).split(" ")) {
                for (String number : pair.split(",")) {
                    numbers.add(Float.parseFloat(number));
                }
            }
        }
        return numbers;
    }
}
