package ixdar.graphics.image;

import java.util.Arrays;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import ixdar.geometry.grid2d.PolygonScan;
import ixdar.graphics.cameras.Camera3D;

/**
 * Reference pictures of one subject traced into the cells of one orthographic template at one
 * shared world scale: each picture's subject, its cell's picture axes, and the scale and centre that
 * place its outline in its cell so the axes two views share measure alike.
 */
public final class ReferenceOutlines {

    public static final float AXIS_ALIGNED = 0.99f;

    public static final int HULL_LATTICE = 96;

    public static final float HULL_REACH = 0.6f;

    public static final float NEAR_PLANE_FRACTION = 0.01f;

    public static final int RIGHT = 0;

    public static final int UP = 3;

    /** World units one cell pixel spans. */
    public float worldPerCellPixel;

    /** Cell width in pixels. */
    public int cellWidth;

    /** Cell height in pixels. */
    public int cellHeight;

    /** How many pictures were added. */
    public int views;

    /** Each picture's cell. */
    public int[] cellByView = new int[0];

    /** Each picture's subject. */
    public SubjectOutline[] subjectByView = new SubjectOutline[0];

    /** Each picture's cell axes in world space, right then up, packed xyz. */
    public float[][] axesByView = new float[0][];

    /** The world axes a view's box width and height measure, or null for an oblique view. */
    public int[][] measuredByView = new int[0][];

    /** World units per picture pixel of each picture, once fitted. */
    public float[] scaleByView = new float[0];

    /** Cell pixels per picture pixel of each picture, once placed. */
    public float[] pixelsPerPicturePixelByView = new float[0];

    /** Where each picture's box centre lands in its cell, once placed. */
    public float[][] centreByView = new float[0][];

    /** The subject's world extent along x, y and z, once fitted. */
    public final float[] extents = new float[3];

    /**
     * Add a picture's subject in a cell whose orbit camera looks from an azimuth and elevation.
     *
     * @param subject   the picture's subject
     * @param cell      the cell it goes in
     * @param azimuth   the cell's orbit azimuth, radians
     * @param elevation the cell's orbit elevation, radians
     */
    public void add(SubjectOutline subject, int cell, float azimuth, float elevation) {
        int view = views++;
        cellByView = Arrays.copyOf(cellByView, views);
        subjectByView = Arrays.copyOf(subjectByView, views);
        axesByView = Arrays.copyOf(axesByView, views);
        measuredByView = Arrays.copyOf(measuredByView, views);
        scaleByView = Arrays.copyOf(scaleByView, views);
        pixelsPerPicturePixelByView = Arrays.copyOf(pixelsPerPicturePixelByView, views);
        centreByView = Arrays.copyOf(centreByView, views);
        cellByView[view] = cell;
        subjectByView[view] = subject;
        Camera3D camera = new Camera3D(new Vector3f(), 0f, 0f);
        camera.setDistanceBounds(0f, Float.MAX_VALUE);
        camera.setOrbit(azimuth, elevation, 1f);
        camera.updateProjection(true, 1f, NEAR_PLANE_FRACTION, 2f);
        Matrix4f viewMatrix = camera.view;
        float[] axes = { viewMatrix.m00(), viewMatrix.m10(), viewMatrix.m20(), viewMatrix.m01(), viewMatrix.m11(),
            viewMatrix.m21() };
        axesByView[view] = axes;
        float[] forward = { viewMatrix.m02(), viewMatrix.m12(), viewMatrix.m22() };
        int along = 0;
        for (int axis = 1; axis < 3; axis++) {
            along = Math.abs(forward[axis]) > Math.abs(forward[along]) ? axis : along;
        }
        measuredByView[view] = Math.abs(forward[along]) >= AXIS_ALIGNED
                ? new int[] { largestAxis(axes, RIGHT), largestAxis(axes, UP) } : null;
    }

