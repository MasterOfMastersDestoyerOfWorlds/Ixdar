package ixdar.geometry.mesh.data.representation;

import org.joml.Vector3f;

/**
 * One point of a packed xyz array (vertex positions, handles, waypoints, GPU buffers) read out as
 * a vector or written back, so a caller names the point instead of spelling out its three slots.
 */
public final class PackedPoints {

    private PackedPoints() {
    }

    /**
     * The point whose x sits at {@code offset}, as a new vector.
     *
     * @param packed packed xyz, three floats per point
     * @param offset index of the point's x in {@code packed}
     * @return a new vector holding the point
     */
    public static Vector3f at(float[] packed, int offset) {
        return new Vector3f(packed[offset], packed[offset + 1], packed[offset + 2]);
    }

    /**
     * Write a point into a packed xyz array, its x at {@code offset}.
     *
     * @param packed packed xyz, three floats per point
     * @param offset index the point's x goes to
     * @param point  the point to write
     */
    public static void store(float[] packed, int offset, Vector3f point) {
        packed[offset] = point.x;
        packed[offset + 1] = point.y;
        packed[offset + 2] = point.z;
    }

    /**
     * Write a point into a packed double xyz array, its x at {@code offset}.
     *
     * @param packed packed xyz, three doubles per point
     * @param offset index the point's x goes to
     * @param point  the point to write
     */
    public static void store(double[] packed, int offset, Vector3f point) {
        packed[offset] = point.x;
        packed[offset + 1] = point.y;
        packed[offset + 2] = point.z;
    }
}
