package shell.cuts;

import java.util.ArrayList;
import java.util.HashMap;

import shell.knot.VirtualPoint;

class DisjointUnionSets {
    HashMap<Integer, Integer> rank, parent;

    // Constructor
    public DisjointUnionSets(ArrayList<VirtualPoint> knotPoints) {
        rank = new HashMap<Integer, Integer>();
        parent = new HashMap<Integer, Integer>();
        for (int i = 0; i < knotPoints.size(); i++) {
            // Initially, all elements are in
            // their own set.
            int id = knotPoints.get(i).id;
            parent.put(id, id);
        }
    }

    // Returns representative of x's set
    int find(int x) {
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

    // Unites the set that includes x and the set
    // that includes x
    void union(int x, int y) {
        // Find representatives of two sets
        int xRoot = find(x), yRoot = find(y);

        // Elements are in the same set, no need
        // to unite anything.
        if (xRoot == yRoot)
            return;

        // If x's rank is less than y's rank
        if (rank.getOrDefault(xRoot, 0) < rank.getOrDefault(yRoot, 0))

            // Then move x under y so that depth
            // of tree remains less
            parent.put(xRoot, yRoot);

        // Else if y's rank is less than x's rank
        else if (rank.getOrDefault(yRoot, 0) < rank.getOrDefault(xRoot, 0))

            // Then move y under x so that depth of
            // tree remains less
            parent.put(yRoot, xRoot);

        else // if ranks are the same
        {
            // Then move y under x (doesn't matter
            // which one goes where)
            parent.put(yRoot, xRoot);

            // And increment the result tree's
            // rank by 1
            rank.put(xRoot, rank.getOrDefault(xRoot, 0) + 1);
        }
    }
}