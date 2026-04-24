package ixdar.geometry.mesh.data;

/**
 * Shared RGB constants for feature-edge overlays. Used by {@link PatchRenderer}'s
 * CPU PNG diagnostic and by the live GL viewer so the CPU screenshot and the
 * desktop overlay always show the same color story — change a color here and
 * both paths pick it up.
 *
 * <p>Values are 0x00RRGGBB with no alpha channel.
 */
public final class FeatureEdgeColors {

    // STAGES mode: colors per feature-edge source.
    public static final int DIHEDRAL       = 0x6AC8FF; // light blue
    public static final int PRINCIPAL      = 0xFFD700; // yellow
    public static final int CREST          = 0xFF3040; // red
    public static final int SADDLE         = 0x7B1FA2; // purple
    public static final int MULTI_SOURCE   = 0xFFFFFF; // white (edge in ≥2 sources)

    // CREST_VS_BOUNDARY mode: whether crest signals became patch boundaries.
    public static final int BOUNDARY_ONLY  = 0x000000; // black
    public static final int CREST_IGNORED  = 0xFF3040; // red
    public static final int CREST_HONORED  = 0x00E050; // green

    private FeatureEdgeColors() {}
}
