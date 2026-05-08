package ixdar.platform.gl;

/**
 * Desktop GL3 (LWJGL) shares shader files with WebGL2, which use {@code #version 300 es}.
 * macOS and typical GL core profiles compile desktop GLSL ({@code #version 330 core}), not ES.
 */
public final class GlslSource {
    public static final int NUM_16 = 16;

    private GlslSource() {}

    /**
     * Rewrite WebGL2-oriented sources for OpenGL 3.3 core: version line and ES-only precision.
     * Replaces a leading {@code #version 300 es} with {@code #version 330 core} and strips any
     * {@code precision ...;} declarations. Trailing NULs are stripped.
     *
     * @param source GLSL ES source text (may be null / empty)
     * @return adapted source for desktop core profile, or {@code source} unchanged if null/empty
     */
    public static String adaptEs300SharedForDesktopCore330(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        String normalized = source.charAt(source.length() - 1) == '\0'
                ? source.substring(0, source.length() - 1)
                : source;
        String[] lines = normalized.split("\\r\\n|\\n|\\r", -1);
        StringBuilder out = new StringBuilder(normalized.length() + NUM_16);
        boolean replacedVersion = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (!replacedVersion && trimmed.startsWith("#version")) {
                replacedVersion = true;
                if (trimmed.matches("(?i)#version\\s+300\\s+es\\s*")) {
                    out.append("#version 330 core");
                } else {
                    out.append(line);
                }
            } else if (trimmed.startsWith("precision ") && trimmed.endsWith(";")) {
                continue;
            } else {
                out.append(line);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Concatenate shader source fragments, skipping nulls and stripping a trailing NUL.
     *
     * @param parts source chunks (may contain nulls)
     * @return concatenated source, or {@code ""} when {@code parts} is null / empty
     */
    public static String joinChunks(CharSequence[] parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (CharSequence p : parts) {
            if (p != null) {
                b.append(p);
            }
        }
        String s = b.toString();
        if (!s.isEmpty() && s.charAt(s.length() - 1) == '\0') {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
