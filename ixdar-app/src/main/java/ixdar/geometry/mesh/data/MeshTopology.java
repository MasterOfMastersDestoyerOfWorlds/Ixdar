package ixdar.geometry.mesh.data;

import org.joml.Vector3f;

/**
 * Read-only mesh topology and geometry interface used by the editor and
 * geometry algorithms. Surfaces vertices, edges, faces, and (where
 * supported) half-edges with stable integer ids and adjacency lookups.
 */
public interface MeshTopology {
    int NONE = -1;

    /**
     * Live vertex count for this mesh.
     *
     * @return number of live vertices in the mesh
     */
    int vertexCount();

    /**
     * Live edge count for this mesh.
     *
     * @return number of live edges in the mesh
     */
    int edgeCount();

    /**
     * Live face count for this mesh.
     *
     * @return number of live faces in the mesh
     */
    int faceCount();

    /**
     * Live half-edge count for this mesh.
     *
     * @return number of live half-edges (0 if the implementation does not store them)
     */
    int halfEdgeCount();

    /**
     * Vertex id at the given dense index in {@code [0, vertexCount())}.
     *
     * @param activeIndex packed index over live vertices
     * @return stable vertex id at that position
     */
    int vertexIdAt(int activeIndex);

    /**
     * Edge id at the given dense index in {@code [0, edgeCount())}.
     *
     * @param activeIndex packed index over live edges
     * @return stable edge id at that position
     */
    int edgeIdAt(int activeIndex);

    /**
     * Face id at the given dense index in {@code [0, faceCount())}.
     *
     * @param activeIndex packed index over live faces
     * @return stable face id at that position
     */
    int faceIdAt(int activeIndex);

    /**
     * Half-edge id at the given dense index in {@code [0, halfEdgeCount())}.
     *
     * @param activeIndex packed index over live half-edges
     * @return stable half-edge id at that position
     */
    int halfEdgeIdAt(int activeIndex);

    /**
     * Test whether the given id refers to a live vertex.
     *
     * @param vertexId candidate id
     * @return true if {@code vertexId} refers to a live vertex
     */
    boolean hasVertex(int vertexId);

    /**
     * Test whether the given id refers to a live edge.
     *
     * @param edgeId candidate id
     * @return true if {@code edgeId} refers to a live edge
     */
    boolean hasEdge(int edgeId);

    /**
     * Test whether the given id refers to a live face.
     *
     * @param faceId candidate id
     * @return true if {@code faceId} refers to a live face
     */
    boolean hasFace(int faceId);

    /**
     * Test whether the given id refers to a live half-edge.
     *
     * @param halfEdgeId candidate id
     * @return true if {@code halfEdgeId} refers to a live half-edge
     */
    boolean hasHalfEdge(int halfEdgeId);

    /**
     * Read the position of a vertex into {@code dest}.
     *
     * @param vertexId vertex to read
     * @param dest scratch vector to fill
     * @return {@code dest} for chaining
     */
    Vector3f vertexPosition(int vertexId, Vector3f dest);

    /**
     * Read the (cached) per-vertex normal into {@code dest}.
     *
     * @param vertexId vertex to read
     * @param dest scratch vector to fill
     * @return {@code dest} for chaining
     */
    Vector3f vertexNormal(int vertexId, Vector3f dest);

    /**
     * Pick a representative half-edge leaving {@code vertexId}.
     *
     * @param vertexId vertex to query
     * @return id of one outgoing half-edge, or {@link #NONE} if none
     */
    int vertexOutgoingHalfEdge(int vertexId);

    /**
     * Number of half-edges originating at {@code vertexId}.
     *
     * @param vertexId vertex to query
     * @return number of outgoing half-edges incident to {@code vertexId}
     */
    int vertexOutgoingHalfEdgeCount(int vertexId);

    /**
     * Outgoing half-edge of {@code vertexId} at a given adjacency slot.
     *
     * @param vertexId vertex to query
     * @param adjacencyIndex index in {@code [0, vertexOutgoingHalfEdgeCount(vertexId))}
     * @return outgoing half-edge id at that adjacency slot
     */
    int vertexOutgoingHalfEdgeAt(int vertexId, int adjacencyIndex);

    /**
     * Number of edges sharing {@code vertexId} as an endpoint.
     *
     * @param vertexId vertex to query
     * @return number of edges incident to {@code vertexId}
     */
    int vertexEdgeCount(int vertexId);

    /**
     * Incident edge of {@code vertexId} at a given adjacency slot.
     *
     * @param vertexId vertex to query
     * @param adjacencyIndex index in {@code [0, vertexEdgeCount(vertexId))}
     * @return incident edge id at that adjacency slot
     */
    int vertexEdgeAt(int vertexId, int adjacencyIndex);

    /**
     * Number of faces touching {@code vertexId}.
     *
     * @param vertexId vertex to query
     * @return number of faces incident to {@code vertexId}
     */
    int vertexFaceCount(int vertexId);

    /**
     * Incident face of {@code vertexId} at a given adjacency slot.
     *
     * @param vertexId vertex to query
     * @param adjacencyIndex index in {@code [0, vertexFaceCount(vertexId))}
     * @return incident face id at that adjacency slot
     */
    int vertexFaceAt(int vertexId, int adjacencyIndex);

