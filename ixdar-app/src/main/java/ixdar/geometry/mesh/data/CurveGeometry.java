package ixdar.geometry.mesh.data;

import java.util.Arrays;

import ixdar.annotations.meshnode.GeometryBundleValue;

/**
 * One or more 3D polylines: packed xyz positions and curve start offsets (length = numCurves + 1).
 */
public final class CurveGeometry implements GeometryBundleValue {

    private final float[] positions;
    /** curveOffsets[k] is the first point index of curve k; last entry is total point count. */
    private final int[] curveOffsets;

    public CurveGeometry(float[] positions, int[] curveOffsets) {
        if (positions == null || positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions");
        }
        if (curveOffsets == null || curveOffsets.length < 2) {
            throw new IllegalArgumentException("curveOffsets");
        }
        this.positions = positions;
        this.curveOffsets = curveOffsets;
    }

    public float[] positions() {
        return positions;
    }

    public int[] curveOffsets() {
        return curveOffsets;
    }

    public int curveCount() {
        return curveOffsets.length - 1;
    }

    public int pointCount() {
        return positions.length / 3;
    }

    public static CurveGeometry singlePolyline(float[] positions) {
        int n = positions.length / 3;
        return new CurveGeometry(Arrays.copyOf(positions, positions.length), new int[] { 0, n });
    }
}
