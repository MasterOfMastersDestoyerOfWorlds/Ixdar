package ixdar.geometry.mesh.data;

/**
 * A distinct colour per patch id, reported as {@code flat_color} so a pixel sampled from a
 * flat-shaded capture maps back to its patch.
 *
 * <p>Every colour has channel minimum 66 and maximum 215, never grey, so no backdrop outside
 * that band reads as a patch.
 */
public final class PatchColors {

    /** Reciprocal golden ratio, the hue step giving maximum pairwise separation. */
    public static final double GOLDEN_RATIO_CONJUGATE = 0.6180339887498949;

    /** Saturation every patch colour is generated at. */
    public static final float SATURATION = 0.65f;

    /** Lightness every patch colour is generated at. */
    public static final float LIGHTNESS = 0.55f;

    /** Mask isolating the three colour channels of a packed pixel. */
    public static final int RGB_MASK = 0xFFFFFF;

    /** Largest value a colour channel can hold. */
    public static final int CHANNEL_MAX = 255;

    /** Largest value a colour channel can hold, as a float scale factor. */
    public static final float CHANNEL_SCALE = 255f;

    /** Sextants the hue circle is divided into when converting HSL to RGB. */
    public static final float HUE_SEXTANTS = 6f;

    /** Bit offset of the red channel in a packed pixel. */
    public static final int RED_SHIFT = 16;

    /** Bit offset of the green channel in a packed pixel. */
    public static final int GREEN_SHIFT = 8;

    /** Format producing an uppercase six-digit hex triple. */
    public static final String HEX_FORMAT = "%06X";

    private PatchColors() {
    }

    /**
     * Globally-unique RGB for a patch id, stepping the hue by the golden ratio so any two ids are
     * as far apart in hue as possible.
     *
     * @param patchId the patch's id
     * @return packed {@code 0xRRGGBB} integer
     */
    public static int uniquePatchColor(int patchId) {
        float hue = (float) ((patchId * GOLDEN_RATIO_CONJUGATE) % 1.0);
        return hslToRgb(hue, SATURATION, LIGHTNESS);
    }

    /**
     * Hex string, without a leading hash, of {@link #uniquePatchColor(int)}.
     *
     * @param patchId the patch's id
     * @return uppercase six-digit hex of the RGB triple
     */
    public static String uniquePatchColorHex(int patchId) {
        return String.format(HEX_FORMAT, uniquePatchColor(patchId) & RGB_MASK);
    }

    /**
     * Convert an HSL triple to a packed RGB integer.
     *
     * @param hue hue in the range zero to one
     * @param saturation saturation in the range zero to one
     * @param lightness lightness in the range zero to one
     * @return packed {@code 0xRRGGBB} integer
     */
    private static int hslToRgb(float hue, float saturation, float lightness) {
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
        return (channel(red + lift) << RED_SHIFT)
                | (channel(green + lift) << GREEN_SHIFT)
                | channel(blue + lift);
    }

    /**
     * Round one normalised channel value to a byte, clamped into range.
     *
     * @param value the channel value, nominally zero to one
     * @return the channel as an integer from zero to {@value #CHANNEL_MAX}
     */
    private static int channel(float value) {
        return Math.max(0, Math.min(CHANNEL_MAX, Math.round(value * CHANNEL_SCALE)));
    }
}
