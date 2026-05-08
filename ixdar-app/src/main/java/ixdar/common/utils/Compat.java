package ixdar.common.utils;

public final class Compat {

    private Compat() {
    }

    /**
     * TODO: document {@code isBlank}.
     *
     * @param s TODO: describe
     * @return TODO: describe
     */
    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * TODO: document {@code stripTrailing}.
     *
     * @param s TODO: describe
     * @return TODO: describe
     */
    public static String stripTrailing(String s) {
        if (s == null)
            return null;
        return s.replaceAll("\\s+$", "");
    }

    /**
     * TODO: document {@code fmaf}.
     *
     * @param a TODO: describe
     * @param b TODO: describe
     * @param c TODO: describe
     * @return TODO: describe
     */
    public static float fmaf(float a, float b, float c) {
        return a * b + c;
    }
}
