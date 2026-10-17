package ixdar.graphics.render.color;

import java.util.Random;

import org.joml.Vector3f;
import org.joml.Vector4f;

public interface Color {
    public static final float HUE_SEXTANTS = 6f;
    // Sorted by hue: neutrals black to white, then hue angle from red, ties by
    // lightness, alpha
    // variants (named BASE_<percent>) after their base. ColorOrderTest pins the
    // order.
    public static final Color BLACK = new ColorRGB(0f, 0f, 0f, "Black");
    public static final Color BLACK_75 = new ColorRGB(0, 0, 0, 0.75f, "Black 75");
    public static final Color BLACK_50 = new ColorRGB(0, 0, 0, 0.5f, "Black 50");
    public static final Color BLACK_25 = new ColorRGB(0, 0, 0, 0.25f, "Black 25");
    public static final Color TRANSPARENT = new ColorRGB(0, 0, 0, 0, "Transparent");
    public static final Color GREY_VERY_DARK = new ColorRGB(0.15f, 0.15f, 0.15f, "Grey Very Dark");
    public static final Color GREY_DARK = new ColorRGB(0.3f, 0.3f, 0.3f, "Grey Dark");
    public static final Color GREY_DARK_90 = new ColorRGB(0.3f, 0.3f, 0.3f, 0.9f, "Grey Dark 90");
    public static final Color GREY = new ColorRGB(0.42f, 0.42f, 0.42f, "Grey");
    public static final Color WHITE = new ColorRGB(1f, 1f, 1f, "White");
    public static final Color WHITE_25 = new ColorRGB(1f, 1f, 1f, 0.25f, "White 25");
    public static final Color RED_DARK = new ColorRGB(0.7f, 0.1f, 0.1f, "Red Dark");
    public static final Color RED = new ColorRGB(1f, 0f, 0f, "Red");
    public static final Color RED_50 = new ColorRGB(1f, 0f, 0f, 0.5f, "Red 50");
    public static final Color RED_LIGHT = new ColorRGB(1f, 0.25f, 0.25f, "Red Light");
    public static final Color RED_LIGHT_80 = new ColorRGB(1f, 0.25f, 0.25f, 0.8f, "Red Light 80");
    public static final Color PINK_PASTEL = new ColorRGB(0.99f, 0.80f, 0.80f, "Pink Pastel");
    public static final Color RED_VERY_LIGHT = new ColorRGB(255, 132, 124, "Red Very Light");
    public static final Color BROWN_RED = new ColorRGB(210, 111, 92, "Brown Red");
    public static final Color RED_ORANGE = new ColorRGB(255, 77, 26, "Red Orange");
    public static final Color ORANGE_LIGHT = new ColorRGB(248, 177, 149, "Orange Light");
    public static final Color BROWN_DARK = new ColorRGB(210, 105, 30, "Brown Dark");
    public static final Color ORANGE_VERY_LIGHT = new ColorRGB(254, 206, 171, "Orange Very Light");
    public static final Color BROWN_ORANGE = new ColorRGB(208, 138, 74, "Brown Orange");
    public static final Color ORANGE_DARK = new ColorRGB(178, 89, 0, "Orange Dark");
    public static final Color BROWN_LIGHT = new ColorRGB(233, 166, 99, "Brown Light");
    public static final Color ORANGE = new ColorRGB(255, 159, 10, "Orange");
    public static final Color ORANGE_PASTEL = new ColorRGB(243, 199, 122, "Orange Pastel");
    public static final Color YELLOW_ORANGE_VERY_LIGHT = new ColorRGB(0.98f, 0.88f, 0.70f, "Yellow Orange Very Light");
    public static final Color BROWN_PASTEL = new ColorRGB(0.90f, 0.80f, 0.60f, "Brown Pastel");
    public static final Color YELLOW_PASTEL = new ColorRGB(0.95f, 0.85f, 0.55f, "Yellow Pastel");
    public static final Color YELLOW_ORANGE = new ColorRGB(1f, 0.8f, 0f, "Yellow Orange");
    public static final Color YELLOW_VERY_LIGHT = new ColorRGB(0.98f, 0.96f, 0.70f, "Yellow Very Light");
    public static final Color YELLOW = new ColorRGB(255, 255, 0, "Yellow");
    public static final Color YELLOW_50 = new ColorRGB(1f, 1f, 0f, 0.5f, "Yellow 50");
    public static final Color YELLOW_LIGHT = new ColorRGB(232, 232, 160, "Yellow Light");
    public static final Color YELLOW_GREEN_PASTEL = new ColorRGB(0.90f, 0.95f, 0.55f, "Yellow Green Pastel");
    public static final Color GREEN_YELLOW = new ColorRGB(182, 255, 59, "Green Yellow");
    public static final Color GREEN_PASTEL = new ColorRGB(0.75f, 0.85f, 0.65f, "Green Pastel");
    public static final Color GREEN_YELLOW_LIGHT = new ColorRGB(0.70f, 0.90f, 0.60f, "Green Yellow Light");
    public static final Color GREEN_LIGHT = new ColorRGB(121, 233, 99, "Green Light");
    public static final Color GREEN_GREY_LIGHT = new ColorRGB(164, 195, 162, "Green Grey Light");
    public static final Color GREEN = new ColorRGB(0f, 1f, 0f, "Green");
    public static final Color GREEN_80 = new ColorRGB(0.2f, 1f, 0.2f, 0.8f, "Green 80");
    public static final Color GREEN_VERY_LIGHT = new ColorRGB(0.80f, 0.96f, 0.80f, "Green Very Light");
    public static final Color GREEN_TEAL = new ColorRGB(42, 223, 79, "Green Teal");
    public static final Color GREEN_TEAL_LIGHT = new ColorRGB(0.2f, 1f, 0.4f, "Green Teal Light");
    public static final Color TEAL_GREEN = new ColorRGB(0, 255, 128, "Teal Green");
    public static final Color TEAL_DARK = new ColorRGB(0f, 0.75f, 0.5f, "Teal Dark");
    public static final Color TEAL_PASTEL = new ColorRGB(182, 224, 212, "Teal Pastel");
    public static final Color TEAL_LIGHT = new ColorRGB(61, 232, 200, "Teal Light");
    public static final Color TEAL = new ColorRGB(0, 255, 255, "Teal");
    public static final Color TEAL_50 = new ColorRGB(0f, 1f, 1f, 0.5f, "Teal 50");
    public static final Color TEAL_VERY_LIGHT = new ColorRGB(77, 255, 255, "Teal Very Light");
    public static final Color TEAL_BLUE = new ColorRGB(0, 229, 255, "Teal Blue");
    public static final Color TEAL_BLUE_PASTEL = new ColorRGB(0.60f, 0.85f, 0.90f, "Teal Blue Pastel");
    public static final Color TEAL_BLUE_LIGHT = new ColorRGB(0.2f, 0.85f, 1f, "Teal Blue Light");
    public static final Color BLUE_TEAL = new ColorRGB(0, 200, 255, "Blue Teal");
    public static final Color GREY_BLUE_VERY_DARK = new ColorRGB(42, 54, 59, "Grey Blue Very Dark");
    public static final Color BLUE_TEAL_VERY_DARK = new ColorRGB(5, 37, 53, "Blue Teal Very Dark");
    public static final Color BLUE_GREY = new ColorRGB(98, 142, 166, "Blue Grey");
    public static final Color BLUE_VERY_LIGHT = new ColorRGB(0.88f, 0.95f, 1.0f, "Blue Very Light");
    public static final Color BLUE_GREY_VERY_LIGHT = new ColorRGB(143, 177, 203, "Blue Grey Very Light");
    public static final Color BLUE_GREY_DARK = new ColorRGB(53, 92, 125, "Blue Grey Dark");
    public static final Color BLUE_LIGHT = new ColorRGB(46, 155, 255, "Blue Light");
    public static final Color BLUE_TEAL_DARK = new ColorRGB(23, 115, 207, "Blue Teal Dark");
    public static final Color BLUE_PASTEL = new ColorRGB(0.7f, 0.85f, 1f, "Blue Pastel");
    public static final Color BLUE_GREY_LIGHT = new ColorRGB(123, 154, 204, "Blue Grey Light");
    public static final Color PURPLE_PASTEL = new ColorRGB(0.80f, 0.86f, 0.96f, "Purple Pastel");
    public static final Color BLUE_TEAL_LIGHT = new ColorRGB(0.25f, 0.5f, 1f, "Blue Teal Light");
    public static final Color BLUE_TEAL_LIGHT_80 = new ColorRGB(0.25f, 0.5f, 1f, 0.8f, "Blue Teal Light 80");
    public static final Color BLUE_DARK = new ColorRGB(15, 45, 135, "Blue Dark");
    public static final Color PURPLE_VERY_LIGHT = new ColorRGB(0.88f, 0.90f, 0.96f, "Purple Very Light");
    public static final Color BLUE_VERY_DARK = new ColorRGB(32, 35, 70, "Blue Very Dark");
    public static final Color BLUE_GREY_DARK_90 = new ColorRGB(0.4f, 0.4f, 0.5f, 0.9f, "Blue Grey Dark 90");
    public static final Color BLUE = new ColorRGB(0f, 0f, 1f, "Blue");
    public static final Color BLUE_GREY_LIGHT_90 = new ColorRGB(0.5f, 0.5f, 0.8f, 0.9f, "Blue Grey Light 90");
    public static final Color PURPLE_LIGHT = new ColorRGB(167, 147, 238, "Purple Light");
    public static final Color PURPLE = new ColorRGB(127, 0, 255, "Purple");
    public static final Color PURPLE_GREY_VERY_LIGHT = new ColorRGB(199, 181, 217, "Purple Grey Very Light");
    public static final Color PURPLE_BLUE_LIGHT = new ColorRGB(0.72f, 0.58f, 0.85f, "Purple Blue Light");
    public static final Color PURPLE_GREY = new ColorRGB(108, 91, 123, "Purple Grey");
    public static final Color PURPLE_PINK_VERY_LIGHT = new ColorRGB(0.85f, 0.70f, 0.95f, "Purple Pink Very Light");
    public static final Color PURPLE_BLUE = new ColorRGB(0.72f, 0.25f, 1f, "Purple Blue");
    public static final Color PURPLE_GREY_DARK = new ColorRGB(93, 78, 96, "Purple Grey Dark");
    public static final Color PURPLE_GREY_LIGHT = new ColorRGB(173, 140, 174, "Purple Grey Light");
    public static final Color PURPLE_PINK = new ColorRGB(1f, 0f, 1f, "Purple Pink");
    public static final Color PURPLE_PINK_50 = new ColorRGB(1f, 0f, 1f, 0.5f, "Purple Pink 50");
    public static final Color PURPLE_PINK_LIGHT = new ColorRGB(255, 77, 255, "Purple Pink Light");
    public static final Color PURPLE_DARK = new ColorRGB(125, 49, 123, "Purple Dark");
    public static final Color PINK = new ColorRGB(255, 0, 200, "Pink");
    public static final Color PINK_LIGHT = new ColorRGB(255, 64, 160, "Pink Light");
    public static final Color PINK_RED = new ColorRGB(255, 0, 128, "Pink Red");
    public static final Color PINK_RED_LIGHT = new ColorRGB(192, 108, 132, "Pink Red Light");
    public static final Color PINK_RED_VERY_LIGHT = new ColorRGB(231, 163, 182, "Pink Red Very Light");
    public static final Color IXDAR_DARK = new ColorRGB(51, 1, 13, "Dark Ixdar");
    public static final Color IXDAR = new ColorRGB(150, 0, 36, "Ixdar");
    public static final Color PINK_VERY_LIGHT = new ColorRGB(255, 192, 203, "Pink Very Light");
    public static final Color RED_PINK_LIGHT = new ColorRGB(232, 74, 95, "Red Pink Light");

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
     *
     * @param hue        hue in [0, 1); fractional turns are taken modulo 1
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
     * The fully saturated, full-brightness colour of a hue, the step of a
     * hue-rotated gradient.
     *
     * @param hue hue in [0, 1); fractional turns are taken modulo 1
     * @return new opaque RGB color
     */
    public static Color fullHue(float hue) {
        return HSBtoRGB(hue, 1f, 1f);
    }

