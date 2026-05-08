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
     * TODO: document {@code CurveGeometry}.
     *
     * @param positions TODO: describe
     * @param curveOffsets TODO: describe
     * @throws IllegalArgumentException TODO: describe
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
     * TODO: document {@code positions}.
     *
     * @return TODO: describe
     */
    public float[] positions() {
        return positions;
    }

    /**
     * TODO: document {@code curveOffsets}.
     *
     * @return TODO: describe
     */
    public int[] curveOffsets() {
        return curveOffsets;
    }

    /**
     * TODO: document {@code curveCount}.
     *
     * @return TODO: describe
     */
    public int curveCount() {
        return curveOffsets.length - 1;
    }

    /**
     * TODO: document {@code pointCount}.
     *
     * @return TODO: describe
     */
    public int pointCount() {
        return positions.length / NUM_3;
    }

    /**
     * TODO: document {@code singlePolyline}.
     *
     * @param positions TODO: describe
     * @return TODO: describe
     */
    public static CurveGeometry singlePolyline(float[] positions) {
        int n = positions.length / NUM_3;
        return new CurveGeometry(Arrays.copyOf(positions, positions.length), new int[] { 0, n });
    }
}
