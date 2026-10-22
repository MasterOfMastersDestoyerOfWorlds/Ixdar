package ixdar.geometry.tsp.cuts;

import java.util.Arrays;

import ixdar.geometry.tsp.knot.Knot;
import ixdar.numeric.graph.UnionFind;

/**
 * The knot groups a cut-match search joins, as a {@link UnionFind} forest over knot ids merged by
 * rank, so a group's id is the id of its root knot.
 */
public class DisjointUnionSets {

    private static final int GROWTH = 2;

    /** Parent knot id per knot id; a group's root is its own parent. */
    public int[] parent = new int[0];

    /** Upper bound on each root's tree height, which decides which root a merge keeps. */
    public int[] rank = new int[0];

    /** Groups ever added, the bound callers size per-group buffers by. */
    public int totalNumGroups;

    /**
     * Find the group of a knot.
     *
     * @param k knot whose group is looked up
     * @return id of the group's root knot
     */
    public int find(Knot k) {
        return UnionFind.find(parent, k.id);
    }

    /**
     * Join the groups of two knots, the lower-ranked root moving under the higher, the second
     * knot's under the first's on a tie.
     *
     * @param k1 first knot
     * @param k2 second knot
     */
    public void union(Knot k1, Knot k2) {
        int firstRoot = find(k1);
        int secondRoot = find(k2);
        if (firstRoot == secondRoot) {
            return;
        }
        if (rank[firstRoot] < rank[secondRoot]) {
            parent[firstRoot] = secondRoot;
            return;
        }
        parent[secondRoot] = firstRoot;
        if (rank[firstRoot] == rank[secondRoot]) {
            rank[firstRoot]++;
        }
    }

    /**
     * Add a knot as a fresh singleton group.
     *
     * @param k knot to register; its id becomes its own parent
     */
    public void addSet(Knot k) {
        if (k.id >= parent.length) {
            int oldLength = parent.length;
            int newLength = Math.max(k.id + 1, oldLength * GROWTH);
            parent = Arrays.copyOf(parent, newLength);
            rank = Arrays.copyOf(rank, newLength);
            for (int id = oldLength; id < newLength; id++) {
                parent[id] = id;
            }
        }
        parent[k.id] = k.id;
        totalNumGroups++;
    }

    /**
     * Total number of groups ever introduced, used to size per-group buffers.
     *
     * @return cumulative count of {@code addSet} calls
     */
    public int totalNumGroups() {
        return totalNumGroups;
    }
}
