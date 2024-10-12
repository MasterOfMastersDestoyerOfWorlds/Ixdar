package shell.render.color;

import org.joml.Vector3f;
import org.joml.Vector4f;

public interface Color {
    public static final Color WHITE = new ColorRGB(1f, 1f, 1f, "White");
    public static final Color BLACK = new ColorRGB(0f, 0f, 0f, "Black");
    public static final Color RED = new ColorRGB(1f, 0f, 0f);
    public static final Color GREEN = new ColorRGB(0f, 1f, 0f);
    public static final Color BLUE = new ColorRGB(0f, 0f, 1f);
    public static final Color NAVY = new ColorRGB(5, 37, 53);
    public static final Color BLUE_WHITE = new ColorRGB(98, 142, 166);
    public static final Color CYAN = new ColorRGB(0, 255, 255);
    public static final Color IXDAR = new ColorRGB(150, 0, 36);
    public static final Color IXDAR_DARK = new ColorRGB(51, 1, 13);
    public static final Color TRANSPARENT = new ColorRGB(0, 0, 0, 0);
    public static final Color YELLOW = new ColorRGB(0, 255, 255);
    public static final Color MAGENTA = new ColorRGB(1f, 0f, 1f);
    public static final Color LIGHT_GRAY = new ColorRGB(0.3f, 0.3f, 0.3f);
    public static final Color ORANGE = new ColorRGB(210, 105, 30);

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
}
