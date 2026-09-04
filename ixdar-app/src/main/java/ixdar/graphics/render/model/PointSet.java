package ixdar.graphics.render.model;

import org.joml.Vector3f;

import ixdar.graphics.render.color.Color;

/**
 * Sphere markers: flat xyz with a colour and a world-space radius cap (zero for none) per
 * point, and one scale applied to the shared sphere radius before the cap.
 */
public final class PointSet {

    public final float[] xyz;
    public final Color[] colors;
    public final float[] radiusCaps;
    public final float scale;
    public int count;

    /**
     * An empty set.
     *
     * @param capacity points the set can hold
     * @param scale    scale on the shared sphere radius
     */
    public PointSet(int capacity, float scale) {
        this.xyz = new float[capacity * 3];
        this.colors = new Color[capacity];
        this.radiusCaps = new float[capacity];
        this.scale = scale;
    }

    /**
     * Every point of a packed cloud in one colour.
     *
     * @param packed    flat xyz, or {@code null} for an empty set
     * @param color     colour of every point
     * @param scale     scale on the shared sphere radius
     * @param radiusCap world-space radius cap of every point, zero for none
     * @return the set
     */
    public static PointSet cloud(float[] packed, Color color, float scale, float radiusCap) {
        int pointCount = packed == null ? 0 : packed.length / 3;
        PointSet points = new PointSet(pointCount, scale);
        for (int index = 0; index < pointCount; index++) {
            points.add(packed[index * 3], packed[index * 3 + 1], packed[index * 3 + 2], color,
                    radiusCap);
        }
        return points;
    }

    /**
     * Append one point.
     *
     * @param position  world position
     * @param color     sphere colour
     * @param radiusCap world-space radius cap, zero for none
     */
    public void add(Vector3f position, Color color, float radiusCap) {
        add(position.x, position.y, position.z, color, radiusCap);
    }

    /**
     * Append one point.
     *
     * @param x         x
     * @param y         y
     * @param z         z
     * @param color     sphere colour
     * @param radiusCap world-space radius cap, zero for none
     */
    public void add(float x, float y, float z, Color color, float radiusCap) {
        xyz[count * 3] = x;
        xyz[count * 3 + 1] = y;
        xyz[count * 3 + 2] = z;
        colors[count] = color;
        radiusCaps[count] = radiusCap;
        count++;
    }
}
