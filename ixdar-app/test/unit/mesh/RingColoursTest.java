package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.color.Palettes;
import ixdar.tools.ring.RingTool;

/**
 * The ring tool's colours that carry a meaning, the draft modes and the marked ring, never appear
 * in the ring cycle of any palette nor match each other; cycles wrap and shade past their end.
 */
class RingColoursTest {

    private static final float QUARTER = 0.25f;

    @Test
    void ringCycleSharesNoEntryWithTheDraftModesOrTheMarkedRing() {
        for (Palette palette : Palettes.OPTIONS) {
            Color[] meanings = { palette.categories[RingTool.DRAFT_GEODESIC],
                palette.categories[RingTool.DRAFT_CREASE], palette.highlight };
            for (int first = 0; first < meanings.length; first++) {
                for (int second = first + 1; second < meanings.length; second++) {
                    assertNotEquals(meanings[first].toVector4f(), meanings[second].toVector4f(),
                            palette.name + ": " + meanings[first].getName() + " vs "
                                    + meanings[second].getName());
                }
                // Ring entries only cycle by ring number, so they are not checked against each other.
                for (Color entry : palette.ring) {
                    assertNotEquals(meanings[first].toVector4f(), entry.toVector4f(),
                            palette.name + ": " + meanings[first].getName() + " vs " + entry.getName());
                }
            }
        }
    }

    @Test
    void cyclesWrapAroundAndShadeTheirOverflow() {
        Color[] ring = Palettes.DARK.ring;
        assertSame(Palette.entry(ring, 0), Palette.entry(ring, ring.length));
        assertSame(Palette.entry(ring, ring.length - 1), Palette.entry(ring, -1));
        Color[] patch = Palettes.DARK.patch;
        assertSame(patch[patch.length - 1], Palette.entry(patch, 2 * patch.length - 1));
        Color[] region = Palettes.DARK.region;
        assertEquals(Palette.entry(region, 0).toVector4f(), Palette.shadedEntry(region, 0).toVector4f());
        Color overflow = Palette.shadedEntry(region, region.length);
        assertEquals(Palette.entry(region, 0).shaded(Palette.OVERFLOW_SHADE).toVector4f(),
                overflow.toVector4f());
        Color white = Color.fromVector4f(new Vector4f(1f, 1f, 1f, 1f));
        Color black = Color.fromVector4f(new Vector4f(0f, 0f, 0f, 1f));
        assertEquals(0.0, white.distanceTo(white.withAlpha(QUARTER)), 1e-9);
        assertEquals(Math.sqrt(3.0), black.distanceTo(white), 1e-6);
    }
}
