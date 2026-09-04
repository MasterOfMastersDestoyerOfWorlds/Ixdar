package ixdar.graphics.render.model;

import org.joml.Vector3f;

/** GL_LINES vertices: flat xyz, two points per segment, filled front to back. */
public final class LineSet {

    public final float[] xyz;
    public int cursor;

    /**
     * An empty set.
     *
     * @param segmentCount segments the set will hold
     */
    public LineSet(int segmentCount) {
        this.xyz = new float[segmentCount * 2 * 3];
    }

    /**
     * The segments of a polyline: each consecutive point pair becomes one.
     *
     * @param points flat xyz in walking order
     * @return the segments
     */
    public static LineSet polyline(float[] points) {
        int count = points.length / 3;
        LineSet lines = new LineSet(Math.max(0, count - 1));
        for (int index = 1; index < count; index++) {
            lines.point(points, index - 1);
            lines.point(points, index);
        }
        return lines;
    }

    /**
     * Append one segment endpoint.
     *
     * @param point endpoint
     */
    public void point(Vector3f point) {
        point(point.x, point.y, point.z);
    }

    /**
     * Append one segment endpoint read from a packed xyz array.
     *
     * @param packed flat xyz
     * @param index  point index into {@code packed}
     */
    public void point(float[] packed, int index) {
        point(packed[index * 3], packed[index * 3 + 1], packed[index * 3 + 2]);
    }

    /**
     * Append one segment endpoint.
     *
     * @param x x
     * @param y y
     * @param z z
     */
    public void point(float x, float y, float z) {
        xyz[cursor] = x;
        xyz[cursor + 1] = y;
        xyz[cursor + 2] = z;
        cursor += 3;
    }

    /**
     * Endpoints appended so far.
     *
     * @return the count
     */
    public int vertexCount() {
        return cursor / 3;
    }
}
