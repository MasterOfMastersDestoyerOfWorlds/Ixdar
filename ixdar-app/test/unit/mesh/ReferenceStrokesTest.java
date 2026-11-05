package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import ixdar.automation.endpoints.ui.MultiviewScreenshot;
import ixdar.geometry.grid2d.PolygonScan;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.sculpt.ReferenceStrokes;
import ixdar.geometry.mesh.data.sculpt.SculptStroke;
import ixdar.geometry.mesh.data.sculpt.StrokeRefinement;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.ReferenceOutlines;
import ixdar.graphics.image.ShadingEdges;
import ixdar.graphics.image.SubjectOutline;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.modifier.MergeByDistanceNode;
import ixdar.nodegraph.nodes.primitives.QuadSphereMeshNode;

/**
 * reference-strokes' conversion (MESH-89) on hand-made pictures of a lit ball carrying a ridge, a
 * groove and a hole: one thin line per shading edge, signed by the lit side, a fill for the hole,
 * and strokes placed on a ball base under the terrace limit.
 */
class ReferenceStrokesTest {

    private static final int SIZE = 240;

    private static final float CENTRE = 120f;

    private static final float BALL = 90f;

    private static final float RIDGE_X = 95f;

    private static final float GROOVE_X = 150f;

    private static final float HOLE_Y = 85f;

    private static final float HOLE_X = 120f;

    private static final float HOLE_RADIUS = 9f;

    private static final float HOLLOW_RADIUS = 24f;

    private static final float HOLLOW_DEPTH = 0.6f;

    private static final float FEATURE_WIDTH = 5f;

    private static final float FEATURE_HEIGHT = 9f;

    private static final float[] LIGHT = { -0.55f, -0.35f, 0.76f };

    private static final float AMBIENT = 0.15f;

    private static final int LIT = 230;

    private static final int BACKGROUND = 0xFFF4EEE2;

    private static final float NEAR = 6f;

    private static final int TOLERANCE = 40;

    private static final int BALL_LINES_OFF = 100;

    private static final String ROOT = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"5120\" height=\"2560\" "
            + "viewBox=\"0 0 5120 2560\" data-center=\"0 0 0\" data-distance=\"3.906623\" data-fov=\"45\" "
            + "data-projection=\"perspective\" data-cell-width=\"1280\" data-cell-height=\"1280\" data-columns=\"4\" "
            + "data-views=\"1.570796 0;0 0;-1.570796 0;3.141593 0;1.570796 1.570796;1.570796 -1.570796;0.785398 0.4;"
            + "2.356194 0.4\">\n</svg>\n";

    private static final Pattern SHAPE = Pattern.compile(
            "<(polyline|polygon) (?:data-kind=\"(\\w+)\" )?data-depth=\"([-0-9.]+)\" data-radius=\"([0-9.]+)\"");

    @Test
    void eachShadingEdgeIsOneThinLineSignedByItsLitSide() {
        PixelImage picture = ball(true);
        ShadingEdges shading = ShadingEdges.of(picture, inside(picture));
        assertTrue(shading.light[0] < 0f && shading.light[1] < 0f, "the rim shows the light up and to the left: "
                + shading.light[0] + "," + shading.light[1]);
        int ridges = 0;
        int valleys = 0;
        for (int line = 0; line < shading.lines.size(); line++) {
            float[] xy = shading.lines.get(line);
            float meanX = 0f;
            float lowest = Float.MAX_VALUE;
            float highest = -Float.MAX_VALUE;
            for (int point = 0; point < xy.length; point += 2) {
                meanX += xy[point] / (xy.length / 2);
                lowest = Math.min(lowest, xy[point + 1]);
                highest = Math.max(highest, xy[point + 1]);
            }
            // A long vertical line on the ridge's crest or the groove's floor.
            if (highest - lowest > BALL && Math.abs(meanX - RIDGE_X) < NEAR) {
                ridges += shading.ridgeByLine[line] ? 1 : -BALL_LINES_OFF;
            } else if (highest - lowest > BALL && Math.abs(meanX - GROOVE_X) < NEAR) {
                valleys += shading.ridgeByLine[line] ? -BALL_LINES_OFF : 1;
            }
            float span = (float) Math.hypot(xy[0] - xy[xy.length - 2], xy[1] - xy[xy.length - 1]);
            assertTrue(highest - lowest < BALL || span > NEAR, "a long edge runs end to end, not out along one side and "
                    + "back along the other as a band's outline does: x " + meanX + " y " + lowest + "-" + highest);
        }
        assertEquals(1, ridges, "the crest, bright on its lit side, is one ridge line");
        assertEquals(1, valleys, "the groove's floor, bright on its far side, is one valley line");
        boolean hole = false;
        for (int region = 0; region < shading.regions.size(); region++) {
            float[] outline = shading.regions.get(region);
            hole |= Math.abs(outline[0] - HOLE_X) < HOLE_RADIUS * 2 && Math.abs(outline[1] - HOLE_Y) < HOLE_RADIUS * 2
                    && shading.darknessByRegion[region] > 0.5f;
        }
        assertTrue(hole, "the hole is a dark region");
    }

