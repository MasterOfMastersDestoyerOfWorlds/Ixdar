package ixdar.geometry.mesh.data;

import ixdar.graphics.render.color.Color;

/**
 * A distinct colour per patch id, reported as {@code flat_color} so a pixel sampled from a
 * flat-shaded capture maps back to its patch.
 *
 * <p>Every colour has channel minimum 66 and maximum 215, never grey, so no backdrop outside
 * that band reads as a patch.
 */
public final class PatchColors {

    public static final double GOLDEN_RATIO_CONJUGATE = 0.6180339887498949;

    public static final float SATURATION = 0.65f;

    public static final float LIGHTNESS = 0.55f;

    private PatchColors() {
    }

    /**
     * Globally-unique colour for a patch id, stepping the hue by the golden ratio so any two ids
     * are as far apart in hue as possible.
     *
     * @param patchId the patch's id
     * @return the patch's opaque colour
     */
    public static Color uniquePatchColor(int patchId) {
        float hue = (float) ((patchId * GOLDEN_RATIO_CONJUGATE) % 1.0);
        return Color.fromHsl(hue, SATURATION, LIGHTNESS);
    }
}
