package ixdar.geometry.mesh.data.paths;

import java.util.Locale;

/**
 * How a ring runs between its authored anchors: the smooth geodesic spline, or the cheapest path
 * over the surface's crease cost, which hugs the groove the anchors sit in.
 */
public enum RingSegmentMode {

    GEODESIC("geodesic"),

    CREASE("crease");

    /** The value a {@code spline_ring} statement's {@code mode=} argument spells it as. */
    public final String dslName;

    /** The name the ring tool's controls and rows show. */
    public final String label;

    RingSegmentMode(String label) {
        this.dslName = name().toLowerCase(Locale.ROOT);
        this.label = label;
    }

    /**
     * The mode a {@code mode=} argument names; an absent or empty one is geodesic.
     *
     * @param dslName the argument's text, or {@code null}
     * @throws IllegalArgumentException when the text names no mode
     * @return the mode
     */
    public static RingSegmentMode named(String dslName) {
        if (dslName == null || dslName.isBlank()) {
            return GEODESIC;
        }
        for (RingSegmentMode mode : values()) {
            if (mode.dslName.equals(dslName)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown ring segment mode \"" + dslName
                + "\"; use geodesic or crease");
    }
}
