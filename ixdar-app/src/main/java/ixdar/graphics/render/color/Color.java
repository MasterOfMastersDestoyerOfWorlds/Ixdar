package ixdar.graphics.render.color;

import org.joml.Vector3f;
import org.joml.Vector4f;

public interface Color {
    public static final String NAVY_2 = "Navy";
    public static final float NUM_255_0 = 255.0f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_6_0 = 6.0f;
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final int NUM_5 = 5;
    public static final Color WHITE = new ColorRGB(1f, 1f, 1f, "White");
    public static final Color BLACK = new ColorRGB(0f, 0f, 0f, "Black");
    public static final Color RED = new ColorRGB(1f, 0f, 0f, "Red");
    public static final Color RED_FAINT = new ColorRGB(0.25f, 0f, 0f, 0.1f, "Red Faint");
    public static final Color GREEN = new ColorRGB(0f, 1f, 0f, "Green");
    public static final Color BLUE = new ColorRGB(0f, 0f, 1f, "Blue");
    public static final Color LIGHT_NAVY = new ColorRGB(15, 45, 135, NAVY_2);
    public static final Color NAVY = new ColorRGB(5, 37, 53, NAVY_2);
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
     * TODO: document {@code toVector3f}.
     *
     * @return TODO: describe
     */
    public Vector3f toVector3f();

    /**
     * TODO: document {@code toVector4f}.
     *
     * @return TODO: describe
     */
    public Vector4f toVector4f();

    /**
     * TODO: document {@code HSBtoRGB}.
     *
     * @param hue TODO: describe
     * @param saturation TODO: describe
     * @param brightness TODO: describe
     * @return TODO: describe
     */
    public static Color HSBtoRGB(float hue, float saturation, float brightness) {
        int r = 0, g = 0, b = 0;
        if (saturation == 0) {
            r = g = b = (int) (brightness * NUM_255_0 + NUM_0_5);
        } else {
            float h = (hue - (float) Math.floor(hue)) * NUM_6_0;
            float f = h - (float) java.lang.Math.floor(h);
            float p = brightness * (1.0f - saturation);
            float q = brightness * (1.0f - saturation * f);
            float t = brightness * (1.0f - (saturation * (1.0f - f)));
            switch ((int) h) {
            case 0:
                r = (int) (brightness * NUM_255_0 + NUM_0_5);
                g = (int) (t * NUM_255_0 + NUM_0_5);
                b = (int) (p * NUM_255_0 + NUM_0_5);
                break;
            case 1:
                r = (int) (q * NUM_255_0 + NUM_0_5);
                g = (int) (brightness * NUM_255_0 + NUM_0_5);
                b = (int) (p * NUM_255_0 + NUM_0_5);
                break;
            case 2:
                r = (int) (p * NUM_255_0 + NUM_0_5);
                g = (int) (brightness * NUM_255_0 + NUM_0_5);
                b = (int) (t * NUM_255_0 + NUM_0_5);
                break;
            case NUM_3:
                r = (int) (p * NUM_255_0 + NUM_0_5);
                g = (int) (q * NUM_255_0 + NUM_0_5);
                b = (int) (brightness * NUM_255_0 + NUM_0_5);
                break;
            case NUM_4:
                r = (int) (t * NUM_255_0 + NUM_0_5);
                g = (int) (p * NUM_255_0 + NUM_0_5);
                b = (int) (brightness * NUM_255_0 + NUM_0_5);
                break;
            case NUM_5:
                r = (int) (brightness * NUM_255_0 + NUM_0_5);
                g = (int) (p * NUM_255_0 + NUM_0_5);
                b = (int) (q * NUM_255_0 + NUM_0_5);
                break;
            }
        }
        return new ColorRGB(r, g, b);
    }

    /**
     * TODO: document {@code getHSBColor}.
     *
     * @param h TODO: describe
     * @param s TODO: describe
     * @param b TODO: describe
     * @return TODO: describe
     */
    public static Color getHSBColor(float h, float s, float b) {
        return new ColorRGB(HSBtoRGB(h, s, b));
    }

    /**
     * TODO: document {@code getName}.
     *
     * @return TODO: describe
     */
    public String getName();
}
