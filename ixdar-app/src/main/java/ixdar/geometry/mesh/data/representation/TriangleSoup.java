package ixdar.geometry.mesh.data.representation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Adjacency read off a packed triangle index list, three vertex indices per triangle, for passes
 * that work on a mesh's triangles rather than its half-edges.
 */
public final class TriangleSoup {

    public static final int NEIGHBOUR_SET_ORDER_FIXING_CAPACITY = 6;

    // The table java.util.HashMap gives that capacity: the next power of two.
    public static final int NEIGHBOUR_SET_FIRST_TABLE = 8;

    // Entries a bucket may hold before the next would make java.util.HashMap treeify it or grow
    // the table out of turn (its TREEIFY_THRESHOLD less one), leaving the order rebuilt here.
    public static final int NEIGHBOUR_SET_LONG_BIN = 7;

    // java.util.HashMap folds a hash's high half onto its low half by this shift.
    public static final int HASH_SPREAD_SHIFT = 16;

    public static final int VERTICES_PER_TASK = 4096;

    private TriangleSoup() {
    }

    /**
     * Each edge's first two incident triangles, keyed by {@link EdgeKey#undirected}.
     *
     * @param triangles packed vertex indices, three per triangle
     * @return triangle indices per edge key, the second -1 on a boundary
     */
    public static Map<Long, int[]> edgeFaces(int[] triangles) {
        Map<Long, int[]> edgeFaces = new HashMap<>();
        for (int corner = 0; corner < triangles.length; corner++) {
            int triangle = corner / 3;
            int next = triangle * 3 + (corner + 1) % 3;
            long key = EdgeKey.undirected(triangles[corner], triangles[next]);
            int[] pair = edgeFaces.get(key);
            if (pair == null) {
                edgeFaces.put(key, new int[] { triangle, -1 });
            } else if (pair[1] == -1) {
                pair[1] = triangle;
            }
        }
        return edgeFaces;
    }

    /**
     * Each vertex's neighbours across the triangles' edges, each once, in the order a
     * {@code HashSet<Integer>} filled corner by corner iterates them, which callers' sums and
     * walks follow.
     *
     * @param triangles   packed vertex indices, three per triangle
     * @param vertexBound one past the largest vertex index
     * @return neighbour indices per vertex index, empty for one no triangle uses
     */
    public static int[][] oneRings(int[] triangles, int vertexBound) {
        // That order is rebuilt on flat arrays, vertices in parallel: the set's table starts at
        // NEIGHBOUR_SET_FIRST_TABLE buckets and doubles past three quarters full, iteration runs
        // bucket by bucket, and a bucket keeps its entries in the order they were added. A bucket
        // reaching NEIGHBOUR_SET_LONG_BIN entries would make the set restructure it, so that
        // vertex is read off a real set instead.
        // Every corner offers its vertex the triangle's two other corners, in corner order.
        int[] start = new int[vertexBound + 1];
        for (int vertex : triangles) {
            start[vertex + 1] += 2;
        }
        for (int vertex = 0; vertex < vertexBound; vertex++) {
            start[vertex + 1] += start[vertex];
        }
        int[] fill = Arrays.copyOf(start, vertexBound);
        int[] offered = new int[start[vertexBound]];
        for (int corner = 0; corner < triangles.length; corner++) {
            int triangle = corner / 3 * 3;
            for (int other = 0; other < 3; other++) {
                if (triangle + other != corner) {
                    offered[fill[triangles[corner]]++] = triangles[triangle + other];
                }
            }
        }
        int[][] rings = new int[vertexBound][];
        IntStream.range(0, (vertexBound + VERTICES_PER_TASK - 1) / VERTICES_PER_TASK).parallel()
                .forEach(task -> {
            int[] ring = new int[0];
            for (int vertex = task * VERTICES_PER_TASK;
                    vertex < Math.min(vertexBound, (task + 1) * VERTICES_PER_TASK); vertex++) {
                if (ring.length < start[vertex + 1] - start[vertex]) {
                    ring = new int[start[vertex + 1] - start[vertex]];
                }
                int size = 0;
                int table = NEIGHBOUR_SET_FIRST_TABLE;
                boolean longBin = false;
                for (int slot = start[vertex]; slot < start[vertex + 1] && !longBin; slot++) {
                    int neighbour = offered[slot];
                    int bucket = bucket(neighbour, table);
                    int sameBucket = 0;
                    boolean seen = false;
                    for (int kept = 0; kept < size && !seen; kept++) {
                        seen = ring[kept] == neighbour;
                        sameBucket += bucket(ring[kept], table) == bucket ? 1 : 0;
                    }
                    if (seen) {
                        continue;
                    }
                    longBin = sameBucket >= NEIGHBOUR_SET_LONG_BIN;
                    ring[size++] = neighbour;
                    if (size > table / 4 * 3) {
                        table *= 2;
                    }
                }
                if (longBin) {
                    HashSet<Integer> set = new HashSet<>(NEIGHBOUR_SET_ORDER_FIXING_CAPACITY);
                    for (int slot = start[vertex]; slot < start[vertex + 1]; slot++) {
                        set.add(offered[slot]);
                    }
                    rings[vertex] = set.stream().mapToInt(Integer::intValue).toArray();
                    continue;
                }
                // Bucket by bucket, each bucket's entries in the order they were added.
                for (int sorted = 1; sorted < size; sorted++) {
                    int neighbour = ring[sorted];
                    int bucket = bucket(neighbour, table);
                    int insert = sorted;
                    while (insert > 0 && bucket(ring[insert - 1], table) > bucket) {
                        ring[insert] = ring[insert - 1];
                        insert--;
                    }
                    ring[insert] = neighbour;
                }
                rings[vertex] = Arrays.copyOf(ring, size);
            }
        });
        return rings;
    }

    /**
     * The bucket a {@code HashSet<Integer>} files a value in: its hash, the value, with the high
     * half folded onto the low, masked to the table.
     *
     * @param value an entry
     * @param table the table's bucket count, a power of two
     * @return the bucket index
     */
    private static int bucket(int value, int table) {
        return (value ^ (value >>> HASH_SPREAD_SHIFT)) & (table - 1);
    }
}
