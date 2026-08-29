package ixdar.geometry.mesh.quadlayout.solver.ordering;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.IncrementalCholeskySolver;
import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;

/**
 * Fill-reducing permutations for the SPD systems factored by
 * {@link DirectSolver} and {@link IncrementalCholeskySolver}, selected through
 * {@link OrderingMethod}: identity, reverse Cuthill-McKee, or approximate
 * minimum degree.
 */
public final class SolverPermutation {

    /** Placeholder off-diagonal value; the ordering reads only the pattern, not the values. */
    private static final double PATTERN_ENTRY = 1.0;

    private SolverPermutation() {
    }

    /**
     * Build the adjacency list of the compact (free-only) submatrix. Each
     * entry holds the off-diagonal neighbours of a free variable in compact
     * index space — exactly what {@link #reverseCuthillMcKee(int[][])} and
     * {@link #amdOrdering(int[][])} consume.
     *
     * <p>For the all-free case (no constraints), pass an all-{@code false}
     * {@code fixed} mask, identity {@code compactOf}, and {@code freeCount
     * == matrix.size()}.
     *
     * @param matrix    full symmetric system matrix A
     * @param fixed     mask of held-fixed variables
     * @param compactOf full-index → compact-index lookup (or {@code -1} for
     *                  fixed rows)
     * @param freeCount number of free variables (size of the compact problem)
     * @return per-free-variable list of free-variable neighbours
     *         (off-diagonal only)
     */
    public static int[][] buildAdjacency(NormalMatrix matrix,
            boolean[] fixed,
            int[] compactOf,
            int freeCount) {
        int n = matrix.size();
        int[] degree = new int[freeCount];
        for (int i = 0; i < n; i++) {
            if (fixed[i]) {
                continue;
            }
            int u = compactOf[i];
            for (int c = matrix.rowStart(i); c < matrix.rowEnd(i); c++) {
                int col = matrix.column(c);
                if (!fixed[col] && col != i) {
                    degree[u]++;
                }
            }
        }
        int[][] adj = new int[freeCount][];
        for (int u = 0; u < freeCount; u++) {
            adj[u] = new int[degree[u]];
        }
        int[] cursor = new int[freeCount];
        for (int i = 0; i < n; i++) {
            if (fixed[i]) {
                continue;
            }
            int u = compactOf[i];
            for (int c = matrix.rowStart(i); c < matrix.rowEnd(i); c++) {
                int col = matrix.column(c);
                if (!fixed[col] && col != i) {
                    adj[u][cursor[u]++] = compactOf[col];
                }
            }
        }
        return adj;
    }

    /**
     * Compute a fill-reducing permutation by the requested method.
     *
     * @param matrix    full symmetric system matrix A
     * @param fixed     mask of held-fixed variables; for the all-free case
     *                  pass an all-{@code false} array
     * @param compactOf full-index → compact-index lookup; pass identity for
     *                  the all-free case
     * @param freeCount number of free variables (size of the compact problem)
     * @param ordering  which ordering to compute
     * @throws IllegalStateException if a future enum value is added and not
     *                               wired into this dispatcher
     * @return {@code perm[newIndex] = oldIndex}, length {@code freeCount}
     */
    public static int[] computePermutation(NormalMatrix matrix,
            boolean[] fixed,
            int[] compactOf,
            int freeCount,
            OrderingMethod ordering) {
        if (ordering == OrderingMethod.NATURAL) {
            int[] identity = new int[freeCount];
            for (int i = 0; i < freeCount; i++) {
                identity[i] = i;
            }
            return identity;
        }
        int[][] adj = buildAdjacency(matrix, fixed, compactOf, freeCount);
        return switch (ordering) {
            case RCM -> reverseCuthillMcKee(adj);
            case AMD -> amdOrdering(adj);
            default -> throw new IllegalStateException("unreachable: " + ordering);
        };
    }

    /**
     * Approximate-minimum-degree fill-reducing ordering of the compact free-variable subgraph, via
     * the SuiteSparse {@link AMDOrdering} port the seamless stage uses. The adjacency is packed into
     * a pattern-only {@link NormalMatrix} — the ordering reads only the non-zero pattern — and
     * handed to {@link AMDOrdering#order}.
     *
     * @param adj per-vertex neighbour lists for the compact problem, entries in the same index space
     * @return {@code perm[newIndex] = oldIndex}, length {@code adj.length}
     */
    public static int[] amdOrdering(int[][] adj) {
        int n = adj.length;
        Map<Long, Double> upper = new HashMap<>();
        for (int vertex = 0; vertex < n; vertex++) {
            for (int neighbour : adj[vertex]) {
                if (neighbour > vertex) {
                    upper.put(((long) vertex << NormalMatrix.KEY_ROW_SHIFT) | neighbour, PATTERN_ENTRY);
                }
            }
        }
        NormalMatrix pattern = new NormalMatrix(new double[n], upper, new double[n]);
        AMDOrdering ordering = new AMDOrdering();
        ordering.order(pattern);
        return ordering.permutation;
    }

    /**
     * Reverse Cuthill-McKee ordering. BFS from a minimum-degree start,
     * sorting each level's neighbours by ascending degree, then reverse
     * the resulting sequence. Disconnected graphs are handled by restarting
     * from the next unvisited minimum-degree vertex.
     *
     * @param adj per-vertex neighbour lists for the compact problem
     * @return {@code perm[newIndex] = oldIndex}, length {@code adj.length}
     */
    public static int[] reverseCuthillMcKee(int[][] adj) {
        int n = adj.length;
        int[] perm = new int[n];
        boolean[] visited = new boolean[n];
        int filled = 0;

        while (filled < n) {
            int start = -1;
            int minDeg = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!visited[i] && adj[i].length < minDeg) {
                    minDeg = adj[i].length;
                    start = i;
                }
            }

            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            visited[start] = true;
            while (!queue.isEmpty()) {
                int u = queue.poll();
                perm[filled++] = u;
                int[] neighbours = adj[u].clone();
                Integer[] boxed = new Integer[neighbours.length];
                for (int i = 0; i < neighbours.length; i++) {
                    boxed[i] = neighbours[i];
                }
                Arrays.sort(boxed, (a, b) -> adj[a].length - adj[b].length);
                for (int v : boxed) {
                    if (!visited[v]) {
                        visited[v] = true;
                        queue.add(v);
                    }
                }
            }
        }

        int[] reversed = new int[n];
        for (int i = 0; i < n; i++) {
            reversed[i] = perm[n - 1 - i];
        }
        return reversed;
    }

}
