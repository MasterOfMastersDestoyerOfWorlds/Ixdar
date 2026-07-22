package ixdar.geometry.mesh.quadlayout.solver;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Fill-reducing permutations for the SPD systems factored by
 * {@link DirectSolver} and {@link IncrementalCholeskySolver}, selected through
 * {@link OrderingMethod}: identity, reverse Cuthill-McKee, or approximate
 * minimum degree.
 */
public final class SolverPermutation {

    /** Initial-capacity multiplier for the AMD per-variable neighbour sets. */
    private static final int AMD_NEIGHBOUR_SET_GROWTH = 2;

    private SolverPermutation() {
    }

    /**
     * Build the adjacency list of the compact (free-only) submatrix. Each
     * entry holds the off-diagonal neighbours of a free variable in compact
     * index space — exactly what {@link #reverseCuthillMcKee(int[][])} and
     * {@link #approximateMinimumDegree(int[][])} consume.
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
     * @return {@code perm[newIndex] = oldIndex}, length {@code freeCount}
     * @throws IllegalStateException if a future enum value is added and not
     *                               wired into this dispatcher
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
            case AMD -> approximateMinimumDegree(adj);
            default -> throw new IllegalStateException("unreachable: " + ordering);
        };
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

    /**
     * Approximate minimum-degree ordering via quotient-graph element
     * absorption: at each step the variable of minimum approximate degree is
     * eliminated, and its surviving neighbours become a clique represented
     * implicitly by a new element that absorbs the eliminated variable's old
     * elements.
     *
     * <p>See also: Davis, Direct Methods for Sparse Linear Systems, Chapter 7
     *
     * @param adj per-vertex neighbour lists, length {@code n}; entries are
     *            old (input) indices
     * @return {@code perm[newIndex] = oldIndex}, length {@code adj.length}
     */
    @SuppressWarnings("unchecked")
    public static int[] approximateMinimumDegree(int[][] adj) {
        int n = adj.length;
        HashSet<Integer>[] variableNeighbours = new HashSet[n];
        HashSet<Integer>[] elementMembership = new HashSet[n];
        for (int i = 0; i < n; i++) {
            variableNeighbours[i] = new HashSet<>(adj[i].length * AMD_NEIGHBOUR_SET_GROWTH);
            for (int j : adj[i]) {
                variableNeighbours[i].add(j);
            }
            elementMembership[i] = new HashSet<>();
        }

        HashSet<Integer>[] elementVariables = new HashSet[n];
        boolean[] elementAlive = new boolean[n];
        boolean[] eliminated = new boolean[n];
        int[] approximateDegree = new int[n];
        for (int i = 0; i < n; i++) {
            approximateDegree[i] = variableNeighbours[i].size();
        }

        int[] perm = new int[n];
        for (int step = 0; step < n; step++) {
            int pivot = findMinimumDegree(eliminated, approximateDegree, n);
            perm[step] = pivot;
            eliminated[pivot] = true;

            HashSet<Integer> cliqueMembers = collectCliqueMembers(
                    pivot, variableNeighbours, elementMembership,
                    elementVariables, elementAlive, eliminated);

            int newElement = pivot;
            elementVariables[newElement] = cliqueMembers;
            elementAlive[newElement] = true;

            for (int member : cliqueMembers) {
                variableNeighbours[member].remove(pivot);
                elementMembership[member].add(newElement);
            }

            recomputeApproximateDegrees(cliqueMembers,
                    variableNeighbours, elementMembership,
                    elementVariables, elementAlive, approximateDegree);

            variableNeighbours[pivot] = null;
            elementMembership[pivot] = null;
        }
        return perm;
    }

    /**
     * Scan for the unvisited variable of minimum current approximate
     * degree. Ties are broken by ascending index (first found wins).
     *
     * @param eliminated         mask of already-eliminated variables
     * @param approximateDegree  current approximate degrees
     * @param n                  total variable count
     * @return index of the next variable to eliminate
     */
    private static int findMinimumDegree(boolean[] eliminated,
            int[] approximateDegree,
            int n) {
        int best = -1;
        int bestDegree = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (eliminated[i]) {
                continue;
            }
            int degree = approximateDegree[i];
            if (degree < bestDegree) {
                bestDegree = degree;
                best = i;
            }
        }
        return best;
    }

    /**
     * Form the clique created when {@code pivot} is eliminated: every alive
     * variable reachable through its neighbours or elements. Subsumed elements are
     * killed and unlinked from their members in place, so degree recomputation
     * never sees a dead id.
     *
     * @param pivot              the variable being eliminated
     * @param variableNeighbours per-variable variable-neighbour sets
     * @param elementMembership  per-variable element memberships
     * @param elementVariables   per-element variable lists
     * @param elementAlive       per-element liveness flag
     * @param eliminated         per-variable eliminated flag
     * @return the new clique's variable set (excluding the pivot)
     */
    private static HashSet<Integer> collectCliqueMembers(int pivot,
            HashSet<Integer>[] variableNeighbours,
            HashSet<Integer>[] elementMembership,
            HashSet<Integer>[] elementVariables,
            boolean[] elementAlive,
            boolean[] eliminated) {
        HashSet<Integer> clique = new HashSet<>(variableNeighbours[pivot].size() * AMD_NEIGHBOUR_SET_GROWTH);
        for (int neighbour : variableNeighbours[pivot]) {
            if (!eliminated[neighbour]) {
                clique.add(neighbour);
            }
        }
        for (int element : elementMembership[pivot]) {
            if (!elementAlive[element]) {
                continue;
            }
            for (int v : elementVariables[element]) {
                if (v != pivot && !eliminated[v]) {
                    clique.add(v);
                    elementMembership[v].remove(element);
                }
            }
            elementAlive[element] = false;
            elementVariables[element] = null;
        }
        return clique;
    }

    /**
     * Recompute the approximate degree of every variable in the new clique.
     * Approximate degree of variable {@code v} is bounded by the sum of
     * the sizes of its variable-neighbour set and its alive-element
     * memberships, with the variable itself excluded — this is the
     * classic "external degree" upper bound used by AMD.
     *
     * @param cliqueMembers      members of the just-formed clique
     * @param variableNeighbours per-variable variable-neighbour sets
     * @param elementMembership  per-variable element memberships
     * @param elementVariables   per-element variable lists
     * @param elementAlive       per-element liveness flag
     * @param approximateDegree  output: updated approximate degrees
     */
    private static void recomputeApproximateDegrees(HashSet<Integer> cliqueMembers,
            HashSet<Integer>[] variableNeighbours,
            HashSet<Integer>[] elementMembership,
            HashSet<Integer>[] elementVariables,
            boolean[] elementAlive,
            int[] approximateDegree) {
        for (int v : cliqueMembers) {
            int degree = variableNeighbours[v].size();
            for (int element : elementMembership[v]) {
                if (elementAlive[element]) {
                    degree += elementVariables[element].size() - 1;
                }
            }
            approximateDegree[v] = degree;
        }
    }
}
