package unit.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.automation.endpoints.ui.MultiviewScreenshot;
import ixdar.geometry.grid2d.OutlineOverlap;
import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.PngWriter;
import ixdar.graphics.image.ReferenceOutlines;
import ixdar.graphics.image.SubjectOutline;

/**
 * The maths the tracing and outline-compare routes run (MESH-80 moved it out of the Python CLI):
 * overlap and distance between two outlines, a subject's contour from a picture, and reference
 * pictures at unrelated zooms fitted to one shared world scale.
 */
class OutlineTracingTest {

    private static final int GRID = 100;

    private static final int SQUARE_LOW = 20;

    private static final int SQUARE_HIGH = 80;

    private static final int SHIFT = 5;

    private static final float OVERLAP_TOLERANCE = 1e-4f;

    private static final float MEAN_LOW = 1.5f;

    private static final float MEAN_HIGH = 3.5f;

    private static final int PICTURE_WIDTH = 200;

    private static final int PICTURE_HEIGHT = 160;

    private static final float ELLIPSE_X = 100f;

    private static final float ELLIPSE_Y = 80f;

    private static final float ELLIPSE_HALF_WIDTH = 40f;

    private static final float ELLIPSE_HALF_HEIGHT = 60f;

    private static final int TOLERANCE = 40;

    private static final int BAND_TOP = 10;

    private static final int BAND_BOTTOM = 50;

    private static final int BOX_TOLERANCE = 1;

    private static final float AREA_TOLERANCE = 0.04f;

    private static final float[] RADII = { 0.6f, 1.0f, 0.8f };

    private static final int[] CELLS = { 0, 1, 4, 6 };

    private static final float[] PIXELS_PER_UNIT = { 180f, 95f, 240f, 130f };

    private static final int CELL_SIZE = 400;

    private static final float HEIGHT = 2f;

    private static final float FILL = 0.45f;

    private static final float FIELD_OF_VIEW = 45f;

    private static final float EXTENT_TOLERANCE = 0.03f;

    private static final float CENTRE_TOLERANCE = 1.5f;

    private static final float OBLIQUE_TOLERANCE = 0.06f;

    @Test
    void overlapAndDistanceOfAHandMadePair() {
        boolean[] trace = new boolean[GRID * GRID];
        boolean[] shape = new boolean[GRID * GRID];
        for (int y = SQUARE_LOW; y < SQUARE_HIGH; y++) {
            for (int x = SQUARE_LOW; x < SQUARE_HIGH; x++) {
                trace[y * GRID + x] = true;
                shape[y * GRID + x + SHIFT] = true;
            }
        }
        OutlineOverlap overlap = new OutlineOverlap().measure(trace, shape, GRID, GRID);
        int side = SQUARE_HIGH - SQUARE_LOW;
        assertEquals((side - SHIFT) * side / (float) ((side + SHIFT) * side), overlap.intersectionOverUnion,
                OVERLAP_TOLERANCE);
        assertEquals(SHIFT * side, overlap.shortPixels);
        assertEquals(SHIFT * side, overlap.overPixels);
        assertEquals(SHIFT, overlap.maxPixels, OVERLAP_TOLERANCE, "the shifted sides lie the shift apart");
        assertTrue(overlap.meanPixels > MEAN_LOW && overlap.meanPixels < MEAN_HIGH, "top and bottom edges mostly "
                + "coincide, so the mean is under the shift: " + overlap.meanPixels);
    }

    @Test
    void contourOfAnEllipseFromItsPicture() {
        PixelImage picture = new PixelImage(PICTURE_WIDTH, PICTURE_HEIGHT);
        int background = picture.pixels[0] = PixelImage.OPAQUE | PixelImage.RGB_MASK;
        for (int pixel = 0; pixel < picture.pixels.length; pixel++) {
            float u = (pixel % PICTURE_WIDTH - ELLIPSE_X) / ELLIPSE_HALF_WIDTH;
            float v = (pixel / PICTURE_WIDTH - ELLIPSE_Y) / ELLIPSE_HALF_HEIGHT;
            picture.pixels[pixel] = u * u + v * v <= 1f ? PixelImage.OPAQUE : background;
        }
        SubjectOutline subject = SubjectOutline.of(picture, SubjectOutline.borderMedian(picture), TOLERANCE, 0f);
        assertEquals(ELLIPSE_X - ELLIPSE_HALF_WIDTH, subject.box[0], BOX_TOLERANCE);
        assertEquals(ELLIPSE_Y - ELLIPSE_HALF_HEIGHT, subject.box[1], BOX_TOLERANCE);
        assertEquals(2 * ELLIPSE_HALF_WIDTH + 1, subject.box[2], BOX_TOLERANCE);
        assertEquals(2 * ELLIPSE_HALF_HEIGHT + 1, subject.box[3], BOX_TOLERANCE);
        double area = 0.0;
        float[] outline = subject.outline;
        for (int point = 0; point + 3 < outline.length; point += 2) {
            area += outline[point] * outline[point + 3] - outline[point + 2] * outline[point + 1];
        }
        double expected = Math.PI * ELLIPSE_HALF_WIDTH * ELLIPSE_HALF_HEIGHT;
        assertEquals(1.0, Math.abs(area) / 2.0 / expected, AREA_TOLERANCE, "the contour encloses the ellipse");
    }

