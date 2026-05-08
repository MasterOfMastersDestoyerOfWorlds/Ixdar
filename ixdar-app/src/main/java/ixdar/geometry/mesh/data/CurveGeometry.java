package ixdar.geometry.mesh.data;

import java.util.Arrays;

import ixdar.annotations.meshnode.GeometryBundleValue;

/**
 * One or more 3D polylines: packed xyz positions and curve start offsets (length = numCurves + 1).
 */
public final class CurveGeometry implements GeometryBundleValue {
    public static final int NUM_3 = 3;

    private final float[] positions;
    /** curveOffsets[k] is the first point index of curve k; last entry is total point count. */
    private final int[] curveOffsets;

    /**
     * Wrap the packed point/offset arrays directly (no defensive copy).
     *
     * @param positions packed xyz; length must be divisible by 3
     * @param curveOffsets curve start offsets; must contain at least 2 entries (one curve)
     * @throws IllegalArgumentException if either array is null or has the wrong shape
     */
    public CurveGeometry(float[] positions, int[] curveOffsets) {
        if (positions == null || positions.length % NUM_3 != 0) {
            throw new IllegalArgumentException("positions");
        }
        if (curveOffsets == null || curveOffsets.length < 2) {
            throw new IllegalArgumentException("curveOffsets");
        }
        this.positions = positions;
        this.curveOffsets = curveOffsets;
    }

    /**
     * Backing positions array (shared, not a copy).
     *
     * @return packed xyz of all points across all curves
     */
    public float[] positions() {
        return positions;
    }

    /**
     * Backing curve-start offsets (shared, not a copy).
     *
     * @return offsets array of length {@code curveCount() + 1}
     */
    public int[] curveOffsets() {
        return curveOffsets;
    }

    /**
     * Number of polylines packed in this geometry.
     *
     * @return {@code curveOffsets.length - 1}
     */
    public int curveCount() {
        return curveOffsets.length - 1;
    }

    /**
     * Total number of 3D points across all curves.
     *
     * @return {@code positions.length / 3}
     */
    public int pointCount() {
        return positions.length / NUM_3;
    }

    /**
     * Build a single-curve geometry by copying {@code positions} into a fresh array.
     *
     * @param positions packed xyz of the polyline
     * @return geometry with one curve covering all points
     */
    public static CurveGeometry singlePolyline(float[] positions) {
        int n = positions.length / NUM_3;
        return new CurveGeometry(Arrays.copyOf(positions, positions.length), new int[] { 0, n });
    }
}
