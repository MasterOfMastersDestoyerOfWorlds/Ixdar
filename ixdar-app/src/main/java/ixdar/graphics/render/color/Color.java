package ixdar.graphics.render.color;

import org.joml.Vector3f;
import org.joml.Vector4f;

public interface Color {
    public static final Color WHITE = new ColorRGB(1f, 1f, 1f, "White");
    public static final Color BLACK = new ColorRGB(0f, 0f, 0f, "Black");
    public static final Color RED = new ColorRGB(1f, 0f, 0f, "Red");
    public static final Color RED_FAINT = new ColorRGB(0.25f, 0f, 0f, 0.1f, "Red Faint");
    public static final Color GREEN = new ColorRGB(0f, 1f, 0f, "Green");
    public static final Color BLUE = new ColorRGB(0f, 0f, 1f, "Blue");
    public static final Color LIGHT_NAVY = new ColorRGB(15, 45, 135, "Light Navy");
    public static final Color NAVY = new ColorRGB(5, 37, 53, "Navy");
    public static final Color BLUE_GRAY = new ColorRGB(32, 35, 70, "Blue Gray");
    public static final Color BLUE_WHITE = new ColorRGB(98, 142, 166, "Blue White");
    public static final Color CYAN = new ColorRGB(0, 255, 255, "Cyan");
    public static final Color IXDAR = new ColorRGB(150, 0, 36, "Ixdar");
    public static final Color DARK_IXDAR = new ColorRGB(51, 1, 13, "Dark Ixdar");
    public static final Color TRANSPARENT = new ColorRGB(0, 0, 0, 0, "Transparent");
    public static final Color TRANSPARENT25 = new ColorRGB(0, 0, 0, 0.25f, "Transparent 25");
    public static final Color TRANSPARENT50 = new ColorRGB(0, 0, 0, 0.5f, "Transparent 50");
    public static final Color TRANSPARENT75 = new ColorRGB(0, 0, 0, 0.75f, "Transparent 75");
    public static final Color YELLOW = new ColorRGB(255, 255, 0, "Yellow");
    public static final Color LIGHT_PURPLE = new ColorRGB(167, 147, 238, "Light Purple");
    public static final Color DARK_PURPLE = new ColorRGB(32, 35, 36, "Dark Purple");
    public static final Color PURPLE = new ColorRGB(125, 49, 123, "Purple");
    public static final Color MAGENTA = new ColorRGB(1f, 0f, 1f, "Magenta");
    public static final Color LIGHT_GRAY = new ColorRGB(0.3f, 0.3f, 0.3f, "Light Gray");
    public static final Color DARK_GRAY = new ColorRGB(0.15f, 0.15f, 0.15f, "Dark Gray");
    public static final Color DARK_RED = new ColorRGB(0.7f, 0.1f, 0.1f, 1.0f, "Dark Red");
    public static final Color ORANGE = new ColorRGB(210, 105, 30, "Orange");
    public static final Color COMMAND = new ColorRGB(0f, 0.75f, 0.5f, "Command");
    public static final Color PINK = new ColorRGB(255, 192, 203, "Pink");
    public static final Color SOFT_RED = new ColorRGB(1f, 0.25f, 0.25f, "Soft Red");
    public static final Color BRIGHT_ORANGE = new ColorRGB(1f, 0.55f, 0.1f, "Bright Orange");
    public static final Color AMBER = new ColorRGB(1f, 0.8f, 0f, "Amber");
    public static final Color GOLD = new ColorRGB(1f, 0.85f, 0.1f, "Gold");
    public static final Color BRIGHT_GREEN = new ColorRGB(0.2f, 1f, 0.4f, "Bright Green");
    public static final Color SKY_BLUE = new ColorRGB(0.2f, 0.85f, 1f, "Sky Blue");
    public static final Color AZURE = new ColorRGB(0.25f, 0.5f, 1f, "Azure");
    public static final Color GLSL_VECTOR = new ColorRGB(0.72f, 0.58f, 0.85f, "GLSL Vector");
    public static final Color GLSL_COMMA = new ColorRGB(0.80f, 0.86f, 0.96f, "GLSL Comma");
    public static final Color GLSL_PARENTHESIS = new ColorRGB(0.88f, 0.90f, 0.96f, "GLSL Parenthesis");
    public static final Color GLSL_FLOAT = new ColorRGB(0.98f, 0.74f, 0.80f, "GLSL Float");
    public static final Color GLSL_VECTOR_FLOAT_X = new ColorRGB(0.99f, 0.80f, 0.80f, "GLSL Vector Float X");
    public static final Color GLSL_VECTOR_FLOAT_Y = new ColorRGB(0.80f, 0.96f, 0.80f, "GLSL Vector Float Y");
    public static final Color GLSL_VECTOR_FLOAT_Z = new ColorRGB(0.80f, 0.86f, 0.99f, "GLSL Vector Float Z");
    public static final Color GLSL_VECTOR_FLOAT_W = new ColorRGB(0.98f, 0.88f, 0.70f, "GLSL Vector Float W");
    public static final Color GLSL_BRACE = new ColorRGB(0.88f, 0.95f, 1.0f, "GLSL Brace");
    public static final Color GLSL_KEYWORD = new ColorRGB(0.90f, 0.80f, 0.60f, "GLSL Keyword");
    public static final Color GLSL_TYPE = new ColorRGB(0.60f, 0.85f, 0.90f, "GLSL Type");
    public static final Color GLSL_FUNCTION = new ColorRGB(0.75f, 0.85f, 0.65f, "GLSL Function");
    public static final Color GLSL_VECN = new ColorRGB(0.85f, 0.70f, 0.95f, "GLSL VecN");
    public static final Color GLSL_OPERATOR = new ColorRGB(0.95f, 0.85f, 0.55f, "GLSL Operator");
    public static final Color GLSL_EQUALS = new ColorRGB(0.90f, 0.95f, 0.55f, "GLSL Equals");
    public static final Color GLSL_BOOLEAN = new ColorRGB(0.70f, 0.90f, 0.60f, "GLSL Boolean");
    public static final Color GLSL_SKIP = new ColorRGB(0.98f, 0.96f, 0.70f, "GLSL Skip");

