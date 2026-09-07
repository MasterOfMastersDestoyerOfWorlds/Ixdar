package ixdar.geometry.mesh.data;

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
}
