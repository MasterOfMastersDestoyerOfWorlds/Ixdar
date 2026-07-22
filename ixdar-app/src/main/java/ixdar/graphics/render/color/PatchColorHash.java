package ixdar.graphics.render.color;

/**
 * Java port of the {@code patchColor()} hash in {@code mesh_uv_traces.fs}, mapping an integer
 * id to a fixed-saturation HSV hue so Java-side overlays share the shader's palette.
 *
 * <p>Surface fill and layout overlay hash different id spaces, so a shared palette does not
 * imply a shared color for the same region.
 */
public final class PatchColorHash {

    /** HSV saturation used by the shader palette. */
    public static final float SATURATION = 0.55f;

    /** HSV value used by the shader palette. */
    public static final float VALUE = 0.85f;

    private static final int HASH_XOR = 0x27d4eb2d;
    private static final int HASH_MULTIPLIER = 0x9e3779b9;
    private static final int HASH_SHIFT = 16;
    private static final double UNSIGNED_RANGE = 4294967296.0;
    private static final float HUE_SECTORS = 6.0f;

    /**
     * Per hue sector, which of {zero, x, chroma} (encoded 0, 1, 2) feeds the
     * red, green, and blue channels — the standard HSV-to-RGB sector table.
     */
    private static final int[][] SECTOR_CHANNEL_SOURCES = {
            { 2, 1, 0 }, { 1, 2, 0 }, { 0, 2, 1 }, { 0, 1, 2 }, { 1, 0, 2 }, { 2, 0, 1 } };

    private PatchColorHash() {
    }

    /**
     * Deterministic per-patch color matching the GLSL {@code patchColor()}
     * hash.
     *
     * @param patchId patch identifier to hash
     * @param alpha   alpha channel of the returned color
     * @return RGBA color with hashed hue at the palette's saturation and value
     */
    public static Color colorForPatch(int patchId, float alpha) {
        int seed = patchId + 1;
        seed = (seed ^ HASH_XOR) * HASH_MULTIPLIER;
        seed ^= seed >>> HASH_SHIFT;
        float hue = (float) (Integer.toUnsignedLong(seed) / UNSIGNED_RANGE);
        float sector = hue * HUE_SECTORS;
        float chroma = VALUE * SATURATION;
        float x = chroma * (1.0f - Math.abs(sector % 2.0f - 1.0f));
        float[] sources = { 0.0f, x, chroma };
        int[] channelSources = SECTOR_CHANNEL_SOURCES[
                Math.min((int) sector, SECTOR_CHANNEL_SOURCES.length - 1)];
        float offset = VALUE - chroma;
        return new ColorRGB(
                sources[channelSources[0]] + offset,
                sources[channelSources[1]] + offset,
                sources[channelSources[2]] + offset,
                alpha);
    }
}
