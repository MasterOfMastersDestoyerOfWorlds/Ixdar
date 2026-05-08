package ixdar.common.utils;

/**
 * Tiny shims for JDK helpers that may not be available on every supported
 * runtime / language level. Provides null-safe equivalents and a fallback
 * fused-multiply-add.
 */
public final class Compat {

    private Compat() {
    }

    /**
     * Null-safe equivalent of {@code String.isBlank()}.
     *
     * @param s string to test (may be {@code null})
     * @return {@code true} if {@code s} is null, empty, or whitespace-only
     */
    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Null-safe equivalent of {@code String.stripTrailing()}.
     *
     * @param s string to strip (may be {@code null})
     * @return {@code s} with trailing whitespace removed, or {@code null} if {@code s} is null
     */
    public static String stripTrailing(String s) {
        if (s == null)
            return null;
        return s.replaceAll("\\s+$", "");
    }

    /**
     * Float fused-multiply-add fallback computing {@code a * b + c} without
     * relying on {@code Math.fma}. Note: not IEEE-754 fused; rounds twice.
     *
     * @param a first multiplicand
     * @param b second multiplicand
     * @param c addend
     * @return {@code a * b + c}
     */
    public static float fmaf(float a, float b, float c) {
        return a * b + c;
    }
}
