package ixdar.numeric.graph;

import java.util.Arrays;

/**
 * Disjoint-set forest over an {@code int[] parent} array the caller owns, with path halving on
 * lookup. The array is the whole data structure; every operation takes it as its first argument.
 */
public final class UnionFind {

    private UnionFind() {
    }

    /**
     * A parent array in which every element is its own class.
     *
     * @param count elements the forest covers
     * @return the singleton forest
     */
    public static int[] singletons(int count) {
        int[] parent = new int[count];
        for (int element = 0; element < count; element++) {
            parent[element] = element;
        }
        return parent;
    }

    /**
     * The representative of an element's class, halving the path it walked.
     *
     * @param parent parent array, updated in place
     * @param element element to look up
     * @return the element's root
     */
    public static int find(int[] parent, int element) {
        int root = element;
        while (parent[root] != root) {
            parent[root] = parent[parent[root]];
            root = parent[root];
        }
        return root;
    }

    /**
     * Merges the classes of two elements, keeping the first one's root.
     *
     * @param parent parent array, updated in place
     * @param first one element
     * @param second the other element
     */
    public static void union(int[] parent, int first, int second) {
        int firstRoot = find(parent, first);
        int secondRoot = find(parent, second);
        if (firstRoot != secondRoot) {
            parent[secondRoot] = firstRoot;
        }
    }

    /**
     * Merges the classes of two elements by rank: the lower-ranked root moves under the higher,
     * and on a tie the second's root goes under the first's, whose rank grows.
     *
     * @param parent parent array, updated in place
     * @param rank upper bound on each root's tree height, updated in place
     * @param first one element
     * @param second the other element
     */
    public static void unionByRank(int[] parent, int[] rank, int first, int second) {
        int firstRoot = find(parent, first);
        int secondRoot = find(parent, second);
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
     * A parent array that covers {@code element}: the array itself when it already does, else a
     * copy at least doubled in length whose new elements are singletons.
     *
     * @param parent parent array to extend
     * @param element element the result must cover
     * @return {@code parent}, or its longer copy
     */
    public static int[] grow(int[] parent, int element) {
        if (element < parent.length) {
            return parent;
        }
        int oldLength = parent.length;
        int[] grown = Arrays.copyOf(parent, Math.max(element + 1, oldLength * 2));
        for (int added = oldLength; added < grown.length; added++) {
            grown[added] = added;
        }
        return grown;
    }
}