    @Test
    void aPictureFileReadsTopRowFirst(@TempDir Path folder) throws IOException {
        // The platform decoder hands back rows bottom-up for OpenGL; read must undo that, or every
        // traced outline, and the hull built from it, comes out upside down.
        PixelImage drawn = new PixelImage(PICTURE_WIDTH, PICTURE_HEIGHT);
        for (int y = 0; y < PICTURE_HEIGHT; y++) {
            for (int x = 0; x < PICTURE_WIDTH; x++) {
                boolean band = y >= BAND_TOP && y < BAND_BOTTOM && x >= BAND_TOP && x < PICTURE_WIDTH - BAND_TOP;
                drawn.pixels[y * PICTURE_WIDTH + x] = band ? PixelImage.OPAQUE : PixelImage.OPAQUE | PixelImage.RGB_MASK;
            }
        }
        Path path = folder.resolve("top-band.png");
        PngWriter.write(drawn, path.toFile());
        PixelImage picture = SubjectOutline.read(path);
        assertEquals(PixelImage.OPAQUE, picture.get(PICTURE_WIDTH / 2, BAND_TOP), "the band is near the top");
        assertEquals(PixelImage.OPAQUE | PixelImage.RGB_MASK, picture.get(PICTURE_WIDTH / 2,
                PICTURE_HEIGHT - 1 - BAND_TOP));
        SubjectOutline subject = SubjectOutline.of(picture, SubjectOutline.borderMedian(picture), TOLERANCE, 0f);
        assertEquals(BAND_TOP, subject.box[1], BOX_TOLERANCE, "the subject's box starts near the top");
    }

    @Test
    void aReferenceNamesItsCellByPictureEqualsCellOrByItsFileName() {
        // reference-compare and trace-outlines take a bare picture named by its view: the longest
        // view name the file name ends in wins, so front_right is the 3/4 view, not Right.
        String[] labels = MultiviewScreenshot.VIEW_LABELS;
        int threeQuarterRight = ReferenceOutlines.cellNamed("x=3/4 Front-R", labels);
        assertTrue(threeQuarterRight >= 0, "picture=label names a cell");
        assertEquals(threeQuarterRight, ReferenceOutlines.cellNamed("tmp/references/ref_02_front_right.png", labels));
        int right = ReferenceOutlines.cellNamed("x=Right", labels);
        assertTrue(right >= 0 && right != threeQuarterRight);
        assertEquals(right, ReferenceOutlines.cellNamed("tmp/references/ref_04_right.png", labels));
        assertEquals(1, ReferenceOutlines.cellNamed("a.png=1", labels), "a cell by index");
        assertEquals(-1, ReferenceOutlines.cellNamed("tmp/references/skull.png", labels), "no view named");
        String picture = "tmp/a.png";
        assertEquals(picture, ReferenceOutlines.pictureNamed(picture + "=Front"));
        assertEquals(picture, ReferenceOutlines.pictureNamed(picture));
    }

