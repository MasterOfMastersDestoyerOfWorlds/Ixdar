package ixdar.geometry.mesh.data.sculpt;

import org.joml.Vector3f;

/**
 * A stroke's elements bucketed on a grid across its direction, so a vertex measures only those
 * within a radius of it.
 *
 * <p>Element {@code i} is point {@code i} to the next for a joined stroke, else the point alone.
 * Each cell lists, in index order, the elements whose radius-grown box touches it.
 */
public final class StrokeElementGrid {

    public static final int MAXIMUM_CELLS_PER_ELEMENT = 64;

    public static final float BOX_SLACK_FRACTION_OF_RADIUS = 1e-3f;

    private final float[] key;

    private final float[] pointXyz;

    private final Vector3f across = new Vector3f();

    private final Vector3f up = new Vector3f();

    private final float lowAcross;

    private final float lowUp;

    private final float cell;

    private final int columns;

    private final int rows;

    private final int[] cellStart;

    private final int[] cellElements;

    /**
     * Bucket a stroke's elements across a direction.
     *
     * @param stroke    the stroke
     * @param direction its mean normal, unit length
     */
    public StrokeElementGrid(SculptStroke stroke, Vector3f direction) {
        key = new float[] { direction.x, direction.y, direction.z };
        pointXyz = stroke.pointXyz;
        Vector3f helper = Math.abs(direction.x) < 0.9f ? new Vector3f(1f, 0f, 0f) : new Vector3f(0f, 1f, 0f);
        across.set(direction).cross(helper).normalize();
        up.set(direction).cross(across).normalize();
        int elementCount = pointXyz.length / 3;
        float reach = stroke.radius * (1f + BOX_SLACK_FRACTION_OF_RADIUS);
        float[] box = new float[4 * elementCount];
        float low0 = Float.POSITIVE_INFINITY;
        float low1 = Float.POSITIVE_INFINITY;
        float high0 = Float.NEGATIVE_INFINITY;
        float high1 = Float.NEGATIVE_INFINITY;
        for (int element = 0; element < elementCount; element++) {
            int start = element * 3;
            int end = stroke.joinsPoints && start + 3 < pointXyz.length ? start + 3 : start;
            float startAcross = project(across, start);
            float endAcross = project(across, end);
            float startUp = project(up, start);
            float endUp = project(up, end);
            box[4 * element] = Math.min(startAcross, endAcross) - reach;
            box[4 * element + 1] = Math.min(startUp, endUp) - reach;
            box[4 * element + 2] = Math.max(startAcross, endAcross) + reach;
            box[4 * element + 3] = Math.max(startUp, endUp) + reach;
            low0 = Math.min(low0, box[4 * element]);
            low1 = Math.min(low1, box[4 * element + 1]);
            high0 = Math.max(high0, box[4 * element + 2]);
            high1 = Math.max(high1, box[4 * element + 3]);
        }
        lowAcross = low0;
        lowUp = low1;
        // Cells start one radius wide; a stroke long against its radius widens them, which only adds
        // elements to a cell's list, so the grid stays within its cell budget per element.
        float area = Math.max(0f, high0 - low0) * Math.max(0f, high1 - low1);
        float widest = (float) Math.sqrt(area / Math.max(1, MAXIMUM_CELLS_PER_ELEMENT * elementCount));
        cell = Math.max(stroke.radius, widest);
        columns = elementCount == 0 ? 0 : (int) ((high0 - low0) / cell) + 1;
        rows = elementCount == 0 ? 0 : (int) ((high1 - low1) / cell) + 1;
        // Each element's cell rectangle: first column, last column, first row, last row.
        int[] span = new int[4 * elementCount];
        cellStart = new int[columns * rows + 1];
        for (int element = 0; element < elementCount; element++) {
            span[4 * element] = (int) ((box[4 * element] - lowAcross) / cell);
            span[4 * element + 1] = Math.min(columns - 1, (int) ((box[4 * element + 2] - lowAcross) / cell));
            span[4 * element + 2] = (int) ((box[4 * element + 1] - lowUp) / cell);
            span[4 * element + 3] = Math.min(rows - 1, (int) ((box[4 * element + 3] - lowUp) / cell));
            for (int row = span[4 * element + 2]; row <= span[4 * element + 3]; row++) {
                for (int column = span[4 * element]; column <= span[4 * element + 1]; column++) {
                    cellStart[row * columns + column + 1]++;
                }
            }
        }
        for (int at = 0; at < columns * rows; at++) {
            cellStart[at + 1] += cellStart[at];
        }
        cellElements = new int[cellStart[columns * rows]];
        int[] cursor = cellStart.clone();
        for (int element = 0; element < elementCount; element++) {
            for (int row = span[4 * element + 2]; row <= span[4 * element + 3]; row++) {
                for (int column = span[4 * element]; column <= span[4 * element + 1]; column++) {
                    cellElements[cursor[row * columns + column]++] = element;
                }
            }
        }
    }

    /**
     * Whether this grid was built for the stroke's current points and this direction.
     *
     * @param stroke    the stroke asking
     * @param direction the direction it measures across now
     * @return true when the grid can answer for them
     */
    public boolean fits(SculptStroke stroke, Vector3f direction) {
        return pointXyz == stroke.pointXyz && key[0] == direction.x && key[1] == direction.y
                && key[2] == direction.z;
    }

    /**
     * The cell a position falls in.
     *
     * @param x position x
     * @param y position y
     * @param z position z
     * @return the cell index, or -1 outside the grid, where no element is within the radius
     */
    public int cellOf(float x, float y, float z) {
        float atAcross = across.x * x + across.y * y + across.z * z - lowAcross;
        float atUp = up.x * x + up.y * y + up.z * z - lowUp;
        if (!(atAcross >= 0f && atUp >= 0f)) {
            return -1;
        }
        int column = (int) (atAcross / cell);
        int row = (int) (atUp / cell);
        return column < columns && row < rows ? row * columns + column : -1;
    }

    /**
     * Where a cell's elements start in {@link #element}; cell {@code c}'s run ends where cell
     * {@code c + 1}'s starts.
     *
     * @param cell a cell index from {@link #cellOf}, or one past the last to end the last run
     * @return the first slot
     */
    public int first(int cell) {
        return cellStart[cell];
    }

    /**
     * The element in one slot, in increasing index order within each cell.
     *
     * @param slot a slot of a cell's run, from {@link #first}
     * @return the element index, its start point
     */
    public int element(int slot) {
        return cellElements[slot];
    }

    private float project(Vector3f axis, int point) {
        return axis.x * pointXyz[point] + axis.y * pointXyz[point + 1] + axis.z * pointXyz[point + 2];
    }
}
