package ixdar.numeric.graph;

import java.util.Arrays;

import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * The one shared Dijkstra core over a graph its caller walks: {@link #start}, {@link #relax} the
 * sources, then relax each {@link #settle}d node's neighbours. Relaxation is strict, and ties pop
 * in {@link java.util.PriorityQueue} order.
 */
public final class Dijkstra {

    private static final int GROWTH = 2;

    private static final int STAMPS_PER_RUN = 2;

    private double[] distance = new double[0];

    private int[] via = new int[0];

    /** {@code stamp} once a node is reached in this run, {@code stamp + 1} once it is settled. */
    private int[] visit = new int[0];

    private int stamp = -1;

    private double[] frontierPriority = new double[1];

    private int[] frontierNode = new int[1];

    private int frontierSize;

    /**
     * Runs multi-source Dijkstra over a mesh's edges.
     *
     * @param mesh     mesh whose vertex-edge adjacency is walked
     * @param sources  source vertex ids, seeded at distance zero in order
     * @param edgeCost traversal cost per edge, indexed by edge id
     * @return the finished forest, indexed by vertex id
     */
    public static ShortestPathForest forest(MeshTopology mesh, int[] sources, double[] edgeCost) {
        return forest(mesh, sources, edgeCost, new int[0]);
    }

    /**
     * Runs multi-source Dijkstra until every target is settled. A target ends the walks that
     * reach it: its distance is the shortest arrival along its own edges, never through another
     * target.
     *
     * @param mesh     mesh whose vertex-edge adjacency is walked
     * @param sources  source vertex ids, seeded at distance zero in order
     * @param edgeCost traversal cost per edge, indexed by edge id
     * @param targets  vertex ids that are reached but never walked through; empty walks the
     *                 whole mesh
     * @return the forest, final at every target and at every vertex nearer than the farthest
     */
    public static ShortestPathForest forest(MeshTopology mesh, int[] sources, double[] edgeCost,
            int[] targets) {
        int vertexBound = 0;
        for (int index = 0; index < mesh.vertexCount(); index++) {
            vertexBound = Math.max(vertexBound, mesh.vertexIdAt(index) + 1);
        }
        boolean[] unsettledTarget = new boolean[vertexBound];
        int unsettledTargets = 0;
        for (int target : targets) {
            unsettledTargets += unsettledTarget[target] ? 0 : 1;
            unsettledTarget[target] = true;
        }
        Dijkstra walk = new Dijkstra();
        walk.start(vertexBound);
        for (int source : sources) {
            walk.relax(source, 0.0, source);
        }
        for (int vertex = walk.settle(); vertex >= 0; vertex = walk.settle()) {
            if (unsettledTarget[vertex]) {
                unsettledTarget[vertex] = false;
                if (--unsettledTargets == 0) {
                    break;
                }
                continue;
            }
            int spokes = mesh.vertexEdgeCount(vertex);
            for (int spoke = 0; spoke < spokes; spoke++) {
                int edgeId = mesh.vertexEdgeAt(vertex, spoke);
                int other = mesh.edgeOtherVertex(edgeId, vertex);
                if (other >= 0) {
                    walk.relax(other, walk.distance[vertex] + edgeCost[edgeId], vertex);
                }
            }
        }
        double[] distance = new double[vertexBound];
        int[] parent = new int[vertexBound];
        for (int vertex = 0; vertex < vertexBound; vertex++) {
            distance[vertex] = walk.distance(vertex);
            parent[vertex] = walk.via(vertex);
        }
        return new ShortestPathForest(distance, parent);
    }

    /**
     * Begins a run over nodes {@code [0, nodeBound)}: every node unreached, the frontier empty.
     *
     * @param nodeBound one past the largest node the run may reach
     */
    public void start(int nodeBound) {
        if (visit.length < nodeBound) {
            distance = new double[nodeBound];
            via = new int[nodeBound];
            visit = new int[nodeBound];
            stamp = -1;
        }
        if (stamp >= Integer.MAX_VALUE - STAMPS_PER_RUN) {
            Arrays.fill(visit, 0);
            stamp = -1;
        }
        stamp += STAMPS_PER_RUN;
        frontierSize = 0;
    }

    /**
     * Offers a node a distance, taken only when strictly shorter than the node's own (infinite
     * while unreached); a settled node that takes one is settled again. Sources are relaxed too.
     *
     * @param node      node to reach
     * @param candidate distance the node would take
     * @param via       what the caller records for the step, usually the node it came from
     * @return whether the node took the candidate
     */
    public boolean relax(int node, double candidate, int via) {
        return relax(node, candidate, candidate, via);
    }

    /**
     * {@link #relax(int, double, int)} with the node queued at a priority of the caller's, such
     * as A*'s distance plus its estimate of the distance still to go.
     *
     * @param node      node to reach
     * @param candidate distance the node would take
     * @param priority  key the node is settled in order of
     * @param via       what the caller records for the step
     * @return whether the node took the candidate
     */
    public boolean relax(int node, double candidate, double priority, int via) {
        if (!(candidate < distance(node))) {
            return false;
        }
        visit[node] = stamp;
        distance[node] = candidate;
        this.via[node] = via;
        if (frontierSize == frontierNode.length) {
            frontierPriority = Arrays.copyOf(frontierPriority, frontierSize * GROWTH);
            frontierNode = Arrays.copyOf(frontierNode, frontierSize * GROWTH);
        }
        // Sift up exactly as PriorityQueue.offer does, so equal priorities pop in its order.
        int slot = frontierSize++;
        while (slot > 0) {
            int parent = (slot - 1) >>> 1;
            if (Double.compare(priority, frontierPriority[parent]) >= 0) {
                break;
            }
            frontierPriority[slot] = frontierPriority[parent];
            frontierNode[slot] = frontierNode[parent];
            slot = parent;
        }
        frontierPriority[slot] = priority;
        frontierNode[slot] = node;
        return true;
    }

    /**
     * Settles the reached node of least priority not settled yet; with priority equal to
     * distance, its distance is final from here on.
     *
     * @return the settled node, or -1 when the frontier is empty
     */
    public int settle() {
        while (frontierSize > 0) {
            int node = frontierNode[0];
            frontierSize--;
            // Move the last entry to the root and sift it down exactly as PriorityQueue.poll does.
            double key = frontierPriority[frontierSize];
            int moved = frontierNode[frontierSize];
            int slot = 0;
            int half = frontierSize >>> 1;
            while (slot < half) {
                int child = (slot << 1) + 1;
                int right = child + 1;
                if (right < frontierSize
                        && Double.compare(frontierPriority[child], frontierPriority[right]) > 0) {
                    child = right;
                }
                if (Double.compare(key, frontierPriority[child]) <= 0) {
                    break;
                }
                frontierPriority[slot] = frontierPriority[child];
                frontierNode[slot] = frontierNode[child];
                slot = child;
            }
            frontierPriority[slot] = key;
            frontierNode[slot] = moved;
            if (visit[node] == stamp) {
                visit[node] = stamp + 1;
                return node;
            }
        }
        return -1;
    }

    /**
     * Whether this run has reached a node.
     *
     * @param node node to ask about
     * @return true once some relaxation reached it
     */
    public boolean isReached(int node) {
        return visit[node] == stamp || visit[node] == stamp + 1;
    }

    /**
     * Whether this run has settled a node.
     *
     * @param node node to ask about
     * @return true once {@link #settle} returned it
     */
    public boolean isSettled(int node) {
        return visit[node] == stamp + 1;
    }

    /**
     * A node's distance in this run.
     *
     * @param node node to read
     * @return its best distance so far, {@link Double#POSITIVE_INFINITY} when unreached
     */
    public double distance(int node) {
        return isReached(node) ? distance[node] : Double.POSITIVE_INFINITY;
    }

    /**
     * What the step that gave a node its distance recorded.
     *
     * @param node node to read
     * @return the {@code via} of its last accepted relaxation, -1 when unreached
     */
    public int via(int node) {
        return isReached(node) ? via[node] : -1;
    }
}
