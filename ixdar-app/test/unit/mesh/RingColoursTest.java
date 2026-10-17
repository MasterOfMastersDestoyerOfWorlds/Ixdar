package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.color.Palettes;
import ixdar.scenes.regions.RegionColouring;
import ixdar.scenes.ring.RingTool;

/**
 * The ring tool's colours that carry a meaning, the draft modes (red geodesic, purple crease) and
 * the marked ring, read apart from each other and from every ring palette entry; palettes wrap and
 * shade past their end.
 */
class RingColoursTest {

    @Test
    void markedRingAndPaletteStayClearOfTheDraftModes() {
        Color[] meanings = { RingTool.GEODESIC_DRAFT_COLOR, RingTool.CREASE_DRAFT_COLOR,
            RingTool.MARKED_RING_COLOR };
        for (int first = 0; first < meanings.length; first++) {
            for (int second = first + 1; second < meanings.length; second++) {
                assertTrue(meanings[first].distanceTo(meanings[second])
                        >= RegionColouring.MINIMUM_PALETTE_DISTANCE,
                        meanings[first].getName() + " vs " + meanings[second].getName());
            }
            // Palette entries only cycle by ring number, so they are not checked against each other.
            for (Color entry : Palettes.RING.colors) {
                assertTrue(meanings[first].distanceTo(entry)
                        >= RegionColouring.MINIMUM_PALETTE_DISTANCE,
                        meanings[first].getName() + " vs " + entry.getName());
            }
        }
    }

    @Test
    void palettesWrapAroundAndShadeTheirOverflow() {
        Palette ring = Palettes.RING;
        assertSame(ring.color(0), ring.color(ring.size()));
        assertSame(ring.color(ring.size() - 1), ring.color(-1));
        assertSame(Color.RED_PINK_LIGHT, Palettes.PATCH.color(0));
        assertEquals(Palettes.REGION.color(0).toVector4f(), Palettes.REGION.shadedColor(0).toVector4f());
        Color overflow = Palettes.REGION.shadedColor(Palettes.REGION.size());
        assertEquals(Palettes.REGION.color(0).shaded(Palette.OVERFLOW_SHADE).toVector4f(),
                overflow.toVector4f());
        assertEquals(0.0, Color.WHITE.distanceTo(Color.WHITE_25), 1e-9);
        assertEquals(Math.sqrt(3.0), Color.BLACK.distanceTo(Color.WHITE), 1e-6);
    }
}
