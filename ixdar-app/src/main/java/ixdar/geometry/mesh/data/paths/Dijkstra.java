package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;
import java.util.PriorityQueue;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * The one shared Dijkstra core: a mesh's edge adjacency, a per-edge cost, and
 * source vertices in; a {@link ShortestPathForest} over vertex ids out.
 * Relaxation is strict ({@code <}), so equal-distance candidates never replace
 * an earlier parent; callers that must refuse ties detect them on the finished
 * distance field.
 */
public final class Dijkstra {

    private Dijkstra() {
    }

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
        double[] distance = new double[vertexBound];
        int[] parent = new int[vertexBound];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        Arrays.fill(parent, -1);
        boolean[] unsettledTarget = new boolean[vertexBound];
        int unsettledTargets = 0;
        for (int target : targets) {
            unsettledTargets += unsettledTarget[target] ? 0 : 1;
            unsettledTarget[target] = true;
        }
        PriorityQueue<double[]> frontier = new PriorityQueue<>(
                (left, right) -> Double.compare(left[0], right[0]));
        for (int source : sources) {
            distance[source] = 0.0;
            parent[source] = source;
            frontier.add(new double[] { 0.0, source });
        }
        while (!frontier.isEmpty()) {
            double[] entry = frontier.poll();
            int vertex = (int) entry[1];
            if (entry[0] > distance[vertex]) {
                continue;
            }
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
                if (other < 0) {
                    continue;
                }
                double relaxed = distance[vertex] + edgeCost[edgeId];
                if (relaxed < distance[other]) {
                    distance[other] = relaxed;
                    parent[other] = vertex;
                    frontier.add(new double[] { relaxed, other });
                }
            }
        }
        return new ShortestPathForest(distance, parent);
    }
}