    /**
     * Fit every axis view's scale by least squares in log space, so the views measuring one world
     * axis agree and those measuring height give {@code height}, then the subject's extents and the
     * axis views' placement, their box centre on the cell centre.
     *
     * @param height the subject's world height
     * @return an empty string, or why no fit exists
     */
    public String fitAxisViews(float height) {
        double[][] normal = new double[views][views];
        double[] right = new double[views];
        boolean measuresHeight = false;
        for (int axis = 0; axis < 3; axis++) {
            int previous = -1;
            for (int view = 0; view < views; view++) {
                int slot = sizeSlot(view, axis);
                if (slot < 0) {
                    continue;
                }
                double logSize = Math.log(subjectByView[view].box[slot]);
                if (previous >= 0) {
                    double target = logSize - Math.log(subjectByView[previous].box[sizeSlot(previous, axis)]);
                    normal[previous][previous] += 1.0;
                    normal[view][view] += 1.0;
                    normal[previous][view] -= 1.0;
                    normal[view][previous] -= 1.0;
                    right[previous] += target;
                    right[view] -= target;
                }
                if (axis == 1) {
                    measuresHeight = true;
                    normal[view][view] += 1.0;
                    right[view] += Math.log(height) - logSize;
                }
                previous = view;
            }
        }
        if (!measuresHeight) {
            return "no front, back, right or left picture measures the subject's height";
        }
        for (int view = 0; view < views; view++) {
            normal[view][view] += measuredByView[view] == null ? 1.0 : 0.0;
        }
        for (int pivot = 0; pivot < views; pivot++) {
            for (int row = pivot + 1; row < views; row++) {
                double factor = normal[row][pivot] / normal[pivot][pivot];
                for (int column = pivot; column < views; column++) {
                    normal[row][column] -= factor * normal[pivot][column];
                }
                right[row] -= factor * right[pivot];
            }
        }
        double[] logScale = new double[views];
        for (int row = views - 1; row >= 0; row--) {
            double sum = right[row];
            for (int column = row + 1; column < views; column++) {
                sum -= normal[row][column] * logScale[column];
            }
            logScale[row] = sum / normal[row][row];
            scaleByView[row] = (float) Math.exp(logScale[row]);
        }
        for (int axis = 0; axis < 3; axis++) {
            float sum = 0f;
            int count = 0;
            for (int view = 0; view < views; view++) {
                int slot = sizeSlot(view, axis);
                if (slot >= 0) {
                    sum += scaleByView[view] * subjectByView[view].box[slot];
                    count++;
                }
            }
            extents[axis] = count == 0 ? height : sum / count;
        }
        for (int view = 0; view < views; view++) {
            if (measuredByView[view] != null) {
                pixelsPerPicturePixelByView[view] = scaleByView[view] / worldPerCellPixel;
                centreByView[view] = new float[] { cellWidth / 2f - 0.5f, cellHeight / 2f - 0.5f };
            }
        }
        return "";
    }

