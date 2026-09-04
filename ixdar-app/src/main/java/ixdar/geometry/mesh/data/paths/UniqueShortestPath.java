package ixdar.geometry.mesh.data.paths;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Shortest paths over a mesh's edges, weighted by geometric edge length,
 * refusing to pick between tied shortest paths: a second path within a
 * relative epsilon of the best throws, never a tie-break by vertex id. The
 * distance field comes from the shared {@link Dijkstra} core.
 */
public final class UniqueShortestPath {

    /** Relative length gap below which two paths count as tied. */
    public static final double RELATIVE_EPSILON = 1e-6;

    private UniqueShortestPath() {
    }

    /**
     * The unique shortest vertex path between two mesh vertices.
     *
     * @param mesh       mesh whose edges are walked, weighted by their length
     * @param fromVertex vertex the path starts at
     * @param toVertex   vertex the path ends at
     * @throws IllegalStateException when no path exists, or when a second path ties
     *                               the best within {@link #RELATIVE_EPSILON}
     * @return vertices in travel order, both endpoints included; a single
     *         vertex when the endpoints coincide
     */
    public static List<Integer> find(HalfEdgeMesh mesh, int fromVertex, int toVertex) {
        if (fromVertex == toVertex) {
            List<Integer> trivial = new ArrayList<>(1);
            trivial.add(fromVertex);
            return trivial;
        }
        ShortestPathForest forest = Dijkstra.forest(mesh, new int[] { fromVertex }, edgeLengths(mesh));
        double[] distance = forest.distance;
        if (distance[toVertex] == Double.POSITIVE_INFINITY) {
            throw new IllegalStateException("no path between vertices " + fromVertex
                    + " and " + toVertex);
        }
        return uniquePath(mesh, fromVertex, toVertex, distance);
    }

    /**
     * Counts shortest paths over the tight-edge DAG of a finished distance field
     * and reconstructs the path when it is unique.
     *
     * @param mesh       mesh carrying the edge lengths
     * @param fromVertex source of the distance field
     * @param toVertex   target vertex
     * @param distance   Dijkstra distances from the source
     * @throws IllegalStateException when more than one tight path reaches the
     *                               target
     * @return the unique shortest vertex path in travel order
     */
    private static List<Integer> uniquePath(HalfEdgeMesh mesh, int fromVertex,
            int toVertex, double[] distance) {
        double tolerance = RELATIVE_EPSILON * distance[toVertex];
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < mesh.vertexCount(); index++) {
            int vertexId = mesh.vertexIdAt(index);
            if (distance[vertexId] <= distance[toVertex] + tolerance) {
                order.add(vertexId);
            }
        }
        order.sort((left, right) -> Double.compare(distance[left], distance[right]));
        int[] pathCount = new int[distance.length];
        int[] predecessor = new int[distance.length];
        Arrays.fill(predecessor, -1);
        pathCount[fromVertex] = 1;
        for (int vertex : order) {
            if (pathCount[vertex] == 0) {
                continue;
            }
            for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertex); spoke++) {
                int edgeId = mesh.vertexEdgeAt(vertex, spoke);
                int other = otherEndpoint(mesh, edgeId, vertex);
                if (distance[vertex] >= distance[other]) {
                    continue;
                }
                if (distance[vertex] + edgeLength(mesh, edgeId)
                        <= distance[other] + tolerance) {
                    pathCount[other] = Math.min(2, pathCount[other] + pathCount[vertex]);
                    predecessor[other] = vertex;
                }
            }
        }
        if (pathCount[toVertex] != 1) {
            throw new IllegalStateException("ambiguous shortest path between vertices "
                    + fromVertex + " and " + toVertex + ", add a via waypoint");
        }
        List<Integer> path = new ArrayList<>();
        int cursor = toVertex;
        while (cursor != -1) {
            path.add(cursor);
            if (cursor == fromVertex) {
                break;
            }
            cursor = predecessor[cursor];
        }
        if (path.get(path.size() - 1) != fromVertex) {
            throw new IllegalStateException("shortest path reconstruction between vertices "
                    + fromVertex + " and " + toVertex + " did not reach the source");
        }
        Collections.reverse(path);
        return path;
    }

    /**
     * The geometric length of every live edge, indexed by edge id.
     *
     * @param mesh mesh whose edges are measured
     * @return per-edge-id lengths; slots of dead edge ids stay zero
     */
    private static double[] edgeLengths(HalfEdgeMesh mesh) {
        int bound = 0;
        for (int index = 0; index < mesh.edgeCount(); index++) {
            bound = Math.max(bound, mesh.edgeIdAt(index) + 1);
        }
        double[] lengths = new double[bound];
        for (int index = 0; index < mesh.edgeCount(); index++) {
            int edgeId = mesh.edgeIdAt(index);
            lengths[edgeId] = edgeLength(mesh, edgeId);
        }
        return lengths;
    }

    /**
     * The endpoint of an edge that is not the given vertex.
     *
     * @param mesh     mesh holding the edge
     * @param edgeId   edge to read
     * @param vertexId one endpoint
     * @return the other endpoint's id
     */
    private static int otherEndpoint(HalfEdgeMesh mesh, int edgeId, int vertexId) {
        int halfEdge = mesh.edgeHalfEdge(edgeId);
        int start = mesh.halfEdgeVertex(halfEdge);
        return start == vertexId ? mesh.halfEdgeEndVertex(halfEdge) : start;
    }

    /**
     * The geometric length of an edge, from its endpoint positions.
     *
     * @param mesh   mesh holding the edge
     * @param edgeId edge to measure
     * @return the Euclidean endpoint distance
     */
    private static float edgeLength(HalfEdgeMesh mesh, int edgeId) {
        int halfEdge = mesh.edgeHalfEdge(edgeId);
        Vector3f start = mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), new Vector3f());
        Vector3f end = mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), new Vector3f());
        return start.distance(end);
    }
}
