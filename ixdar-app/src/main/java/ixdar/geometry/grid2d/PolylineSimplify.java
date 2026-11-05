package ixdar.geometry.grid2d;

import java.util.Arrays;

/**
 * Douglas-Peucker simplification of a polyline of any dimension (Cartographica 1973): keep the
 * point farthest from the chord between the kept ends while it lies beyond the tolerance,
 * recursively.
 */
public final class PolylineSimplify {

    private PolylineSimplify() {
    }

    /**
     * The 2D polyline's points that Douglas-Peucker keeps, its two ends always among them.
     *
     * @param xy        packed x then y; a closed outline repeats its first point last
     * @param tolerance largest distance a dropped point may lie from the kept polyline
     * @return the kept points, packed xy, in order
     */
    public static float[] douglasPeucker(float[] xy, float tolerance) {
        boolean[] keep = keptPoints(xy, 2, tolerance);
        float[] kept = new float[xy.length - xy.length % 2];
        int written = 0;
        for (int point = 0; point < keep.length; point++) {
            if (keep[point]) {
                kept[written++] = xy[point * 2];
                kept[written++] = xy[point * 2 + 1];
            }
        }
        return Arrays.copyOf(kept, written);
    }

    /**
     * Which of a polyline's points Douglas-Peucker keeps, its two ends always among them.
     *
     * @param packed    coordinates, {@code stride} floats per point
     * @param stride    floats per point
     * @param tolerance largest distance a dropped point may lie from the kept polyline
     * @return one flag per point, true where the point is kept
     */
    public static boolean[] keptPoints(float[] packed, int stride, float tolerance) {
        int points = packed.length / stride;
        boolean[] keep = new boolean[points];
        if (points < 3) {
            Arrays.fill(keep, true);
            return keep;
        }
        keep[0] = true;
        keep[points - 1] = true;
        int[] ranges = new int[2 * points];
        int pending = 0;
        ranges[pending++] = 0;
        ranges[pending++] = points - 1;
        while (pending > 0) {
            int last = ranges[--pending];
            int first = ranges[--pending];
            double chordSquared = 0.0;
            for (int axis = 0; axis < stride; axis++) {
                double chord = packed[last * stride + axis] - packed[first * stride + axis];
                chordSquared += chord * chord;
            }
            float farthest = 0f;
            int farthestPoint = -1;
            for (int point = first + 1; point < last; point++) {
                double dot = 0.0;
                for (int axis = 0; axis < stride; axis++) {
                    dot += (packed[point * stride + axis] - packed[first * stride + axis])
                            * (double) (packed[last * stride + axis] - packed[first * stride + axis]);
                }
                double along = chordSquared == 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, dot / chordSquared));
                double squared = 0.0;
                for (int axis = 0; axis < stride; axis++) {
                    double offset = packed[point * stride + axis] - packed[first * stride + axis]
                            - along * (packed[last * stride + axis] - packed[first * stride + axis]);
                    squared += offset * offset;
                }
                float distance = (float) Math.sqrt(squared);
                if (distance > farthest) {
                    farthest = distance;
                    farthestPoint = point;
                }
            }
            if (farthestPoint >= 0 && farthest > tolerance) {
                keep[farthestPoint] = true;
                ranges[pending++] = first;
                ranges[pending++] = farthestPoint;
                ranges[pending++] = farthestPoint;
                ranges[pending++] = last;
            }
        }
        return keep;
    }
}
