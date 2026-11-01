package ixdar.geometry.grid2d;

import java.util.Arrays;

/**
 * Douglas-Peucker simplification of a polyline (Cartographica 1973): keep the point farthest from
 * the chord between the kept ends while it lies beyond the tolerance, recursively.
 */
public final class PolylineSimplify {

    private PolylineSimplify() {
    }

    /**
     * The polyline's points that Douglas-Peucker keeps, its two ends always among them.
     *
     * @param xy        packed x then y; a closed outline repeats its first point last
     * @param tolerance largest distance a dropped point may lie from the kept polyline
     * @return the kept points, packed xy, in order
     */
    public static float[] douglasPeucker(float[] xy, float tolerance) {
        int points = xy.length / 2;
        if (points < 3) {
            return Arrays.copyOf(xy, points * 2);
        }
        boolean[] keep = new boolean[points];
        keep[0] = true;
        keep[points - 1] = true;
        int[] ranges = new int[2 * points];
        int pending = 0;
        ranges[pending++] = 0;
        ranges[pending++] = points - 1;
        while (pending > 0) {
            int last = ranges[--pending];
            int first = ranges[--pending];
            float chordX = xy[last * 2] - xy[first * 2];
            float chordY = xy[last * 2 + 1] - xy[first * 2 + 1];
            float chordSquared = chordX * chordX + chordY * chordY;
            float farthest = 0f;
            int farthestPoint = -1;
            for (int point = first + 1; point < last; point++) {
                float offsetX = xy[point * 2] - xy[first * 2];
                float offsetY = xy[point * 2 + 1] - xy[first * 2 + 1];
                float along = chordSquared == 0f ? 0f
                        : Math.max(0f, Math.min(1f, (offsetX * chordX + offsetY * chordY) / chordSquared));
                float distance = (float) Math.hypot(offsetX - along * chordX, offsetY - along * chordY);
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
        float[] kept = new float[points * 2];
        int written = 0;
        for (int point = 0; point < points; point++) {
            if (keep[point]) {
                kept[written++] = xy[point * 2];
                kept[written++] = xy[point * 2 + 1];
            }
        }
        return Arrays.copyOf(kept, written);
    }
}
