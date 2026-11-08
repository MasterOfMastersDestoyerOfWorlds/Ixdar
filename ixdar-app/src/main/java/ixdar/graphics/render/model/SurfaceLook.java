package ixdar.graphics.render.model;

import ixdar.graphics.render.color.Palette;

/**
 * One way a mesh surface is drawn, declared in one line where a scene offers it, as a control
 * is: its id, what it shows, where its colour comes from, how it is lit and the palette it wears.
 */
public final class SurfaceLook {

    /** Where a fragment's colour comes from; the ordinal is {@code mesh.fs}'s colourSource. */
    public enum Colour {
        /** The face groups' colours a tool set, else the solid surface colour. */
        SURFACE,
        /** The model's own base-colour texture, the surface colour where it has none. */
        TEXTURE,
        /** Light grey clay. */
        CLAY,
        /** A ramp from cavity to ridge over the curvature the fragment shader measures. */
        CURVATURE,
        /** The look's per-vertex field through the palette's scalar ramp. */
        FIELD
    }

    /** How the colour is lit; the ordinal is {@code mesh.fs}'s lighting. */
    public enum Light {
        /** One light along the view direction plus a rim glow. */
        VIEW,
        /** A key, fill and sky light that follow the camera. */
        CLAY,
        /** The clay light over a floor, so a ramp's colours stay readable in shadow. */
        SOFT_CLAY
    }

    /** The values of a field look, read when the look is shown on a surface. */
    @FunctionalInterface
    public interface FieldSource {

        /**
         * The field on the shown surface.
         *
         * @return one value per active vertex in active order, or {@code null} while there is
         *         nothing to show
         */
        float[] values();
    }

    /** Id M's row, {@code ixdar-cli shading}, multiview and other code know the look by. */
    public final String id;

    /** One line saying what the look shows. */
    public final String description;

    /** Where the colour comes from. */
    public final Colour colour;

    /** How it is lit. */
    public final Light light;

    /** The palette the scene wears while the look shows, {@code null} for the scene's own. */
    public final Palette palette;

    /** The field a {@link Colour#FIELD} look shows, {@code null} for any other. */
    public final FieldSource field;

    /** Field value at the ramp's low end; NaN with {@link #fieldMax} scales to the field's own range. */
    public final float fieldMin;

    /** Field value at the ramp's high end; NaN scales to the field's own range. */
    public final float fieldMax;

    /**
     * A look in one line.
     *
     * @param id          id it is known by
     * @param description what it shows
     * @param colour      where its colour comes from; a field look takes its field instead
     * @param light       how it is lit
     * @param palette     the palette the scene wears with it, {@code null} for the scene's own
     */
    public SurfaceLook(String id, String description, Colour colour, Light light, Palette palette) {
        this(id, description, colour, light, palette, null, Float.NaN, Float.NaN);
    }

    /**
     * A field look in one line: the field through the scalar ramp, in the scene's own palette.
     *
     * @param id          id it is known by
     * @param description what the field is
     * @param light       how it is lit
     * @param field       its values, read when the look is shown
     */
    public SurfaceLook(String id, String description, Light light, FieldSource field) {
        this(id, description, Colour.FIELD, light, null, field, Float.NaN, Float.NaN);
    }

    /**
     * A field look over a fixed range, so a value means the same colour on every surface: a signed
     * field from minus to plus the same bound puts zero mid-ramp.
     *
     * @param id          id it is known by
     * @param description what the field is
     * @param light       how it is lit
     * @param field       its values, read when the look is shown
     * @param fieldMin    value at the ramp's low end; lower values clamp to it
     * @param fieldMax    value at the ramp's high end; higher values clamp to it
     */
    public SurfaceLook(String id, String description, Light light, FieldSource field,
            float fieldMin, float fieldMax) {
        this(id, description, Colour.FIELD, light, null, field, fieldMin, fieldMax);
    }

    private SurfaceLook(String id, String description, Colour colour, Light light, Palette palette,
            FieldSource field, float fieldMin, float fieldMax) {
        this.id = id;
        this.description = description;
        this.colour = colour;
        this.light = light;
        this.palette = palette;
        this.field = field;
        this.fieldMin = fieldMin;
        this.fieldMax = fieldMax;
    }
}