    @Test
    void picturesAtDifferentZoomsShareOneWorldScale() {
        // An ellipsoid of radii 0.6, 1.0, 0.8 pictured front, right, top and 3/4 at unrelated zooms:
        // each axis comes out measured alike by the two views across it, the height pinned to 2, and
        // the 3/4 outline sized to the ellipsoid's own 3/4 silhouette.
        ReferenceOutlines traced = new ReferenceOutlines();
        traced.cellWidth = CELL_SIZE;
        traced.cellHeight = CELL_SIZE;
        float distance = HEIGHT / (FILL * 2f * (float) Math.tan(Math.toRadians(FIELD_OF_VIEW) / 2.0));
        traced.worldPerCellPixel = 2f * distance * (float) Math.tan(Math.toRadians(FIELD_OF_VIEW) / 2.0) / CELL_SIZE;
        float[][] angles = MultiviewScreenshot.VIEW_AZIMUTH_ELEVATION_RADIANS;
        for (int view = 0; view < CELLS.length; view++) {
            float[] cell = angles[CELLS[view]];
            ReferenceOutlines probe = new ReferenceOutlines();
            probe.add(null, CELLS[view], cell[0], cell[1]);
            float[] axes = probe.axesByView[0];
            Vector3f right = new Vector3f(axes[0], axes[1], axes[2]);
            Vector3f up = new Vector3f(axes[3], axes[4], axes[5]);
            Vector3f forward = new Vector3f(right).cross(up);
            int size = (int) (PIXELS_PER_UNIT[view] * 2.6f);
            PixelImage picture = new PixelImage(size, size);
            for (int pixel = 0; pixel < picture.pixels.length; pixel++) {
                float u = (pixel % size - size / 2f) / PIXELS_PER_UNIT[view];
                float v = -(pixel / size - size / 2f) / PIXELS_PER_UNIT[view];
                // The line through (u, v) along the view meets the ellipsoid when its closest approach,
                // in the ellipsoid's own scaled space, lies within the unit sphere.
                float[] start = new float[3];
                float[] along = new float[3];
                double nearest = 0.0;
                double alongSquared = 0.0;
                for (int axis = 0; axis < 3; axis++) {
                    start[axis] = (u * right.get(axis) + v * up.get(axis)) / RADII[axis];
                    along[axis] = forward.get(axis) / RADII[axis];
                    nearest -= start[axis] * along[axis];
                    alongSquared += along[axis] * along[axis];
                }
                double reach = 0.0;
                for (int axis = 0; axis < 3; axis++) {
                    double at = start[axis] + nearest / alongSquared * along[axis];
                    reach += at * at;
                }
                picture.pixels[pixel] = reach <= 1.0 ? PixelImage.OPAQUE : PixelImage.OPAQUE | PixelImage.RGB_MASK;
            }
            traced.add(SubjectOutline.of(picture, SubjectOutline.borderMedian(picture), TOLERANCE, 0f), CELLS[view],
                    cell[0], cell[1]);
        }
        assertEquals("", traced.fitAxisViews(HEIGHT));
        assertEquals(-1, traced.fitObliqueViews());
        int[][] acrossAndDown = { { 0, 1 }, { 2, 1 }, { 0, 2 } };
        for (int view = 0; view < acrossAndDown.length; view++) {
            float[] box = box(traced.placedOutline(view));
            assertEquals(2 * RADII[acrossAndDown[view][0]], box[2] * traced.worldPerCellPixel, EXTENT_TOLERANCE,
                    "cell " + CELLS[view] + " width");
            assertEquals(2 * RADII[acrossAndDown[view][1]], box[3] * traced.worldPerCellPixel, EXTENT_TOLERANCE,
                    "cell " + CELLS[view] + " height");
            assertEquals(CELL_SIZE / 2f - 0.5f, box[0] + box[2] / 2f, CENTRE_TOLERANCE);
            assertEquals(CELL_SIZE / 2f - 0.5f, box[1] + box[3] / 2f, CENTRE_TOLERANCE);
        }
        float[] oblique = box(traced.placedOutline(CELLS.length - 1));
        float[] axes = traced.axesByView[CELLS.length - 1];
        float[] extent = new float[2];
        for (int side = 0; side < 2; side++) {
            float squared = 0f;
            for (int axis = 0; axis < 3; axis++) {
                squared += axes[side * 3 + axis] * axes[side * 3 + axis] * RADII[axis] * RADII[axis];
            }
            extent[side] = 2f * (float) Math.sqrt(squared) / traced.worldPerCellPixel;
        }
        assertEquals(1f, oblique[2] / extent[0], OBLIQUE_TOLERANCE, "3/4 width against the ellipsoid's silhouette");
        assertEquals(1f, oblique[3] / extent[1], OBLIQUE_TOLERANCE, "3/4 height against the ellipsoid's silhouette");
    }

    /**
     * A packed outline's bounding box.
     *
     * @param outline packed xy
     * @return left, top, width, height
     */
    private static float[] box(float[] outline) {
        float[] bounds = { Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        for (int index = 0; index < outline.length; index += 2) {
            bounds[0] = Math.min(bounds[0], outline[index]);
            bounds[1] = Math.min(bounds[1], outline[index + 1]);
            bounds[2] = Math.max(bounds[2], outline[index]);
            bounds[3] = Math.max(bounds[3], outline[index + 1]);
        }
        return new float[] { bounds[0], bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1] };
    }
}