    /**
     * Convert HSL (hue/saturation/lightness) to RGB, unrounded: the conversion
     * behind the patch colours, which step the hue by the golden ratio at a fixed
     * saturation and lightness.
     *
     * @param hue        hue in [0, 1)
     * @param saturation saturation in [0, 1]
     * @param lightness  lightness in [0, 1]
     * @return new opaque RGB color, each channel clamped into [0, 1]
     */
    public static Color fromHsl(float hue, float saturation, float lightness) {
        float chroma = (1f - Math.abs(2f * lightness - 1f)) * saturation;
        float sextant = hue * HUE_SEXTANTS;
        float second = chroma * (1f - Math.abs(sextant % 2f - 1f));
        float red = 0f;
        float green = 0f;
        float blue = 0f;
        if (sextant < 1f) {
            red = chroma;
            green = second;
        } else if (sextant < 2f) {
            red = second;
            green = chroma;
        } else if (sextant < 3f) {
            green = chroma;
            blue = second;
        } else if (sextant < 4f) {
            green = second;
            blue = chroma;
        } else if (sextant < 5f) {
            red = second;
            blue = chroma;
        } else {
            red = chroma;
            blue = second;
        }
        float lift = lightness - chroma * 0.5f;
        return new ColorRGB(red + lift, green + lift, blue + lift);
    }

