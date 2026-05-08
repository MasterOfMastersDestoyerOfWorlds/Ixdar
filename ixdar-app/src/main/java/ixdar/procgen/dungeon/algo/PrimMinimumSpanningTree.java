package ixdar.procgen.dungeon.algo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;
import ixdar.procgen.dungeon.values.RoomListValue.Room;
import ixdar.procgen.dungeon.values.RoomListValue3D;

/**
 * Minimum spanning tree over Delaunay edges weighted by Euclidean distance between room
 * centers, plus a probabilistic extra-edge pass that reintroduces non-MST Delaunay edges to
 * create loops. Stage 3 of the vazgriz dungeon pipeline.
 *
 * <p>Determinism: the MST step is deterministic in the input edge order (priority queue ties
 * are broken by edge index). The extra-edge pass uses a separate RNG stream seeded by
 * {@code seed ^ <mix constant>} so it's reproducible independently of upstream RNG consumers
 * (e.g. room placement).
 */
public final class PrimMinimumSpanningTree {
    public static final long NUM_0x9E3779B97F4A7C15 = 0x9E3779B97F4A7C15L;

    /** Default probability per non-MST Delaunay edge of being kept as an extra loop (vazgriz). */
    public static final double DEFAULT_EXTRA_EDGE_PROB = 0.125;

    private PrimMinimumSpanningTree() {
    }

    /**
     * TODO: document.
     *
     * @param delaunayEdges edges from {@link DelaunayTriangulation2D} (candidate set)
     * @param rooms         source rooms used to compute edge weights
     * @param extraEdgeProb probability [0, 1] of keeping each non-MST edge as a loop
     * @param seed          seed for the extra-edge RNG stream
     * @return TODO: describe
     */
    public static EdgeGraphValue build(EdgeGraphValue delaunayEdges,
                                       RoomListValue rooms,
                                       double extraEdgeProb,
                                       long seed) {
        double[] weights = new double[delaunayEdges.edgeCount()];
        for (int i = 0; i < delaunayEdges.edgeCount(); i++) {
            int[] e = delaunayEdges.edge(i);
            weights[i] = distance(rooms.get(e[0]), rooms.get(e[1]));
        }
        return buildWithWeights(delaunayEdges, weights, extraEdgeProb, seed);
    }

    /**
     * 3D analog: weights are 3D Euclidean distances between {@link RoomListValue3D.Room} centers.
     *
     * @param delaunayEdges TODO: describe
     * @param rooms TODO: describe
     * @param extraEdgeProb TODO: describe
     * @param seed TODO: describe
     * @return TODO: describe
     */
    public static EdgeGraphValue build3D(EdgeGraphValue delaunayEdges,
                                         RoomListValue3D rooms,
                                         double extraEdgeProb,
                                         long seed) {
        double[] weights = new double[delaunayEdges.edgeCount()];
        for (int i = 0; i < delaunayEdges.edgeCount(); i++) {
            int[] e = delaunayEdges.edge(i);
            weights[i] = distance3D(rooms.get(e[0]), rooms.get(e[1]));
        }
        return buildWithWeights(delaunayEdges, weights, extraEdgeProb, seed);
    }

    private static EdgeGraphValue buildWithWeights(EdgeGraphValue delaunayEdges,
                                                   double[] weights,
                                                   double extraEdgeProb,
                                                   long seed) {
        int n = delaunayEdges.nodeCount();
        if (n <= 1) return new EdgeGraphValue(n, new int[0][]);

        // Build weighted adjacency indexed by node. adj.get(v) is in original Delaunay order.
        List<List<WeightedEdge>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int i = 0; i < delaunayEdges.edgeCount(); i++) {
            int[] e = delaunayEdges.edge(i);
            double w = weights[i];
            adj.get(e[0]).add(new WeightedEdge(e[1], w, i));
            adj.get(e[1]).add(new WeightedEdge(e[0], w, i));
        }

        // Prim's starting from node 0.
        boolean[] inTree = new boolean[n];
        Set<Integer> mstEdgeIndices = new LinkedHashSet<>();
        PriorityQueue<PrimEdge> pq = new PriorityQueue<>();
        inTree[0] = true;
        for (WeightedEdge w : adj.get(0)) {
            pq.add(new PrimEdge(w.edgeIdx, w.neighbor, w.weight));
        }
        while (!pq.isEmpty()) {
            PrimEdge top = pq.poll();
            if (inTree[top.to]) continue;
            inTree[top.to] = true;
            mstEdgeIndices.add(top.edgeIdx);
            for (WeightedEdge w : adj.get(top.to)) {
                if (!inTree[w.neighbor]) {
                    pq.add(new PrimEdge(w.edgeIdx, w.neighbor, w.weight));
                }
            }
        }

        // Extra-edge pass on its own RNG stream.
        Random extraRng = new Random(seed ^ NUM_0x9E3779B97F4A7C15);
        Set<Integer> finalEdges = new LinkedHashSet<>(mstEdgeIndices);
        for (int i = 0; i < delaunayEdges.edgeCount(); i++) {
            if (mstEdgeIndices.contains(i)) continue;
            if (extraRng.nextDouble() < extraEdgeProb) {
                finalEdges.add(i);
            }
        }

        // Emit edges in original Delaunay index order for deterministic output.
        List<Integer> sortedIndices = new ArrayList<>(finalEdges);
        sortedIndices.sort(Integer::compareTo);
        int[][] out = new int[sortedIndices.size()][];
        for (int i = 0; i < sortedIndices.size(); i++) {
            out[i] = delaunayEdges.edge(sortedIndices.get(i));
        }
        return new EdgeGraphValue(n, out);
    }

    private static double distance(Room a, Room b) {
        double dx = a.centerX() - b.centerX();
        double dy = a.centerY() - b.centerY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double distance3D(RoomListValue3D.Room a, RoomListValue3D.Room b) {
        double dx = a.centerX() - b.centerX();
        double dy = a.centerY() - b.centerY();
        double dz = a.centerZ() - b.centerZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private record WeightedEdge(int neighbor, double weight, int edgeIdx) { }

    private record PrimEdge(int edgeIdx, int to, double weight) implements Comparable<PrimEdge> {
        @Override
        public int compareTo(PrimEdge o) {
            int c = Double.compare(weight, o.weight);
            if (c != 0) return c;
            // Tie-break by edge index so the PriorityQueue is fully deterministic.
            return Integer.compare(edgeIdx, o.edgeIdx);
        }
    }
}
