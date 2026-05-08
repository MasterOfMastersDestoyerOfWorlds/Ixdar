package ixdar.geometry.cuts;

import java.util.ArrayList;
import java.util.HashMap;

import ixdar.geometry.knot.Knot;

public class DisjointUnionSets {
    public HashMap<Integer, Integer> parent;
    public HashMap<Integer, Integer> unmatched;
    public int countGroups = 0;
    public int totalNumGroups = 0;
    HashMap<Integer, Integer> rank;

    // Constructor
    /**
     * Initialize one singleton group per knot point, each with two unmatched slots.
     *
     * @param knotPoints points to seed as their own groups
     */
    public DisjointUnionSets(ArrayList<Knot> knotPoints) {
        rank = new HashMap<>();
        parent = new HashMap<>();
        unmatched = new HashMap<>();
        for (int i = 0; i < knotPoints.size(); i++) {
            // Initially, all elements are in
            // their own set.
            int id = knotPoints.get(i).id;
            parent.put(id, id);
            unmatched.put(id, 2);
            countGroups++;
            totalNumGroups++;
        }
    }

    /**
     * Build an empty union-find with no initial groups; populate via {@link #addSet}.
     */
    public DisjointUnionSets() {
        rank = new HashMap<>();
        parent = new HashMap<>();
        unmatched = new HashMap<>();
    }

    /**
     * Test whether two knots share a group representative.
     *
     * @param k1 first knot
     * @param k2 second knot
     * @return {@code true} iff their {@code find} representatives match
     */
    public boolean sameGroup(Knot k1, Knot k2) {
        int k1Group = this.find(k1.id);
        int k2Group = this.find(k2.id);
        return k1Group == k2Group;
    }

    /**
     * Find the group representative for a knot by its id.
     *
     * @param k knot whose id is looked up
     * @return id of the representative root
     */
    public int find(Knot k) {
        return this.find(k.id);
    }

    // Returns representative of x's set
    /**
     * Find with path compression: returns the root id of {@code x} and
     * rewires every node along the way directly under that root.
     *
     * @param x element id
     * @return id of the root representative
     */
    public int find(int x) {
        // Finds the representative of the set
        // that x is an element of
        if (parent.get(x) != x) {
            // if x is not the parent of itself
            // Then x is not the representative of
            // his set,
            parent.put(x, find(parent.get(x)));

            // so we recursively call Find on its parent
            // and move i's node directly under the
            // representative of this set
        }

        return parent.get(x);
    }

    /**
     * Number of unmatched slots remaining on the root of {@code x}'s group.
     *
     * @param x element id
     * @return unmatched count stored at the group's root
     */
    public int findUnmatched(int x) {
        int xRoot = find(x);
        return unmatched.get(xRoot);
    }

    /**
     * Union by knot ids, decrementing the merged group's unmatched count by two.
     *
     * @param k1 first knot
     * @param k2 second knot
     * @return id of the resulting group's root
     */
    public int union(Knot k1, Knot k2) {
        return union(k1.id, k2.id);
    }

    // Unites the set that includes x and the set
    // that includes y
    /**
     * Union by rank: attaches the lower-rank root under the higher-rank root,
     * collapses the merged groups' unmatched counts (subtracting two for the
     * consumed slot pair), and decrements {@code countGroups}. When the two
     * elements already share a root, only the unmatched count is adjusted.
     *
     * @param x first element id
     * @param y second element id
     * @return id of the resulting root
     */
    public int union(int x, int y) {
        // Find representatives of two sets
        int xRoot = find(x), yRoot = find(y);

        // Elements are in the same set, no need
        // to unite anything.
        int yUnmatched = unmatched.get(yRoot);
        int xUnmatched = unmatched.get(xRoot);
        if (xRoot == yRoot) {
            unmatched.put(xRoot, xUnmatched - 2);
            return xRoot;
        }

        countGroups--;

        // If x's rank is less than y's rank
        if (rank.getOrDefault(xRoot, 0) < rank.getOrDefault(yRoot, 0)) {

            // Then move x under y so that depth
            // of tree remains less
            unmatched.put(yRoot, xUnmatched + yUnmatched - 2);
            parent.put(xRoot, yRoot);
            return yRoot;
        }
        // Else if y's rank is less than x's rank
        else if (rank.getOrDefault(yRoot, 0) < rank.getOrDefault(xRoot, 0)) {

            // Then move y under x so that depth of
            // tree remains less
            unmatched.put(xRoot, xUnmatched + yUnmatched - 2);
            parent.put(yRoot, xRoot);
            return xRoot;
        } else {
            // Then move y under x (doesn't matter
            // which one goes where)
            unmatched.put(xRoot, xUnmatched + yUnmatched - 2);
            parent.put(yRoot, xRoot);

            // And increment the result tree's
            // rank by 1
            rank.put(xRoot, rank.getOrDefault(xRoot, 0) + 1);
            return xRoot;
        }
    }

    /**
     * Add a knot as a fresh singleton group with two unmatched slots.
     *
     * @param k knot to register; its id becomes its own parent
     */
    public void addSet(Knot k) {
        parent.put(k.id, k.id);
        unmatched.put(k.id, 2);
        countGroups++;
        totalNumGroups++;
    }

    /**
     * Number of currently distinct groups (decreases on each union).
     *
     * @return live group count
     */
    public int countGroups() {
        return countGroups;
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