    /**
     * The colour an {@code (r, g, b, a)} vector holds, the inverse of
     * {@link #toVector4f()}.
     *
     * @param rgba channels in [0, 1]
     * @return new RGBA color
     */
    public static Color fromVector4f(Vector4f rgba) {
        return new ColorRGB(rgba.x, rgba.y, rgba.z, rgba.w);
    }

    /**
     * A random opaque colour, each channel uniform in [0, 1).
     *
     * @param random source of the three channels
     * @return new RGB color
     */
    public static Color random(Random random) {
        return new ColorRGB(random.nextFloat(), random.nextFloat(), random.nextFloat());
    }

    /**
     * This colour with its alpha replaced.
     *
     * @param alpha replacement transparency in [0, 1]
     * @return new color with the same RGB channels
     */
    public default Color withAlpha(float alpha) {
        return new ColorRGB(this, alpha);
    }

    /**
     * This colour with its RGB channels scaled, darker for a factor below one.
     *
     * @param factor multiplier applied to red, green and blue; alpha is kept
     * @return new color
     */
    public default Color shaded(float factor) {
        Vector4f channels = toVector4f();
        return new ColorRGB(channels.x * factor, channels.y * factor, channels.z * factor, channels.w);
    }

    /**
     * Euclidean distance between this colour and another in RGB, alpha ignored: how
     * far apart two colours read, from 0 for the same colour to {@code sqrt(3)} for
     * black against white.
     *
     * @param other the colour to compare with
     * @return the RGB distance
     */
    public default double distanceTo(Color other) {
        Vector3f one = toVector3f();
        Vector3f two = other.toVector3f();
        double red = one.x - two.x;
        double green = one.y - two.y;
        double blue = one.z - two.z;
        return Math.sqrt(red * red + green * green + blue * blue);
    }

    /**
     * Display name for diagnostics, color pickers, and debug overlays.
     *
     * @return human-readable color name
     */
    public String getName();
}
