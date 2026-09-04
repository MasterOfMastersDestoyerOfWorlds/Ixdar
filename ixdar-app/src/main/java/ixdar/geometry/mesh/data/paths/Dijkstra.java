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
        int vertexBound = 0;
        for (int index = 0; index < mesh.vertexCount(); index++) {
            vertexBound = Math.max(vertexBound, mesh.vertexIdAt(index) + 1);
        }
        double[] distance = new double[vertexBound];
        int[] parent = new int[vertexBound];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        Arrays.fill(parent, -1);
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
            int spokes = mesh.vertexEdgeCount(vertex);
            for (int spoke = 0; spoke < spokes; spoke++) {
                int edgeId = mesh.vertexEdgeAt(vertex, spoke);
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                int start = mesh.halfEdgeVertex(halfEdge);
                int other = start == vertex ? mesh.halfEdgeEndVertex(halfEdge) : start;
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
