package ixdar.procgen.dungeon.algo;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Minimum spanning tree over a graph mesh's edges, weighted by Euclidean distance between
 * endpoint positions, plus a probabilistic pass reintroducing non-MST edges as loops. Emits a
 * per-edge selection in dense edge order; the extra-edge pass draws from its own RNG stream.
 */
public final class PrimMinimumSpanningTree {
    public static final long NUM_0x9E3779B97F4A7C15 = 0x9E3779B97F4A7C15L;

    /** Default probability per non-MST edge of being kept as an extra loop (vazgriz). */
    public static final double DEFAULT_EXTRA_EDGE_PROB = 0.125;

    private PrimMinimumSpanningTree() {
    }

    /**
     * Run Prim's MST on the mesh's edges weighted by Euclidean distance between endpoint
     * positions, then probabilistically reintroduce non-MST edges to add loops.
     *
     * @param mesh          graph mesh whose wire edges are the candidate set
     * @param extraEdgeProb probability [0, 1] of keeping each non-MST edge as a loop
     * @param seed          seed for the extra-edge RNG stream
     * @return per-edge kept flags in dense edge order
     */
    public static boolean[] build(MeshTopology mesh, double extraEdgeProb, long seed) {
        int n = mesh.vertexCount();
        int e = mesh.edgeCount();
        if (n <= 1 || e == 0) {
            return new boolean[e];
        }

        int[] pairs = DungeonGrids.selectedEdgePairs(mesh, null);
        double[] weights = new double[e];
        Vector3f pa = new Vector3f();
        Vector3f pb = new Vector3f();
        for (int i = 0; i < e; i++) {
            mesh.vertexPosition(mesh.vertexIdAt(pairs[i * 2]), pa);
            mesh.vertexPosition(mesh.vertexIdAt(pairs[i * 2 + 1]), pb);
            weights[i] = pa.distance(pb);
        }

        // Adjacency in CSR form indexed by dense vertex, each vertex's incident edges in dense
        // edge order; a candidate's weight is weights[edgeIdx].
        int[] adjOffsets = new int[n + 1];
        for (int i = 0; i < e; i++) {
            adjOffsets[pairs[i * 2] + 1]++;
            adjOffsets[pairs[i * 2 + 1] + 1]++;
        }
        for (int i = 1; i <= n; i++) {
            adjOffsets[i] += adjOffsets[i - 1];
        }
        int[] adjEdge = new int[adjOffsets[n]];
        int[] adjNeighbor = new int[adjOffsets[n]];
        int[] fill = Arrays.copyOf(adjOffsets, n);
        for (int i = 0; i < e; i++) {
            int a = pairs[i * 2];
            int b = pairs[i * 2 + 1];
            adjEdge[fill[a]] = i;
            adjNeighbor[fill[a]++] = b;
            adjEdge[fill[b]] = i;
            adjNeighbor[fill[b]++] = a;
        }

        // Prim's starting from dense vertex 0. The queue holds candidate edge indices ordered
        // by weight, tie-broken by edge index so the order is fully deterministic; an edge is
        // queued from the endpoint already in the tree, so its far endpoint is the one not yet
        // in the tree when it is polled.
        boolean[] inTree = new boolean[n];
        Set<Integer> mstEdgeIndices = new LinkedHashSet<>();
        PriorityQueue<Integer> pq = new PriorityQueue<>((left, right) -> {
            int c = Double.compare(weights[left], weights[right]);
            return c != 0 ? c : Integer.compare(left, right);
        });
        inTree[0] = true;
        for (int j = adjOffsets[0]; j < adjOffsets[1]; j++) {
            pq.add(adjEdge[j]);
        }
        while (!pq.isEmpty()) {
            int edge = pq.poll();
            int a = pairs[edge * 2];
            int to = inTree[a] ? pairs[edge * 2 + 1] : a;
            if (inTree[to]) continue;
            inTree[to] = true;
            mstEdgeIndices.add(edge);
            for (int j = adjOffsets[to]; j < adjOffsets[to + 1]; j++) {
                if (!inTree[adjNeighbor[j]]) {
                    pq.add(adjEdge[j]);
                }
            }
        }

        // Extra-edge pass on its own RNG stream.
        Random extraRng = new Random(seed ^ NUM_0x9E3779B97F4A7C15);
        boolean[] selection = new boolean[e];
        for (int i : mstEdgeIndices) {
            selection[i] = true;
        }
        for (int i = 0; i < e; i++) {
            if (selection[i]) continue;
            if (extraRng.nextDouble() < extraEdgeProb) {
                selection[i] = true;
            }
        }
        return selection;
    }
}
