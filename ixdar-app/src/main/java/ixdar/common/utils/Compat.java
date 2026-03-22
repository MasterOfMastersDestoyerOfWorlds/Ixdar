package ixdar.common.utils;

public final class Compat {

    private Compat() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String stripTrailing(String s) {
        if (s == null)
            return null;
        return s.replaceAll("\\s+$", "");
    }

    public static float fmaf(float a, float b, float c) {
        return a * b + c;
    }
}
