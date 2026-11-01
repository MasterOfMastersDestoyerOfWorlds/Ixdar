package ixdar.geometry.grid2d;

import java.util.Arrays;

/**
 * Which points of a regular 2D lattice a closed polygon covers, found one scanline per lattice row
 * by the even-odd rule, so holes and self-overlaps count as a picture's fill does.
 */
public final class PolygonScan {

    private PolygonScan() {
    }

    /**
     * Mark the lattice points {@code (startX + column * step, startY + row * step)} a closed polygon
     * covers; a point exactly on a left edge counts as inside, one on a right edge as outside.
     *
     * @param polygon packed xy of the closed polygon, its last point joined to its first
     * @param startX  x of lattice column 0
     * @param startY  y of lattice row 0
     * @param step    lattice spacing
     * @param columns lattice columns
     * @param rows    lattice rows
     * @return covered flags, row-major
     */
    public static boolean[] covered(float[] polygon, float startX, float startY, float step, int columns, int rows) {
        boolean[] inside = new boolean[columns * rows];
        int count = polygon.length / 2;
        float[] crossings = new float[count];
        for (int row = 0; row < rows; row++) {
            float y = startY + row * step;
            int found = 0;
            for (int index = 0, previous = count - 1; index < count; previous = index++) {
                float fromY = polygon[previous * 2 + 1];
                float toY = polygon[index * 2 + 1];
                if (fromY <= y && y < toY || toY <= y && y < fromY) {
                    crossings[found++] = polygon[previous * 2]
                            + (y - fromY) * (polygon[index * 2] - polygon[previous * 2]) / (toY - fromY);
                }
            }
            Arrays.sort(crossings, 0, found);
            for (int pair = 0; pair + 1 < found; pair += 2) {
                int first = Math.max(0, (int) Math.ceil((crossings[pair] - startX) / step));
                int last = Math.min(columns - 1, (int) Math.ceil((crossings[pair + 1] - startX) / step) - 1);
                for (int column = first; column <= last; column++) {
                    inside[row * columns + column] = true;
                }
            }
        }
        return inside;
    }

    /**
     * The covered points of a lattice laid over the polygon's bounding box, its first point half a
     * step inside the box's corner.
     *
     * @param polygon packed xy of the closed polygon
     * @param step    lattice spacing
     * @return packed xy of the covered lattice points, row by row
     */
    public static float[] coveredPoints(float[] polygon, float step) {
        float minimumX = Float.POSITIVE_INFINITY;
        float minimumY = Float.POSITIVE_INFINITY;
        float maximumX = Float.NEGATIVE_INFINITY;
        float maximumY = Float.NEGATIVE_INFINITY;
        for (int index = 0; index + 1 < polygon.length; index += 2) {
            minimumX = Math.min(minimumX, polygon[index]);
            maximumX = Math.max(maximumX, polygon[index]);
            minimumY = Math.min(minimumY, polygon[index + 1]);
            maximumY = Math.max(maximumY, polygon[index + 1]);
        }
        float startX = minimumX + step / 2;
        float startY = minimumY + step / 2;
        int columns = Math.max(0, (int) Math.ceil((maximumX - startX) / step));
        int rows = Math.max(0, (int) Math.ceil((maximumY - startY) / step));
        boolean[] inside = covered(polygon, startX, startY, step, columns, rows);
        float[] points = new float[inside.length * 2];
        int found = 0;
        for (int point = 0; point < inside.length; point++) {
            if (inside[point]) {
                points[found * 2] = startX + point % columns * step;
                points[found * 2 + 1] = startY + point / columns * step;
                found++;
            }
        }
        return Arrays.copyOf(points, found * 2);
    }

    /**
     * Each point's distance to the polygon's outline, the nearest of its edges, the closing edge
     * included.
     *
     * @param polygon packed xy of the closed polygon
     * @param points  packed xy of the points measured
     * @return one distance per point, infinite for a polygon with no corner
     */
    public static float[] outlineDistances(float[] polygon, float[] points) {
        float[] distances = new float[points.length / 2];
        for (int point = 0; point < distances.length; point++) {
            distances[point] = Float.POSITIVE_INFINITY;
            for (int corner = 0; corner + 1 < polygon.length; corner += 2) {
                int next = (corner + 2) % polygon.length;
                float edgeX = polygon[next] - polygon[corner];
                float edgeY = polygon[next + 1] - polygon[corner + 1];
                float offsetX = points[point * 2] - polygon[corner];
                float offsetY = points[point * 2 + 1] - polygon[corner + 1];
                float along = Math.max(0f, Math.min(1f, (offsetX * edgeX + offsetY * edgeY)
                        / Math.max(Float.MIN_NORMAL, edgeX * edgeX + edgeY * edgeY)));
                distances[point] = Math.min(distances[point],
                        (float) Math.hypot(offsetX - along * edgeX, offsetY - along * edgeY));
            }
        }
        return distances;
    }
}
