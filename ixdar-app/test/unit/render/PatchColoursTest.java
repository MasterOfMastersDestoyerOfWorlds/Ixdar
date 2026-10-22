package unit.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import ixdar.graphics.render.color.Palettes;
import ixdar.graphics.render.color.RegionColouring;

/**
 * Patch colours come from palettes of named colours: {@link Palettes#PATCH} gives every id its
 * own colour, {@link Palettes#BRIGHT_PATCH} hands neighbouring patches contrasting entries through
 * {@link RegionColouring}, and the scalar heat map's shader holds {@link Palettes#SCALAR_RAMP}.
 */
class PatchColoursTest {

    private static final Path SCALAR_SHADER = Path.of("src/main/resources/glsl/mesh_scalar.fs");

    private static final Pattern RAMP_STOPS = Pattern.compile("const int RAMP_STOPS = (\\d+);");

    private static final int ROUNDS = 3;

    private static final double MINIMUM_BRIGHT_DISTANCE = 0.15;

    // A wheel: hub 0 joined to every rim node 1..5, the rim a cycle.
    private static final int[][] WHEEL = { { 1, 2, 3, 4, 5 }, { 0, 2, 5 }, { 0, 1, 3 },
        { 0, 2, 4 }, { 0, 3, 5 }, { 0, 4, 1 } };

    private static final int[] WHEEL_COLOURS = { 0, 1, 2, 3, 4, 5 };

    @Test
    void everyPatchIdTakesItsOwnColour() {
        assertTrue(Palettes.PATCH.minimumDistance() > 0, "two PATCH entries are the same colour");
        Set<String> seen = new HashSet<>();
        for (int patchId = 0; patchId < ROUNDS * Palettes.PATCH.size(); patchId++) {
            assertTrue(seen.add(Palettes.PATCH.shadedColor(patchId).toVector4f().toString()),
                    "patch " + patchId + " repeats an earlier patch's colour");
        }
    }

    @Test
    void brightPatchEntriesAreApartAndConsecutiveOnesContrast() {
        assertTrue(Palettes.BRIGHT_PATCH.minimumDistance() >= MINIMUM_BRIGHT_DISTANCE);
        for (int entry = 0; entry < Palettes.BRIGHT_PATCH.size(); entry++) {
            assertTrue(Palettes.BRIGHT_PATCH.color(entry).distanceTo(Palettes.BRIGHT_PATCH
                    .color(entry + 1)) >= RegionColouring.MINIMUM_PALETTE_DISTANCE,
                    "entries " + entry + " and " + (entry + 1));
        }
    }

    @Test
    void graphColouringSeparatesNeighboursAndKeepsKeptNodes() {
        int[] sizes = new int[WHEEL.length];
        RegionColouring colouring = new RegionColouring();
        colouring.colour(WHEEL, sizes, null, null, Palettes.BRIGHT_PATCH);
        assertArrayEquals(WHEEL_COLOURS, colouring.colourByNode);
        for (int node = 0; node < WHEEL.length; node++) {
            for (int neighbour : WHEEL[node]) {
                assertNotEquals(colouring.colourByNode[node], colouring.colourByNode[neighbour]);
            }
        }
        // Reversing the node order with every node kept carries each colour to its new index.
        int[][] reversed = new int[WHEEL.length][];
        int[] former = new int[WHEEL.length];
        boolean[] kept = new boolean[WHEEL.length];
        for (int node = 0; node < WHEEL.length; node++) {
            int old = WHEEL.length - 1 - node;
            former[node] = old;
            kept[node] = true;
            reversed[node] = new int[WHEEL[old].length];
            for (int index = 0; index < WHEEL[old].length; index++) {
                reversed[node][index] = WHEEL.length - 1 - WHEEL[old][index];
            }
        }
        colouring.colour(reversed, sizes, former, kept, Palettes.BRIGHT_PATCH);
        for (int node = 0; node < WHEEL.length; node++) {
            assertEquals(WHEEL_COLOURS[former[node]], colouring.colourByNode[node]);
        }
    }

    @Test
    void scalarShaderHoldsTheRampPalette() throws Exception {
        Matcher stops = RAMP_STOPS.matcher(Files.readString(SCALAR_SHADER, StandardCharsets.UTF_8));
        assertTrue(stops.find(), "no RAMP_STOPS constant in " + SCALAR_SHADER);
        assertEquals(Palettes.SCALAR_RAMP.size(), Integer.parseInt(stops.group(1)));
    }
}
