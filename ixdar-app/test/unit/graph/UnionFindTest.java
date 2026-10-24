package unit.graph;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import ixdar.numeric.graph.UnionFind;

/**
 * The rank variant of {@link UnionFind}: which root survives a merge, which is the group id the
 * knot search reads, and the growth that lets the forest take ids as they are created.
 */
class UnionFindTest {

    @Test
    void tieKeepsTheFirstRootAndRaisesItsRank() {
        int[] parent = UnionFind.singletons(2);
        int[] rank = new int[2];
        UnionFind.unionByRank(parent, rank, 1, 0);
        assertEquals(1, UnionFind.find(parent, 0));
        assertArrayEquals(new int[] { 0, 1 }, rank);
    }

    @Test
    void lowerRankedRootMovesUnderTheHigherWhicheverArgumentItIs() {
        int[] parent = UnionFind.singletons(3);
        int[] rank = new int[3];
        UnionFind.unionByRank(parent, rank, 0, 1);
        UnionFind.unionByRank(parent, rank, 2, 1);
        assertEquals(0, UnionFind.find(parent, 2));
        assertArrayEquals(new int[] { 1, 0, 0 }, rank);
    }

    @Test
    void unionOfOneClassChangesNothing() {
        int[] parent = UnionFind.singletons(2);
        int[] rank = new int[2];
        UnionFind.unionByRank(parent, rank, 0, 1);
        UnionFind.unionByRank(parent, rank, 1, 0);
        assertArrayEquals(new int[] { 0, 0 }, parent);
        assertArrayEquals(new int[] { 1, 0 }, rank);
    }

    @Test
    void growReturnsTheSameArrayWhenItAlreadyCoversTheElement() {
        int[] parent = UnionFind.singletons(4);
        assertSame(parent, UnionFind.grow(parent, 3));
    }

    @Test
    void growAtLeastDoublesAndAddsSingletonsKeepingExistingParents() {
        int[] parent = UnionFind.singletons(3);
        UnionFind.union(parent, 0, 2);
        assertArrayEquals(new int[] { 0, 1, 0, 3, 4, 5 }, UnionFind.grow(parent, 3));
    }

    @Test
    void growReachesAnElementBeyondDoubleTheLength() {
        assertArrayEquals(new int[] { 0, 1, 2, 3, 4 }, UnionFind.grow(new int[0], 4));
    }
}
