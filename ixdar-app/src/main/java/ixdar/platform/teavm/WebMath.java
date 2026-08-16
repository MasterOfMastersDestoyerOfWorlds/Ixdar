package ixdar.platform.teavm;

import java.math.BigDecimal;

/**
 * Pure-Java replacements for {@code java.lang.Math} methods TeaVM's classlib does not implement,
 * spliced into the browser build by {@link WebMathTransformer}.
 */
public final class WebMath {

    private WebMath() {
    }

    /**
     * Fused multiply-add: {@code a * b + c} rounded once, so the product's low bits survive.
     *
     * <p>With two roundings {@code fma(a, b, -a * b)} yields zero rather than the product's error
     * term, and the orientation predicates reading it stop being exact.
     *
     * @param a first factor
     * @param b second factor
     * @param c addend
     * @return the fused result
     */
    public static double fma(double a, double b, double c) {
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c)
                || Double.isInfinite(a) || Double.isInfinite(b) || Double.isInfinite(c)
                || a == 0.0 || b == 0.0) {
            return a * b + c;
        }
        return new BigDecimal(a).multiply(new BigDecimal(b)).add(new BigDecimal(c)).doubleValue();
    }

    /**
     * Single-precision fused multiply-add, computed in double where the product is exact.
     *
     * @param a first factor
     * @param b second factor
     * @param c addend
     * @return the fused result
     */
    public static float fma(float a, float b, float c) {
        return (float) ((double) a * (double) b + (double) c);
    }
}
