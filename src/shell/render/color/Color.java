package shell.render.color;

import org.joml.Vector3f;
import org.joml.Vector4f;

public interface Color {
    public static final Color WHITE = new ColorRGB(1f, 1f, 1f, "White");
    public static final Color BLACK = new ColorRGB(0f, 0f, 0f, "Black");
    public static final Color RED = new ColorRGB(1f, 0f, 0f, "Red");
    public static final Color GREEN = new ColorRGB(0f, 1f, 0f, "Green");
    public static final Color BLUE = new ColorRGB(0f, 0f, 1f, "Blue");
    public static final Color NAVY = new ColorRGB(5, 37, 53, "Navy");
    public static final Color BLUE_WHITE = new ColorRGB(98, 142, 166, "Blue White");
    public static final Color CYAN = new ColorRGB(0, 255, 255, "Cyan");
    public static final Color IXDAR = new ColorRGB(150, 0, 36, "Ixdar");
    public static final Color DARK_IXDAR = new ColorRGB(51, 1, 13, "Dark Ixdar");
    public static final Color TRANSPARENT = new ColorRGB(0, 0, 0, 0, "Transparent");
    public static final Color TRANSPARENT25 = new ColorRGB(0, 0, 0, 0.25f, "Transparent 25");
    public static final Color TRANSPARENT50 = new ColorRGB(0, 0, 0, 0.5f, "Transparent 50");
    public static final Color TRANSPARENT75 = new ColorRGB(0, 0, 0, 0.75f, "Transparent 75");
    public static final Color YELLOW = new ColorRGB(255, 255, 0, "Yellow");
    public static final Color DARK_PURPLE = new ColorRGB(32, 35, 36, "Dark Purple");
    public static final Color PURPLE = new ColorRGB(125, 49, 123, "Purple");
    public static final Color MAGENTA = new ColorRGB(1f, 0f, 1f, "Magenta");
    public static final Color LIGHT_GRAY = new ColorRGB(0.3f, 0.3f, 0.3f, "Light Gray");
    public static final Color DARK_GRAY = new ColorRGB(0.15f, 0.15f, 0.15f, "Dark Gray");
    public static final Color ORANGE = new ColorRGB(210, 105, 30, "Orange");
    public static final Color COMMAND = new ColorRGB(0f, 0.75f, 0.5f, "Command");

    public Vector3f toVector3f();

    public Vector4f toVector4f();

    public static Color HSBtoRGB(float hue, float saturation, float brightness) {
        int r = 0, g = 0, b = 0;
        if (saturation == 0) {
            r = g = b = (int) (brightness * 255.0f + 0.5f);
        } else {
            float h = (hue - (float) Math.floor(hue)) * 6.0f;
            float f = h - (float) java.lang.Math.floor(h);
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

    public static Color getHSBColor(float h, float s, float b) {
        return new ColorRGB(HSBtoRGB(h, s, b));
    }

    public String getName();
}