    /**
     * Whether {@code vertexId} sits on the mesh boundary.
     *
     * @param vertexId vertex to query
     * @return true if any incident half-edge lies on the boundary
     */
    boolean isBoundaryVertex(int vertexId);

    /**
     * Pick a representative half-edge along {@code edgeId}.
     *
     * @param edgeId edge to query
     * @return id of one half-edge along {@code edgeId}
     */
    int edgeHalfEdge(int edgeId);

    /**
     * Whether {@code edgeId} sits on the mesh boundary.
     *
     * @param edgeId edge to query
     * @return true if either side of the edge has no incident face
     */
    boolean isBoundaryEdge(int edgeId);

    /**
     * Pick a representative bounding half-edge of {@code faceId}.
     *
     * @param faceId face to query
     * @return id of one half-edge bounding {@code faceId}
     */
    int faceHalfEdge(int faceId);

    /**
     * Number of half-edges that bound {@code faceId}.
     *
     * @param faceId face to query
     * @return number of half-edges around the face (= polygon side count)
     */
    int faceHalfEdgeCount(int faceId);

    /**
     * Bounding half-edge of {@code faceId} at a given winding slot.
     *
     * @param faceId face to query
     * @param adjacencyIndex index in {@code [0, faceHalfEdgeCount(faceId))}
     * @return bounding half-edge id at that slot, in face winding order
     */
    int faceHalfEdgeAt(int faceId, int adjacencyIndex);

    /**
     * Number of corner vertices of {@code faceId}.
     *
     * @param faceId face to query
     * @return number of corner vertices of the face
     */
    int faceVertexCount(int faceId);

    /**
     * Corner vertex of {@code faceId} at a given winding slot.
     *
     * @param faceId face to query
     * @param adjacencyIndex index in {@code [0, faceVertexCount(faceId))}
     * @return corner vertex id at that slot, in face winding order
     */
    int faceVertexAt(int faceId, int adjacencyIndex);

    /**
     * Number of edges that bound {@code faceId}.
     *
     * @param faceId face to query
     * @return number of edges bounding the face
     */
    int faceEdgeCount(int faceId);

    /**
     * Bounding edge of {@code faceId} at a given winding slot.
     *
     * @param faceId face to query
     * @param adjacencyIndex index in {@code [0, faceEdgeCount(faceId))}
     * @return bounding edge id at that slot, in face winding order
     */
    int faceEdgeAt(int faceId, int adjacencyIndex);

    /**
     * Read the (cached) per-face normal into {@code dest}.
     *
     * @param faceId face to read
     * @param dest scratch vector to fill
     * @return {@code dest} for chaining
     */
    Vector3f faceNormal(int faceId, Vector3f dest);

    /**
     * Source vertex of {@code halfEdgeId}.
     *
     * @param halfEdgeId half-edge to query
     * @return source (origin) vertex id of {@code halfEdgeId}
     */
    int halfEdgeVertex(int halfEdgeId);

    /**
     * Destination vertex of {@code halfEdgeId}.
     *
     * @param halfEdgeId half-edge to query
     * @return destination (target) vertex id of {@code halfEdgeId}
     */
    int halfEdgeEndVertex(int halfEdgeId);

    /**
     * Twin (opposite) half-edge of {@code halfEdgeId}.
     *
     * @param halfEdgeId half-edge to query
     * @return id of the opposite half-edge (or {@link #NONE} on boundary)
     */
    int halfEdgeTwin(int halfEdgeId);

    /**
     * Next half-edge in the same face loop.
     *
     * @param halfEdgeId half-edge to query
     * @return id of the next half-edge around the same face
     */
    int halfEdgeNext(int halfEdgeId);

    /**
     * Previous half-edge in the same face loop.
     *
     * @param halfEdgeId half-edge to query
     * @return id of the previous half-edge around the same face
     */
    int halfEdgePrev(int halfEdgeId);

    /**
     * Face that {@code halfEdgeId} belongs to (its left side).
     *
     * @param halfEdgeId half-edge to query
     * @return id of the face on this side of the half-edge (or {@link #NONE} on boundary)
     */
    int halfEdgeFace(int halfEdgeId);

    /**
     * Undirected edge that {@code halfEdgeId} belongs to.
     *
     * @param halfEdgeId half-edge to query
     * @return id of the underlying edge that pairs this half-edge and its twin
     */
    int halfEdgeEdge(int halfEdgeId);

    /**
     * Whether {@code halfEdgeId} lies on the mesh boundary.
     *
     * @param halfEdgeId half-edge to query
     * @return true if {@code halfEdgeId} has no incident face
     */
    boolean isBoundaryHalfEdge(int halfEdgeId);

    /**
     * Read the axis-aligned bounding-box minimum into {@code dest}.
     *
     * @param dest scratch vector to fill
     * @return {@code dest} for chaining
     */
    Vector3f boundsMin(Vector3f dest);

    /**
     * Read the axis-aligned bounding-box maximum into {@code dest}.
     *
     * @param dest scratch vector to fill
     * @return {@code dest} for chaining
     */
    Vector3f boundsMax(Vector3f dest);

    /**
     * Read the bounding-box center into {@code dest}.
     *
     * @param dest scratch vector to fill
     * @return {@code dest} for chaining
     */
    Vector3f center(Vector3f dest);

    /**
     * Convenience scale derived from the bounding box.
     *
     * @return half-length of the bounding-box diagonal (used as a default scene scale)
     */
    float radius();
}
