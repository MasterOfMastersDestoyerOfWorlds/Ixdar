package unit.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.color.Palettes;

/**
 * Every {@link Palette} in {@link Palettes} sets every field, is one of the options every scene steps
 * through, and keeps its text readable on its background and panels, so any scene can wear any palette.
 */
public class PaletteRolesTest {
    private static final double TEXT_CONTRAST = 4.5;
    private static final double MUTED_TEXT_CONTRAST = 2.0;
    private static final double TEXT_ROLE_CONTRAST = 2.5;
    private static final double LUMINANCE_OFFSET = 0.05;
    private static final int VECTOR_COMPONENTS = 4;

    /**
     * Every palette Palettes declares as a static field.
     *
     * @return each palette once, in declaration order
     * @throws IllegalAccessException never: the fields are public
     */
    private static List<Palette> declaredPalettes() throws IllegalAccessException {
        List<Palette> found = new ArrayList<>();
        for (Field field : Palettes.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.get(null) instanceof Palette palette) {
                found.add(palette);
            }
        }
        return found;
    }

    /**
     * No palette leaves a field unset or a cycle empty.
     *
     * @throws IllegalAccessException never: the fields are public
     */
    @Test
    public void everyPaletteSetsEveryField() throws IllegalAccessException {
        List<Palette> palettes = declaredPalettes();
        assertFalse(palettes.isEmpty());
        for (Palette palette : palettes) {
            for (Field field : Palette.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object value = field.get(palette);
                assertNotNull(value, palette.name + " " + field.getName());
                if (value instanceof Color[] cycle) {
                    assertTrue(cycle.length > 0, palette.name + " " + field.getName() + " is empty");
                    for (Color entry : cycle) {
                        assertNotNull(entry, palette.name + " " + field.getName() + " entry");
                    }
                }
            }
        }
    }

    /**
     * Every scene steps through the same options, and they are every palette Palettes declares, each
     * with its own name: there is no per-scene subset.
     *
     * @throws IllegalAccessException never: the fields are public
     */
    @Test
    public void everySceneCanWearEveryPalette() throws IllegalAccessException {
        List<Palette> palettes = declaredPalettes();
        assertEquals(new HashSet<>(palettes), new HashSet<>(Palettes.OPTIONS));
        assertEquals(palettes.size(), Palettes.OPTIONS.size());
        HashSet<String> names = new HashSet<>();
        for (Palette palette : Palettes.OPTIONS) {
            assertTrue(names.add(palette.name.toLowerCase()), "two palettes named " + palette.name);
        }
    }

    /**
     * Every palette's text and muted text read on its background and on its panels.
     *
     * @throws IllegalAccessException never: the fields are public
     */
    @Test
    public void everyPalettesTextReadsOnItsBackground() throws IllegalAccessException {
        for (Palette palette : declaredPalettes()) {
            for (Color ground : new Color[] { palette.background, palette.panel }) {
                assertTrue(contrast(palette.text, ground) >= TEXT_CONTRAST, palette.name + " text");
                assertTrue(contrast(palette.mutedText, ground) >= MUTED_TEXT_CONTRAST,
                        palette.name + " muted text");
            }
        }
    }

    /**
     * The other fields scenes draw words in (headings, links, the code pane's keywords and vector
     * components, status lines) read on the background too, whichever scene wears the palette.
     *
     * @throws IllegalAccessException never: the fields are public
     */
    @Test
    public void everyPalettesTextRolesReadOnItsBackground() throws IllegalAccessException {
        for (Palette palette : declaredPalettes()) {
            List<Color> roles = new ArrayList<>(List.of(palette.headingText, palette.linkText, palette.selected,
                    palette.accent, palette.highlight, palette.success, palette.warning, palette.error));
            for (int component = 0; component < VECTOR_COMPONENTS; component++) {
                roles.add(palette.categories[component]);
            }
            for (Color role : roles) {
                assertTrue(contrast(role, palette.background) >= TEXT_ROLE_CONTRAST,
                        palette.name + " " + role.getName() + " on the background");
            }
        }
    }

    /**
     * The WCAG contrast ratio of two colours, from 1 for the same luminance to 21 for black on white.
     *
     * @param a one colour
     * @param b the other
     * @return the ratio
     */
    private static double contrast(Color a, Color b) {
        double la = luminance(a.toVector4f());
        double lb = luminance(b.toVector4f());
        return (Math.max(la, lb) + LUMINANCE_OFFSET) / (Math.min(la, lb) + LUMINANCE_OFFSET);
    }

    /**
     * Relative luminance of sRGB channels.
     *
     * @param channels red, green and blue between 0 and 1
     * @return the luminance between 0 and 1
     */
    private static double luminance(Vector4f channels) {
        double[] linear = new double[3];
        float[] srgb = { channels.x, channels.y, channels.z };
        for (int k = 0; k < 3; k++) {
            linear[k] = srgb[k] <= 0.03928 ? srgb[k] / 12.92 : Math.pow((srgb[k] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    }
}