    /**
     * Fit every oblique view (a 3/4 cell, which measures no single axis) to the silhouette, in its
     * cell, of the hull the axis views carve on a coarse lattice: scale from the mean of the width and
     * height ratios, centre on its box. Runs after {@link #fitAxisViews}.
     *
     * @return the first oblique view the axis views carve nothing for, or -1
     */
    public int fitObliqueViews() {
        boolean[][] masks = new boolean[views][];
        for (int view = 0; view < views; view++) {
            masks[view] = measuredByView[view] == null ? null
                    : PolygonScan.covered(placedOutline(view), 0f, 0f, 1f, cellWidth, cellHeight);
        }
        float[] point = new float[3];
        for (int view = 0; view < views; view++) {
            if (measuredByView[view] != null) {
                continue;
            }
            float[] bounds = { Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
            for (int lattice = 0; lattice < HULL_LATTICE * HULL_LATTICE * HULL_LATTICE; lattice++) {
                int remaining = lattice;
                for (int axis = 2; axis >= 0; axis--) {
                    point[axis] = (-HULL_REACH + 2f * HULL_REACH * (remaining % HULL_LATTICE) / (HULL_LATTICE - 1))
                            * extents[axis];
                    remaining /= HULL_LATTICE;
                }
                boolean kept = true;
                for (int other = 0; other < views && kept; other++) {
                    if (masks[other] != null) {
                        int column = (int) Math.floor(along(point, other, RIGHT) / worldPerCellPixel + cellWidth / 2f);
                        int row = (int) Math.floor(cellHeight / 2f - along(point, other, UP) / worldPerCellPixel);
                        kept = column >= 0 && row >= 0 && column < cellWidth && row < cellHeight
                                && masks[other][row * cellWidth + column];
                    }
                }
                if (kept) {
                    float across = along(point, view, RIGHT) / worldPerCellPixel + cellWidth / 2f - 0.5f;
                    float down = cellHeight / 2f - 0.5f - along(point, view, UP) / worldPerCellPixel;
                    bounds[0] = Math.min(bounds[0], across);
                    bounds[1] = Math.min(bounds[1], down);
                    bounds[2] = Math.max(bounds[2], across);
                    bounds[3] = Math.max(bounds[3], down);
                }
            }
            if (bounds[0] > bounds[2]) {
                return view;
            }
            SubjectOutline subject = subjectByView[view];
            pixelsPerPicturePixelByView[view] = ((bounds[2] - bounds[0]) / subject.box[2]
                    + (bounds[3] - bounds[1]) / subject.box[3]) / 2f;
            centreByView[view] = new float[] { (bounds[2] + bounds[0]) / 2f, (bounds[3] + bounds[1]) / 2f };
            scaleByView[view] = pixelsPerPicturePixelByView[view] * worldPerCellPixel;
        }
        return -1;
    }

    /**
     * A view's subject outline in its cell's pixels: its box centre at the view's centre, scaled about it.
     *
     * @param view the view
     * @return packed xy, a new array
     */
    public float[] placedOutline(int view) {
        SubjectOutline subject = subjectByView[view];
        float centreX = subject.box[0] + subject.box[2] / 2f;
        float centreY = subject.box[1] + subject.box[3] / 2f;
        float[] outline = new float[subject.outline.length];
        for (int index = 0; index < outline.length; index += 2) {
            outline[index] = (subject.outline[index] - centreX) * pixelsPerPicturePixelByView[view] + centreByView[view][0];
            outline[index + 1] = (subject.outline[index + 1] - centreY) * pixelsPerPicturePixelByView[view]
                    + centreByView[view][1];
        }
        return outline;
    }

    /**
     * Which of a subject box's sides (2 width, 3 height) measures a world axis in a view.
     *
     * @param view the view
     * @param axis world axis
     * @return 2, 3, or -1 when the view does not measure that axis
     */
    private int sizeSlot(int view, int axis) {
        int[] measured = measuredByView[view];
        return measured == null ? -1 : measured[0] == axis ? 2 : measured[1] == axis ? 3 : -1;
    }

    /**
     * A point's coordinate along one of a view's picture axes.
     *
     * @param point  world point
     * @param view   the view
     * @param offset {@link #RIGHT} or {@link #UP}
     * @return the dot product
     */
    private float along(float[] point, int view, int offset) {
        float[] axes = axesByView[view];
        return point[0] * axes[offset] + point[1] * axes[offset + 1] + point[2] * axes[offset + 2];
    }

    /**
     * Which world axis a picture axis runs closest to.
     *
     * @param axes   right then up, packed xyz
     * @param offset {@link #RIGHT} or {@link #UP}
     * @return 0, 1 or 2
     */
    private static int largestAxis(float[] axes, int offset) {
        int largest = 0;
        for (int axis = 1; axis < 3; axis++) {
            largest = Math.abs(axes[offset + axis]) > Math.abs(axes[offset + largest]) ? axis : largest;
        }
        return largest;
    }
}
