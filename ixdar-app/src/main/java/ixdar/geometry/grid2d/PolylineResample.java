package ixdar.geometry.grid2d;

/**
 * Even arc-length resampling of a polyline of any dimension: the samples split its length into
 * equal steps, wherever its own points fall.
 */
public final class PolylineResample {

    public static final float MIN_SEGMENT_LENGTH = 1e-10f;

    private PolylineResample() {
    }

    /**
     * Points evenly spaced by arc length along a polyline. Open, the first and last samples are its
     * ends exactly; closed, the closing segment counts and the first point is not repeated.
     *
     * @param packed  coordinates, {@code stride} floats per point
     * @param first   index of the polyline's first point in {@code packed}
     * @param points  how many points the polyline has, at least one
     * @param stride  floats per point
     * @param closed  whether the last point joins back to the first
     * @param samples how many points to return
     * @return {@code samples * stride} floats; every sample is the first point when the polyline
     *         has no length
     */
    public static float[] uniform(float[] packed, int first, int points, int stride, boolean closed, int samples) {
        int segments = closed ? points : points - 1;
        float[] lengthBefore = new float[segments + 1];
        for (int segment = 0; segment < segments; segment++) {
            int from = (first + segment) * stride;
            int to = (first + (segment + 1) % points) * stride;
            double squared = 0.0;
            for (int axis = 0; axis < stride; axis++) {
                float delta = packed[to + axis] - packed[from + axis];
                squared += delta * delta;
            }
            lengthBefore[segment + 1] = lengthBefore[segment] + (float) Math.sqrt(squared);
        }
        float total = lengthBefore[segments];
        float[] result = new float[samples * stride];
        int segment = 0;
        for (int sample = 0; sample < samples; sample++) {
            int out = sample * stride;
            boolean end = !closed && sample == samples - 1 && samples > 1;
            if (segments == 0 || sample == 0 || end) {
                System.arraycopy(packed, (first + (end ? points - 1 : 0)) * stride, result, out, stride);
                continue;
            }
            float target = closed ? total * sample / samples : total * sample / (samples - 1);
            while (segment < segments - 1 && lengthBefore[segment + 1] < target) {
                segment++;
            }
            float segmentLength = lengthBefore[segment + 1] - lengthBefore[segment];
            float along = segmentLength < MIN_SEGMENT_LENGTH ? 0f : (target - lengthBefore[segment]) / segmentLength;
            int from = (first + segment) * stride;
            int to = (first + (segment + 1) % points) * stride;
            for (int axis = 0; axis < stride; axis++) {
                result[out + axis] = packed[from + axis] + along * (packed[to + axis] - packed[from + axis]);
            }
        }
        return result;
    }
}
