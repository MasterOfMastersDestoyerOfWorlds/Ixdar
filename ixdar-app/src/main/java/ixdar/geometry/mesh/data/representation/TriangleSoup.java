package ixdar.geometry.mesh.data.representation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Adjacency read off a packed triangle index list, three vertex indices per triangle, for passes
 * that work on a mesh's triangles rather than its half-edges.
 */
public final class TriangleSoup {

    public static final int NEIGHBOUR_SET_ORDER_FIXING_CAPACITY = 6;

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
     * Each vertex's neighbours across the triangles' edges, each once.
     *
     * @param triangles   packed vertex indices, three per triangle
     * @param vertexBound one past the largest vertex index
     * @return neighbour indices per vertex index, empty for one no triangle uses
     */
    public static int[][] oneRings(int[] triangles, int vertexBound) {
        List<HashSet<Integer>> neighbours = new ArrayList<>(vertexBound);
        for (int vertex = 0; vertex < vertexBound; vertex++) {
            // The sets' starting capacity fixes their iteration order, which callers' walks follow.
            neighbours.add(new HashSet<>(NEIGHBOUR_SET_ORDER_FIXING_CAPACITY));
        }
        for (int corner = 0; corner < triangles.length; corner++) {
            int triangle = corner / 3 * 3;
            for (int other = 0; other < 3; other++) {
                if (triangle + other != corner) {
                    neighbours.get(triangles[corner]).add(triangles[triangle + other]);
                }
            }
        }
        int[][] rings = new int[vertexBound][];
        for (int vertex = 0; vertex < vertexBound; vertex++) {
            rings[vertex] = neighbours.get(vertex).stream().mapToInt(Integer::intValue).toArray();
        }
        return rings;
    }
}
