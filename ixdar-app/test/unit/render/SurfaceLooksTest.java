package unit.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.graphics.render.color.Palettes;
import ixdar.graphics.render.model.SurfaceLook;
import ixdar.graphics.render.model.SurfaceLook.Colour;
import ixdar.graphics.render.model.SurfaceLook.Light;
import ixdar.graphics.render.model.SurfaceLooks;

/**
 * A scene's looks: M cycles them in registration order, an id finds one in any case, an unknown
 * id is refused with the scene's list, and a tool's override draws in place of the pick and is
 * cleared only by the id that set it.
 */
public class SurfaceLooksTest {
    private static final String REGIONS = "regions";
    private static final String PARTS = "parts";
    private static final String DEFAULT = "default";
    private static final String CLAY = "clay";

    /**
     * The looks a ring scene offers, one line each as the scenes declare them.
     *
     * @return the registry, the first picked
     */
    private static SurfaceLooks ringSceneLooks() {
        SurfaceLooks looks = new SurfaceLooks();
        looks.registered.add(new SurfaceLook(DEFAULT, "the surface colour", Colour.SURFACE, Light.VIEW, null));
        looks.registered.add(new SurfaceLook("textured", "the model's texture", Colour.TEXTURE, Light.VIEW, null));
        looks.registered.add(new SurfaceLook(CLAY, "light grey clay", Colour.CLAY, Light.CLAY, Palettes.PAPER));
        looks.registered.add(new SurfaceLook(REGIONS, "each region in its colour", Colour.SURFACE, Light.VIEW, null));
        looks.registered.add(new SurfaceLook(PARTS, "each region in its part's colour", Colour.SURFACE, Light.VIEW, null));
        looks.picked = looks.registered.get(0);
        return looks;
    }

    /** M steps through every offered look in order and wraps to the first. */
    @Test
    public void cyclesInRegistrationOrder() {
        SurfaceLooks looks = ringSceneLooks();
        StringBuilder seen = new StringBuilder();
        for (int press = 0; press < looks.registered.size() + 1; press++) {
            looks.picked = looks.next();
            seen.append(looks.picked.id).append(' ');
        }
        assertEquals("textured clay regions parts default textured ", seen.toString());
    }

    /** An id finds its look whatever its case and surrounding space. */
    @Test
    public void findsLooksById() {
        SurfaceLooks looks = ringSceneLooks();
        assertEquals(CLAY, looks.named(" Clay ").id);
        assertSame(Palettes.PAPER, looks.named("CLAY").palette);
        assertTrue(new SurfaceLooks().registered.isEmpty());
    }

    /** An unknown id is refused, naming every look the scene offers. */
    @Test
    public void refusesUnknownIdsWithTheList() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> ringSceneLooks().named("crease"));
        assertTrue(refusal.getMessage().contains("default, textured, clay, regions, parts"),
                refusal.getMessage());
        assertTrue(refusal.getMessage().contains("'crease'"), refusal.getMessage());
    }

    /** A tool's override draws in place of the pick, and only the id that set it clears it. */
    @Test
    public void overrideIsClearedOnlyByItsOwnId() {
        SurfaceLooks looks = ringSceneLooks();
        looks.override(REGIONS);
        assertEquals(REGIONS, looks.shown().id);
        looks.clearOverride(PARTS);
        assertEquals(REGIONS, looks.shown().id, "another tool's clear leaves it");
        looks.clearOverride(REGIONS);
        assertNull(looks.override);
        assertSame(looks.picked, looks.shown());
    }

    /** Picking by id is what the region layer and the G key read. */
    @Test
    public void readsThePickById() {
        SurfaceLooks looks = ringSceneLooks();
        assertTrue(looks.isPicked(DEFAULT));
        looks.picked = looks.named(PARTS);
        assertTrue(looks.isPicked(PARTS));
        assertFalse(looks.isPicked(REGIONS));
    }

    /** A field look is a field look in one line, and reads its values only when asked. */
    @Test
    public void fieldLooksReadTheirValuesWhenShown() {
        int[] reads = new int[1];
        SurfaceLook field = new SurfaceLook("density", "a test field", Light.SOFT_CLAY, () -> {
            reads[0]++;
            return new float[] { 0f, 1f };
        });
        assertEquals(0, reads[0]);
        assertEquals(2, field.field.values().length);
        assertEquals(1, reads[0]);
        assertEquals(Colour.FIELD, field.colour);
        assertNull(field.palette);
    }
}
