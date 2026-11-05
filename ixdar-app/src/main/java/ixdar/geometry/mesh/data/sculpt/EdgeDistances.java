package ixdar.geometry.mesh.data.sculpt;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.numeric.graph.Dijkstra;

/**
 * Shortest distances along mesh edges from a set of source vertices: the graph search the stroke
 * snap joins its vertices with and the displacement falloff measures from. A search costs what it
 * settles, never the mesh's size, so a pass's many small searches stay small.
 */
public final class EdgeDistances {

    /** Vertex ids the last search settled, in settling order. */
    public int[] settledVertexIds = new int[0];

    /** How many of {@link #settledVertexIds} the last search filled. */
    public int settledLength;

    private final Dijkstra walk = new Dijkstra();

    /** Index into the sources of the source each reached vertex is nearest, by vertex id. */
    private int[] originByVertexId = new int[0];

    /**
     * Run Dijkstra over edge lengths from the sources, settling vertices up to {@code cutoff} and
     * stopping early once {@code targetVertexId} settles.
     *
     * @param mesh            surface whose edges are walked
     * @param sourceVertexIds vertex ids at distance zero
     * @param sourceCount     how many leading entries of {@code sourceVertexIds} are sources
     * @param cutoff          largest distance settled
     * @param targetVertexId  vertex whose settling ends the search, or -1 to settle the whole ball
     * @param across          unit direction edges are measured across, their extent along it
     *                        ignored, or null for full edge lengths
     * @return true when the target settled, or when there was none to wait for
     */
    public boolean expand(MeshTopology mesh, int[] sourceVertexIds, int sourceCount, float cutoff,
            int targetVertexId, Vector3fc across) {
        Vector3f from = new Vector3f();
        Vector3f to = new Vector3f();
        int bound = mesh.vertexIdBound();
        if (originByVertexId.length < bound) {
            originByVertexId = new int[bound];
            settledVertexIds = new int[bound];
        }
        walk.start(bound);
        settledLength = 0;
        for (int source = 0; source < sourceCount; source++) {
            if (walk.relax(sourceVertexIds[source], 0.0, -1)) {
                originByVertexId[sourceVertexIds[source]] = source;
            }
        }
        for (int vertexId = walk.settle(); vertexId >= 0; vertexId = walk.settle()) {
            settledVertexIds[settledLength++] = vertexId;
            if (vertexId == targetVertexId) {
                return true;
            }
            float reached = (float) walk.distance(vertexId);
            for (int adjacency = 0; adjacency < mesh.vertexEdgeCount(vertexId); adjacency++) {
                int edgeId = mesh.vertexEdgeAt(vertexId, adjacency);
                int neighbourId = mesh.edgeOtherVertex(edgeId, vertexId);
                if (neighbourId < 0 || walk.isSettled(neighbourId)) {
                    continue;
                }
                float length = mesh.edgeLength(edgeId);
                if (across != null) {
                    mesh.vertexPosition(neighbourId, to).sub(mesh.vertexPosition(vertexId, from));
                    length = to.fma(-to.dot(across), across).length();
                }
                float candidate = reached + length;
                if (candidate <= cutoff && walk.relax(neighbourId, candidate, edgeId)) {
                    originByVertexId[neighbourId] = originByVertexId[vertexId];
                }
            }
        }
        return targetVertexId < 0;
    }

    /**
     * A vertex's edge-path distance in the last search.
     *
     * @param vertexId vertex to read
     * @return its distance, infinite where the search never reached
     */
    public float distance(int vertexId) {
        return (float) walk.distance(vertexId);
    }

    /**
     * The edge the last search entered a vertex through.
     *
     * @param vertexId vertex to read
     * @return the edge id, -1 for a source or a vertex never reached
     */
    public int arrivalEdge(int vertexId) {
        return walk.via(vertexId);
    }

    /**
     * Which source a vertex the last search reached is nearest.
     *
     * @param vertexId a reached vertex
     * @return its index into the sources
     */
    public int origin(int vertexId) {
        return originByVertexId[vertexId];
    }
}
