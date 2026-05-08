package ixdar.geometry.mesh.curve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Float curve: control points (x,y), piecewise linear interpolation in x.
 * Matches the common case of {@code _build_closure_with_curve} mapping samples.
 */
public final class FloatCurveKernel {
    public static final int NUM_4 = 4;

    private final float[] xs;
    private final float[] ys;

    /**
     * TODO: document {@code FloatCurveKernel}.
     *
     * @param xs TODO: describe
     * @param ys TODO: describe
     * @throws IllegalArgumentException TODO: describe
     */
    public FloatCurveKernel(float[] xs, float[] ys) {
        if (xs == null || ys == null || xs.length != ys.length || xs.length < 2) {
            throw new IllegalArgumentException("Float curve needs at least two control points");
        }
        this.xs = xs.clone();
        this.ys = ys.clone();
        sortByX();
    }

    /**
     * TODO: document {@code fromCommaSeparatedPairs}.
     *
     * @param raw TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public static FloatCurveKernel fromCommaSeparatedPairs(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty curve points");
        }
        String[] parts = raw.split(",");
        if (parts.length < NUM_4 || parts.length % 2 != 0) {
            throw new IllegalArgumentException("Expected comma-separated x,y pairs");
        }
        int n = parts.length / 2;
        float[] x = new float[n];
        float[] y = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = Float.parseFloat(parts[i * 2].trim());
            y[i] = Float.parseFloat(parts[i * 2 + 1].trim());
        }
        return new FloatCurveKernel(x, y);
    }

    private void sortByX() {
        List<Integer> order = new ArrayList<>(xs.length);
        for (int i = 0; i < xs.length; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> xs[i]));
        float[] nx = new float[xs.length];
        float[] ny = new float[ys.length];
        for (int i = 0; i < order.size(); i++) {
            int j = order.get(i);
            nx[i] = xs[j];
            ny[i] = ys[j];
        }
        System.arraycopy(nx, 0, xs, 0, xs.length);
        System.arraycopy(ny, 0, ys, 0, ys.length);
    }

    /**
     * Map {@code factor} along curve x-axis to y (float curve evaluation at a given factor).
     *
     * @param factor TODO: describe
     * @return TODO: describe
     */
    public float evaluate(float factor) {
        if (factor <= xs[0]) {
            return ys[0];
        }
        int last = xs.length - 1;
        if (factor >= xs[last]) {
            return ys[last];
        }
        for (int i = 0; i < last; i++) {
            float x0 = xs[i];
            float x1 = xs[i + 1];
            if (factor >= x0 && factor <= x1) {
                float t = (factor - x0) / (x1 - x0);
                return ys[i] + t * (ys[i + 1] - ys[i]);
            }
        }
        return ys[last];
    }
}
