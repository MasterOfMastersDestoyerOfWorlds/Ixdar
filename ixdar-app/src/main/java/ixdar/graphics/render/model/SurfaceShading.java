package ixdar.graphics.render.model;

import java.util.Locale;

/**
 * How the lit mesh shader colours the surface. {@link #DEFAULT} is the viewer's own look;
 * {@link #CLAY} and {@link #CURVATURE} make shallow relief readable.
 * The ordinal is the {@code shadingStyle} uniform of {@code mesh.fs}.
 */
public enum SurfaceShading {
    /** The palette's surface colour under one light along the view direction, plus a rim glow. */
    DEFAULT,
    /** Light grey clay under a camera-relative key, fill and sky light. */
    CLAY,
    /** Clay lighting over a ramp that darkens concave and brightens convex surface. */
    CURVATURE;

    /**
     * The look a request names, case-insensitively.
     *
     * @param name a constant's name in any case
     * @return the named look
     * @throws IllegalArgumentException naming the accepted values when {@code name} is none of them
     */
    public static SurfaceShading named(String name) {
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "shading must be default, clay or curvature, not '" + name + "'", e);
        }
    }
}
