package ixdar.geometry.mesh.data;

/**
 * One PBR material for a whole {@link GeometryBundle}: decoded RGBA8 images plus the glTF metallic
 * roughness factors. Whole-bundle data, not per-element, so it is a plain value class rather than a
 * field type.
 */
public final class MaterialData {

    /**
     * Bundle slot the material rides. The only place this string is written; every reader and
     * writer goes through this constant.
     */
    public static final String SLOT = "_material";

    /** Components in {@link #baseColorFactor}: r, g, b, a. */
    public static final int FACTOR_COMPONENTS = 4;

    /**
     * Base-color (albedo) pixels, RGBA8, four bytes per pixel, rows bottom-up so the data can go
     * straight to GL. Null when the material has no base-color texture.
     */
    public final byte[] baseColorRgba;

    public final int baseColorWidth;

    public final int baseColorHeight;

    /**
     * Metallic-roughness pixels in the glTF packing (occlusion in R, roughness in G, metalness in
     * B), same layout as {@link #baseColorRgba}. Stored only; nothing samples it yet.
     */
    public final byte[] metallicRoughnessRgba;

    public final int metallicRoughnessWidth;

    public final int metallicRoughnessHeight;

    /** Base-color multiplier, {@code [r, g, b, a]}, defaulting to opaque white. */
    public final float[] baseColorFactor;

    public final float metallicFactor;

    public final float roughnessFactor;

    /**
     * Build a material from already-decoded images and factors; arrays are taken as given.
     *
     * @param baseColorRgba base-color RGBA8 pixels, or null when the material has no such texture
     * @param baseColorWidth base-color width in pixels, 0 when there is no texture
     * @param baseColorHeight base-color height in pixels, 0 when there is no texture
     * @param metallicRoughnessRgba metallic-roughness RGBA8 pixels, or null when absent
     * @param metallicRoughnessWidth metallic-roughness width in pixels, 0 when absent
     * @param metallicRoughnessHeight metallic-roughness height in pixels, 0 when absent
     * @param baseColorFactor four-component base-color multiplier
     * @param metallicFactor glTF {@code metallicFactor}
     * @param roughnessFactor glTF {@code roughnessFactor}
     * @throws IllegalArgumentException if {@code baseColorFactor} is not four components long
     */
    public MaterialData(
            byte[] baseColorRgba,
            int baseColorWidth,
            int baseColorHeight,
            byte[] metallicRoughnessRgba,
            int metallicRoughnessWidth,
            int metallicRoughnessHeight,
            float[] baseColorFactor,
            float metallicFactor,
            float roughnessFactor) {
        if (baseColorFactor == null || baseColorFactor.length != FACTOR_COMPONENTS) {
            throw new IllegalArgumentException("baseColorFactor must hold four components");
        }
        this.baseColorRgba = baseColorRgba;
        this.baseColorWidth = baseColorWidth;
        this.baseColorHeight = baseColorHeight;
        this.metallicRoughnessRgba = metallicRoughnessRgba;
        this.metallicRoughnessWidth = metallicRoughnessWidth;
        this.metallicRoughnessHeight = metallicRoughnessHeight;
        this.baseColorFactor = baseColorFactor;
        this.metallicFactor = metallicFactor;
        this.roughnessFactor = roughnessFactor;
    }

    /**
     * Whether this material can be sampled as a base-color texture.
     *
     * @return true when base-color pixels and non-zero dimensions are all present
     */
    public boolean hasBaseColorTexture() {
        return baseColorRgba != null && baseColorWidth > 0 && baseColorHeight > 0;
    }

    /**
     * The material a bundle carries.
     *
     * @param bundle bundle to read, may be null
     * @return the material in {@link #SLOT}, or null when the bundle has none
     */
    public static MaterialData of(GeometryBundle bundle) {
        if (bundle == null) {
            return null;
        }
        return bundle.slots().get(SLOT) instanceof MaterialData material ? material : null;
    }
}
