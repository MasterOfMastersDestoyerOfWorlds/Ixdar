package ixdar.geometry.mesh.data.sculpt;

import java.util.Arrays;
import java.util.PriorityQueue;

import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Shortest distances along mesh edges from a set of source vertices: the graph search the stroke
 * snap joins its vertices with and the displacement falloff measures from.
 */
public final class EdgeDistances {

    public static final int VERTEX_BITS = 32;

    public static final long VERTEX_MASK = 0xffffffffL;

    /** Edge-path distance by vertex id, infinite where the search never reached. */
    public float[] distanceByVertexId = new float[0];

    /** Edge each reached vertex was entered through by vertex id, -1 for sources and the unreached. */
    public int[] arrivalEdgeByVertexId = new int[0];

    /** Index into the sources of the source each reached vertex is nearest, by vertex id. */
    public int[] originByVertexId = new int[0];

    /** Vertex ids the last search settled, in settling order. */
    public int[] settledVertexIds = new int[0];

    /** How many of {@link #settledVertexIds} the last search filled. */
    public int settledLength;

    /**
     * Run Dijkstra over edge lengths from the sources, settling vertices up to {@code cutoff} and
     * stopping early once {@code targetVertexId} settles.
     *
     * @param mesh            surface whose edges are walked
     * @param sourceVertexIds vertex ids at distance zero
     * @param sourceCount     how many leading entries of {@code sourceVertexIds} are sources
     * @param cutoff          largest distance settled
     * @param targetVertexId  vertex whose settling ends the search, or -1 to settle the whole ball
     * @return true when the target settled, or when there was none to wait for
     */
    public boolean expand(MeshTopology mesh, int[] sourceVertexIds, int sourceCount, float cutoff,
            int targetVertexId) {
        int bound = mesh.vertexIdBound();
        if (distanceByVertexId.length < bound) {
            distanceByVertexId = new float[bound];
            arrivalEdgeByVertexId = new int[bound];
            originByVertexId = new int[bound];
            settledVertexIds = new int[bound];
        }
        Arrays.fill(distanceByVertexId, Float.POSITIVE_INFINITY);
        Arrays.fill(arrivalEdgeByVertexId, -1);
        settledLength = 0;
        boolean[] settled = new boolean[bound];
        PriorityQueue<Long> frontier = new PriorityQueue<>();
        for (int source = 0; source < sourceCount; source++) {
            int vertexId = sourceVertexIds[source];
            distanceByVertexId[vertexId] = 0f;
            originByVertexId[vertexId] = source;
            frontier.add((long) vertexId);
        }
        while (!frontier.isEmpty()) {
            long entry = frontier.poll();
            int vertexId = (int) (entry & VERTEX_MASK);
            if (settled[vertexId]) {
                continue;
            }
            settled[vertexId] = true;
            settledVertexIds[settledLength++] = vertexId;
            if (vertexId == targetVertexId) {
                return true;
            }
            float reached = distanceByVertexId[vertexId];
            for (int adjacency = 0; adjacency < mesh.vertexEdgeCount(vertexId); adjacency++) {
                int edgeId = mesh.vertexEdgeAt(vertexId, adjacency);
                int neighbourId = mesh.edgeOtherVertex(edgeId, vertexId);
                float candidate = reached + mesh.edgeLength(edgeId);
                if (neighbourId < 0 || settled[neighbourId] || candidate > cutoff
                        || candidate >= distanceByVertexId[neighbourId]) {
                    continue;
                }
                distanceByVertexId[neighbourId] = candidate;
                arrivalEdgeByVertexId[neighbourId] = edgeId;
                originByVertexId[neighbourId] = originByVertexId[vertexId];
                frontier.add(((long) Float.floatToIntBits(candidate) << VERTEX_BITS) | neighbourId);
            }
        }
        return targetVertexId < 0;
    }
}