    @Test
    void aHollowWithALitFloorIsACavityAndAShadedSideIsNot() {
        // Lit along the view, as a headlight lights it: the hollow's floor faces the eye and is as
        // bright as the ball around it, only its walls darken, so it is no dark region; the ball's
        // own rim, turned from the eye, is dark but reaches the outline.
        PixelImage picture = new PixelImage(SIZE, SIZE);
        picture.fill(BACKGROUND);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float across = (x - CENTRE) / BALL;
                float down = (y - CENTRE) / BALL;
                float rest = 1f - across * across - down * down;
                if (rest <= 0f) {
                    continue;
                }
                float depth = (float) Math.sqrt(rest);
                float fromHollow = (float) Math.hypot(x - HOLE_X, y - HOLE_Y) / HOLLOW_RADIUS;
                // A bowl of depth HOLLOW_DEPTH x (1 - r^2): its slope 2 HOLLOW_DEPTH r / HOLLOW_RADIUS.
                float wall = fromHollow < 1f ? 2f * HOLLOW_DEPTH * fromHollow : 0f;
                float normalX = across / depth - (fromHollow < 1f ? wall * (x - HOLE_X) / Math.max(1e-3f,
                        fromHollow * HOLLOW_RADIUS) : 0f);
                float normalY = down / depth - (fromHollow < 1f ? wall * (y - HOLE_Y) / Math.max(1e-3f,
                        fromHollow * HOLLOW_RADIUS) : 0f);
                float lit = 1f / (float) Math.sqrt(normalX * normalX + normalY * normalY + 1f);
                int level = Math.round(LIT * (AMBIENT + (1f - AMBIENT) * lit));
                picture.set(x, y, PixelImage.OPAQUE | level << PixelImage.RED_SHIFT | level << PixelImage.CHANNEL_BITS
                        | level);
            }
        }
        ShadingEdges shading = ShadingEdges.of(picture, inside(picture));
        StringBuilder found = new StringBuilder();
        boolean hollow = false;
        for (int region = 0; region < shading.regions.size(); region++) {
            float[] outline = shading.regions.get(region);
            float meanX = 0f;
            float meanY = 0f;
            for (int point = 0; point < outline.length; point += 2) {
                meanX += outline[point] / (outline.length / 2);
                meanY += outline[point + 1] / (outline.length / 2);
            }
            found.append(String.format(" (%.0f,%.0f %d px%s)", meanX, meanY, shading.areaByRegion[region],
                    shading.blobByRegion[region] ? " blob" : ""));
            // The blob centres on the hollow's darker side, toward the ball's own lit middle.
            hollow |= shading.blobByRegion[region] && Math.hypot(meanX - HOLE_X, meanY - HOLE_Y) < HOLLOW_RADIUS;
            assertTrue(Math.hypot(meanX - CENTRE, meanY - CENTRE) < BALL / 2f, "no cavity on the shaded rim:" + found);
        }
        assertTrue(hollow, "the hollow is a region:" + found);
    }

    @Test
    void theMarksBecomeSignedStrokesOnTheBaseUnderTheTerraceLimit() {
        String[] labels = MultiviewScreenshot.VIEW_LABELS;
        float[][] angles = MultiviewScreenshot.VIEW_AZIMUTH_ELEVATION_RADIANS;
        assertEquals(6, ReferenceOutlines.cellNamed("tmp/references/ref_02_front_right.png", labels),
                "a picture named front_right is the 3/4 Front-R cell, not Right");
        assertEquals(1, ReferenceOutlines.cellNamed("a.png=Right", labels));
        int[] cells = { 0, 1, 4 };
        ReferenceOutlines traced = new ReferenceOutlines();
        traced.cellWidth = 1280;
        traced.cellHeight = 1280;
        traced.worldPerCellPixel = 2f / (0.45f * traced.cellHeight);
        ShadingEdges[] shading = new ShadingEdges[cells.length];
        String[] names = new String[cells.length];
        for (int view = 0; view < cells.length; view++) {
            PixelImage picture = ball(cells[view] == 0);
            traced.add(SubjectOutline.of(picture, SubjectOutline.borderMedian(picture), TOLERANCE, 0f), cells[view],
                    angles[cells[view]][0], angles[cells[view]][1]);
            shading[view] = ShadingEdges.of(picture, inside(picture));
            names[view] = ReferenceOutlines.viewName(labels[cells[view]]);
        }
        assertEquals("", traced.fitAxisViews(2f));
        GeometryBundle ball = new MapNodeContext(new QuadSphereMeshNode())
                .with(QuadSphereMeshNode.SIZE, 2f).with(QuadSphereMeshNode.RESOLUTION, 48).eval()
                .output(QuadSphereMeshNode.MESH, GeometryBundle.class);
        GeometryBundle base = new MapNodeContext(new MergeByDistanceNode()).with(MergeByDistanceNode.GEOMETRY, ball)
                .with(MergeByDistanceNode.DISTANCE, 1e-4f).eval().output(MergeByDistanceNode.GEOMETRY_OUT,
                        GeometryBundle.class);
        ReferenceStrokes strokes = ReferenceStrokes.of(traced, shading, names, base.mesh(),
                StrokeSketch.parse(ROOT, "template"));
        assertTrue(strokes.ridgesByView[0] > 0 && strokes.valleysByView[0] > 0 && strokes.fillsByView[0] > 0,
                "the front picture gives ridges, valleys and the hole's fill: " + strokes.ridgesByView[0] + " "
                        + strokes.valleysByView[0] + " " + strokes.fillsByView[0] + " of " + shading[0].lines.size()
                        + " lines, " + shading[0].regions.size() + " regions");
        String front = strokes.svg.substring(strokes.svg.indexOf("<g id=\"edges_front\">"));
        front = front.substring(0, front.indexOf("</g>"));
        float finest = new StrokeRefinement().minimumEdgeLength;
        Matcher shape = SHAPE.matcher(front);
        int count = 0;
        while (shape.find()) {
            count++;
            float depth = Float.parseFloat(shape.group(3));
            float radius = Float.parseFloat(shape.group(4));
            assertTrue(shape.group(1).equals("polygon") ? depth < 0f : "ridge".equals(shape.group(2)) == depth > 0f,
                    "a ridge raises, a valley and a fill sink: " + shape.group());
            assertTrue(Math.abs(depth) <= SculptStroke.steepestDepth(radius, SculptStroke.FALLOFF_STEEPEST_SLOPE,
                    finest), "every stroke stays under the terrace limit: " + shape.group());
        }
        assertTrue(count > 0, front);
        String overlay = strokes.pictureSvgByView[0];
        // Each group holds its own kind (the first attempt's format shifted them one group along).
        for (String kind : new String[] { "ridges\">\n<polyline data-kind=\"ridge\"",
            "valleys\">\n<polyline data-kind=\"valley\"", "fills\">\n<polygon " }) {
            assertTrue(overlay.contains("<g id=\"" + kind), "the picture's own overlay groups " + kind);
        }
    }

    /**
     * A ball lit from up and to the left, carrying a vertical ridge and groove and a dark hole when
     * asked, on a flat background.
     */
    private static PixelImage ball(boolean features) {
        PixelImage picture = new PixelImage(SIZE, SIZE);
        picture.fill(BACKGROUND);
        float length = (float) Math.sqrt(LIGHT[0] * LIGHT[0] + LIGHT[1] * LIGHT[1] + LIGHT[2] * LIGHT[2]);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float across = (x - CENTRE) / BALL;
                float down = (y - CENTRE) / BALL;
                float rest = 1f - across * across - down * down;
                if (rest <= 0f) {
                    continue;
                }
                float depth = (float) Math.sqrt(rest);
                // The ball's normal, tilted across x by the features' slopes (height in pixels).
                float slopeX = features ? bumpSlope(x - RIDGE_X) - bumpSlope(x - GROOVE_X) : 0f;
                float normalX = across / depth - slopeX;
                float normalY = down / depth;
                float normalLength = (float) Math.sqrt(normalX * normalX + normalY * normalY + 1f);
                float lit = (normalX * LIGHT[0] + normalY * LIGHT[1] + LIGHT[2]) / normalLength / length;
                boolean hole = features && Math.hypot(x - HOLE_X, y - HOLE_Y) < HOLE_RADIUS;
                int level = hole ? 0 : Math.round(LIT * (AMBIENT + (1f - AMBIENT) * Math.max(0f, lit)));
                picture.set(x, y, PixelImage.OPAQUE | level << PixelImage.RED_SHIFT | level << PixelImage.CHANNEL_BITS
                        | level);
            }
        }
        return picture;
    }

    /** The slope across x of a Gaussian bump of {@link #FEATURE_HEIGHT} pixels, centred at 0. */
    private static float bumpSlope(float offset) {
        float spread = offset / FEATURE_WIDTH;
        return (float) (-FEATURE_HEIGHT * spread / FEATURE_WIDTH * Math.exp(-spread * spread / 2.0));
    }

    private static boolean[] inside(PixelImage picture) {
        SubjectOutline subject = SubjectOutline.of(picture, SubjectOutline.borderMedian(picture), TOLERANCE, 0f);
        return PolygonScan.covered(subject.outline, 0f, 0f, 1f, picture.width, picture.height);
    }
}
