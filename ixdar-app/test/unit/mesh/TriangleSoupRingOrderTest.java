package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.TriangleSoup;

/**
 * TriangleSoup.oneRings builds its rings on flat arrays in exactly the order the
 * {@code HashSet<Integer>} it replaced iterated them, since the curvature sums and the crest,
 * saddle and Morse-Smale walks follow that order (CRAW-87).
 */
public final class TriangleSoupRingOrderTest {

    private static final int SEED = 87;

    private static final int SOUPS = 40;

    private static final int TRIANGLES = 400;

    // A fan this wide grows the set's table three times and fills buckets past the long-bin limit.
    private static final int FAN = 150;

    // Multiples of 16 and 64 share buckets in the set's first tables.
    private static final int[] ID_SPREADS = { 1, 16, 64, 1024 };

    // Ids above 2^16 exercise the hash's high-half fold.
    private static final int[] ID_OFFSETS = { 0, 1 << 17 };

    @Test
    void ringsComeOutInTheHashSetOrder() {
        Random random = new Random(SEED);
        for (int soup = 0; soup < SOUPS; soup++) {
            int spread = ID_SPREADS[soup % ID_SPREADS.length];
            int offset = ID_OFFSETS[soup / ID_SPREADS.length % ID_OFFSETS.length];
            int vertices = 2 + random.nextInt(60);
            int[] triangles = new int[3 * (TRIANGLES + FAN)];
            for (int corner = 0; corner < 3 * TRIANGLES; corner++) {
                // Some triangles repeat a corner, as degenerate scan triangles do.
                triangles[corner] = offset + random.nextInt(vertices) * spread;
            }
            for (int blade = 0; blade < FAN; blade++) {
                int at = 3 * (TRIANGLES + blade);
                triangles[at] = offset;
                triangles[at + 1] = offset + (1 + blade) * spread;
                triangles[at + 2] = offset + (2 + blade) * spread;
            }
            int bound = offset + (FAN + 3) * spread;

            // The reference: the HashSet rings TriangleSoup built before CRAW-87.
            List<HashSet<Integer>> neighbours = new ArrayList<>(bound);
            for (int vertex = 0; vertex < bound; vertex++) {
                neighbours.add(new HashSet<>(TriangleSoup.NEIGHBOUR_SET_ORDER_FIXING_CAPACITY));
            }
            for (int corner = 0; corner < triangles.length; corner++) {
                int triangle = corner / 3 * 3;
                for (int other = 0; other < 3; other++) {
                    if (triangle + other != corner) {
                        neighbours.get(triangles[corner]).add(triangles[triangle + other]);
                    }
                }
            }
            int[][] expected = new int[bound][];
            for (int vertex = 0; vertex < bound; vertex++) {
                expected[vertex] = neighbours.get(vertex).stream().mapToInt(Integer::intValue).toArray();
            }
            assertArrayEquals(expected, TriangleSoup.oneRings(triangles, bound),
                    "soup " + soup + ", ids spread by " + spread + " from " + offset);
        }
    }
}
