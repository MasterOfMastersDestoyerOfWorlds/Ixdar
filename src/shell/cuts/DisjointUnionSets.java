package shell.cuts;

import java.util.ArrayList;
import java.util.HashMap;

import shell.knot.Knot;

public class DisjointUnionSets {
    HashMap<Integer, Integer> rank, parent, unmatched;
    int countGroups = 0;
    int totalNumGroups = 0;

    // Constructor
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
    public DisjointUnionSets() {
        rank = new HashMap<>();
        parent = new HashMap<>();
        unmatched = new HashMap<>();
    }
    public boolean sameGroup(Knot k1, Knot k2) {
        int k1Group = this.find(k1.id);
        int k2Group = this.find(k2.id);
        return k1Group == k2Group;
    }

    public int find(Knot k) {
        return this.find(k.id);
    }

    // Returns representative of x's set
    public int find(int x) {
        // Finds the representative of the set
        // that x is an element of
        if(!parent.containsKey(x)){
            float z =0;
        }
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

    public int findUnmatched(int x) {
        int xRoot = find(x);
        return unmatched.get(xRoot);
    }

    public int union(Knot k1, Knot k2) {
        return union(k1.id, k2.id);
    }

    // Unites the set that includes x and the set
    // that includes y
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

    public void addSet(Knot k) {
        parent.put(k.id, k.id);
        unmatched.put(k.id, 2);
        countGroups++;
        totalNumGroups++;
    }

    public int countGroups(){
        return countGroups;
    }
    public int totalNumGroups(){
        return totalNumGroups;
    }

}