    /**
     * RGB channels of this color as a {@code (r, g, b)} vector.
     *
     * @return the color as a vec3
     */
    public Vector3f toVector3f();

    /**
     * RGBA channels of this color as a {@code (r, g, b, a)} vector.
     *
     * @return the color as a vec4
     */
    public Vector4f toVector4f();

    /**
     * Convert HSB (hue/saturation/brightness) to an RGB {@link ColorRGB}.
     * Mirrors {@link java.awt.Color#HSBtoRGB(float, float, float)} but returns
     * a {@code Color} instance directly.
     *
     * @param hue hue in [0, 1); fractional turns are taken modulo 1
     * @param saturation saturation in [0, 1]
     * @param brightness brightness in [0, 1]
     * @return new RGB color with 0–255 channels
     */
    public static Color HSBtoRGB(float hue, float saturation, float brightness) {
        int r = 0, g = 0, b = 0;
        if (saturation == 0) {
            r = g = b = (int) (brightness * 255.0f + 0.5f);
        } else {
            float h = (hue - (float) Math.floor(hue)) * 6.0f;
            float f = h - (float) Math.floor(h);
            float p = brightness * (1.0f - saturation);
            float q = brightness * (1.0f - saturation * f);
            float t = brightness * (1.0f - (saturation * (1.0f - f)));
            switch ((int) h) {
            case 0:
                r = (int) (brightness * 255.0f + 0.5f);
                g = (int) (t * 255.0f + 0.5f);
                b = (int) (p * 255.0f + 0.5f);
                break;
            case 1:
                r = (int) (q * 255.0f + 0.5f);
                g = (int) (brightness * 255.0f + 0.5f);
                b = (int) (p * 255.0f + 0.5f);
                break;
            case 2:
                r = (int) (p * 255.0f + 0.5f);
                g = (int) (brightness * 255.0f + 0.5f);
                b = (int) (t * 255.0f + 0.5f);
                break;
            case 3:
                r = (int) (p * 255.0f + 0.5f);
                g = (int) (q * 255.0f + 0.5f);
                b = (int) (brightness * 255.0f + 0.5f);
                break;
            case 4:
                r = (int) (t * 255.0f + 0.5f);
                g = (int) (p * 255.0f + 0.5f);
                b = (int) (brightness * 255.0f + 0.5f);
                break;
            case 5:
                r = (int) (brightness * 255.0f + 0.5f);
                g = (int) (p * 255.0f + 0.5f);
                b = (int) (q * 255.0f + 0.5f);
                break;
            }
        }
        return new ColorRGB(r, g, b);
    }

    /**
     * Convenience wrapper around {@link #HSBtoRGB(float, float, float)} that
     * rewraps the result as a {@link ColorRGB}, matching the
     * {@code java.awt.Color.getHSBColor} signature for callers porting AWT code.
     *
     * @param h hue in [0, 1)
     * @param s saturation in [0, 1]
     * @param b brightness in [0, 1]
     * @return new RGB color
     */
    public static Color getHSBColor(float h, float s, float b) {
        return new ColorRGB(HSBtoRGB(h, s, b));
    }

    /**
     * Display name for diagnostics, color pickers, and debug overlays.
     *
     * @return human-readable color name
     */
    public String getName();
}
