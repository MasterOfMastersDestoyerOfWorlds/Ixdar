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

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.color.Palettes;
import ixdar.graphics.render.color.RegionColouring;

/**
 * Patch colours come from palette cycles: the patch cycle gives every id its own colour, the
 * categories hand neighbouring patches contrasting entries through {@link RegionColouring}, and the
 * scalar heat map's shader holds as many stops as every palette's scalar ramp.
 */
class PatchColoursTest {

    private static final Path SCALAR_SHADER = Path.of("src/main/resources/glsl/mesh_scalar.fs");

    private static final Pattern RAMP_STOPS = Pattern.compile("const int RAMP_STOPS = (\\d+);");

    private static final int ROUNDS = 3;

    private static final double MINIMUM_BRIGHT_DISTANCE = 0.15;

    // A wheel: hub 0 joined to every rim node 1..5, the rim a cycle.
    private static final int[][] WHEEL = { { 1, 2, 3, 4, 5 }, { 0, 2, 5 }, { 0, 1, 3 },
        { 0, 2, 4 }, { 0, 3, 5 }, { 0, 4, 1 } };

    private static final int[] WHEEL_ENTRIES = { 0, 1, 2, 3, 4, 5 };

    @Test
    void everyPatchIdTakesItsOwnColour() {
        Color[] patch = Palettes.DARK.patch;
        assertTrue(minimumDistance(patch) > 0, "two patch entries are the same colour");
        Set<String> seen = new HashSet<>();
        for (int patchId = 0; patchId < ROUNDS * patch.length; patchId++) {
            assertTrue(seen.add(Palette.shadedEntry(patch, patchId).toVector4f().toString()),
                    "patch " + patchId + " repeats an earlier patch's colour");
        }
    }

    @Test
    void categoryEntriesAreApartAndConsecutiveOnesContrast() {
        Color[] categories = Palettes.DARK.categories;
        assertTrue(minimumDistance(categories) >= MINIMUM_BRIGHT_DISTANCE);
        for (int entry = 0; entry < categories.length; entry++) {
            assertTrue(Palette.entry(categories, entry).distanceTo(Palette.entry(categories, entry + 1))
                    >= RegionColouring.MINIMUM_PALETTE_DISTANCE,
                    "entries " + entry + " and " + (entry + 1));
        }
    }

    @Test
    void graphColouringSeparatesNeighboursAndKeepsKeptNodes() {
        int[] sizes = new int[WHEEL.length];
        RegionColouring colouring = new RegionColouring();
        colouring.colour(WHEEL, sizes, null, null, Palettes.DARK.categories);
        assertArrayEquals(WHEEL_ENTRIES, colouring.colourByNode);
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
        colouring.colour(reversed, sizes, former, kept, Palettes.DARK.categories);
        for (int node = 0; node < WHEEL.length; node++) {
            assertEquals(WHEEL_ENTRIES[former[node]], colouring.colourByNode[node]);
        }
    }

    @Test
    void scalarShaderHoldsTheRampPalette() throws Exception {
        Matcher stops = RAMP_STOPS.matcher(Files.readString(SCALAR_SHADER, StandardCharsets.UTF_8));
        assertTrue(stops.find(), "no RAMP_STOPS constant in " + SCALAR_SHADER);
        for (Palette palette : Palettes.OPTIONS) {
            assertEquals(palette.scalar.length, Integer.parseInt(stops.group(1)), palette.name);
        }
    }

    /**
     * The smallest RGB distance between any two entries of a cycle.
     *
     * @param cycle palette cycle to measure
     * @return the minimum pairwise distance, infinite for fewer than two entries
     */
    private static double minimumDistance(Color[] cycle) {
        double minimum = Double.POSITIVE_INFINITY;
        for (int first = 0; first < cycle.length; first++) {
            for (int second = first + 1; second < cycle.length; second++) {
                minimum = Math.min(minimum, cycle[first].distanceTo(cycle[second]));
            }
        }
        return minimum;
    }
}
