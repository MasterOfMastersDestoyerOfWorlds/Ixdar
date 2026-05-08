package ixdar.geometry.mesh.data;

import org.joml.Vector3f;

public interface MeshTopology {
    int NONE = -1;

    /**
     * TODO: document {@code vertexCount}.
     *
     * @return TODO: describe
     */
    int vertexCount();

    /**
     * TODO: document {@code edgeCount}.
     *
     * @return TODO: describe
     */
    int edgeCount();

    /**
     * TODO: document {@code faceCount}.
     *
     * @return TODO: describe
     */
    int faceCount();

    /**
     * TODO: document {@code halfEdgeCount}.
     *
     * @return TODO: describe
     */
    int halfEdgeCount();

    /**
     * TODO: document {@code vertexIdAt}.
     *
     * @param activeIndex TODO: describe
     * @return TODO: describe
     */
    int vertexIdAt(int activeIndex);

    /**
     * TODO: document {@code edgeIdAt}.
     *
     * @param activeIndex TODO: describe
     * @return TODO: describe
     */
    int edgeIdAt(int activeIndex);

    /**
     * TODO: document {@code faceIdAt}.
     *
     * @param activeIndex TODO: describe
     * @return TODO: describe
     */
    int faceIdAt(int activeIndex);

    /**
     * TODO: document {@code halfEdgeIdAt}.
     *
     * @param activeIndex TODO: describe
     * @return TODO: describe
     */
    int halfEdgeIdAt(int activeIndex);

    /**
     * TODO: document {@code hasVertex}.
     *
     * @param vertexId TODO: describe
     * @return TODO: describe
     */
    boolean hasVertex(int vertexId);

    /**
     * TODO: document {@code hasEdge}.
     *
     * @param edgeId TODO: describe
     * @return TODO: describe
     */
    boolean hasEdge(int edgeId);

    /**
     * TODO: document {@code hasFace}.
     *
     * @param faceId TODO: describe
     * @return TODO: describe
     */
    boolean hasFace(int faceId);

    /**
     * TODO: document {@code hasHalfEdge}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    boolean hasHalfEdge(int halfEdgeId);

    /**
     * TODO: document {@code vertexPosition}.
     *
     * @param vertexId TODO: describe
     * @param dest TODO: describe
     * @return TODO: describe
     */
    Vector3f vertexPosition(int vertexId, Vector3f dest);

    /**
     * TODO: document {@code vertexNormal}.
     *
     * @param vertexId TODO: describe
     * @param dest TODO: describe
     * @return TODO: describe
     */
    Vector3f vertexNormal(int vertexId, Vector3f dest);

    /**
     * TODO: document {@code vertexOutgoingHalfEdge}.
     *
     * @param vertexId TODO: describe
     * @return TODO: describe
     */
    int vertexOutgoingHalfEdge(int vertexId);

    /**
     * TODO: document {@code vertexOutgoingHalfEdgeCount}.
     *
     * @param vertexId TODO: describe
     * @return TODO: describe
     */
    int vertexOutgoingHalfEdgeCount(int vertexId);

    /**
     * TODO: document {@code vertexOutgoingHalfEdgeAt}.
     *
     * @param vertexId TODO: describe
     * @param adjacencyIndex TODO: describe
     * @return TODO: describe
     */
    int vertexOutgoingHalfEdgeAt(int vertexId, int adjacencyIndex);

    /**
     * TODO: document {@code vertexEdgeCount}.
     *
     * @param vertexId TODO: describe
     * @return TODO: describe
     */
    int vertexEdgeCount(int vertexId);

    /**
     * TODO: document {@code vertexEdgeAt}.
     *
     * @param vertexId TODO: describe
     * @param adjacencyIndex TODO: describe
     * @return TODO: describe
     */
    int vertexEdgeAt(int vertexId, int adjacencyIndex);

    /**
     * TODO: document {@code vertexFaceCount}.
     *
     * @param vertexId TODO: describe
     * @return TODO: describe
     */
    int vertexFaceCount(int vertexId);

    /**
     * TODO: document {@code vertexFaceAt}.
     *
     * @param vertexId TODO: describe
     * @param adjacencyIndex TODO: describe
     * @return TODO: describe
     */
    int vertexFaceAt(int vertexId, int adjacencyIndex);

    /**
     * TODO: document {@code isBoundaryVertex}.
     *
     * @param vertexId TODO: describe
     * @return TODO: describe
     */
    boolean isBoundaryVertex(int vertexId);

    /**
     * TODO: document {@code edgeHalfEdge}.
     *
     * @param edgeId TODO: describe
     * @return TODO: describe
     */
    int edgeHalfEdge(int edgeId);

    /**
     * TODO: document {@code isBoundaryEdge}.
     *
     * @param edgeId TODO: describe
     * @return TODO: describe
     */
    boolean isBoundaryEdge(int edgeId);

    /**
     * TODO: document {@code faceHalfEdge}.
     *
     * @param faceId TODO: describe
     * @return TODO: describe
     */
    int faceHalfEdge(int faceId);

    /**
     * TODO: document {@code faceHalfEdgeCount}.
     *
     * @param faceId TODO: describe
     * @return TODO: describe
     */
    int faceHalfEdgeCount(int faceId);

    /**
     * TODO: document {@code faceHalfEdgeAt}.
     *
     * @param faceId TODO: describe
     * @param adjacencyIndex TODO: describe
     * @return TODO: describe
     */
    int faceHalfEdgeAt(int faceId, int adjacencyIndex);

    /**
     * TODO: document {@code faceVertexCount}.
     *
     * @param faceId TODO: describe
     * @return TODO: describe
     */
    int faceVertexCount(int faceId);

    /**
     * TODO: document {@code faceVertexAt}.
     *
     * @param faceId TODO: describe
     * @param adjacencyIndex TODO: describe
     * @return TODO: describe
     */
    int faceVertexAt(int faceId, int adjacencyIndex);

    /**
     * TODO: document {@code faceEdgeCount}.
     *
     * @param faceId TODO: describe
     * @return TODO: describe
     */
    int faceEdgeCount(int faceId);

    /**
     * TODO: document {@code faceEdgeAt}.
     *
     * @param faceId TODO: describe
     * @param adjacencyIndex TODO: describe
     * @return TODO: describe
     */
    int faceEdgeAt(int faceId, int adjacencyIndex);

    /**
     * TODO: document {@code faceNormal}.
     *
     * @param faceId TODO: describe
     * @param dest TODO: describe
     * @return TODO: describe
     */
    Vector3f faceNormal(int faceId, Vector3f dest);

    /**
     * TODO: document {@code halfEdgeVertex}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    int halfEdgeVertex(int halfEdgeId);

    /**
     * TODO: document {@code halfEdgeEndVertex}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    int halfEdgeEndVertex(int halfEdgeId);

    /**
     * TODO: document {@code halfEdgeTwin}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    int halfEdgeTwin(int halfEdgeId);

    /**
     * TODO: document {@code halfEdgeNext}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    int halfEdgeNext(int halfEdgeId);

    /**
     * TODO: document {@code halfEdgePrev}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    int halfEdgePrev(int halfEdgeId);

    /**
     * TODO: document {@code halfEdgeFace}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    int halfEdgeFace(int halfEdgeId);

    /**
     * TODO: document {@code halfEdgeEdge}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    int halfEdgeEdge(int halfEdgeId);

    /**
     * TODO: document {@code isBoundaryHalfEdge}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    boolean isBoundaryHalfEdge(int halfEdgeId);

    /**
     * TODO: document {@code boundsMin}.
     *
     * @param dest TODO: describe
     * @return TODO: describe
     */
    Vector3f boundsMin(Vector3f dest);

    /**
     * TODO: document {@code boundsMax}.
     *
     * @param dest TODO: describe
     * @return TODO: describe
     */
    Vector3f boundsMax(Vector3f dest);

    /**
     * TODO: document {@code center}.
     *
     * @param dest TODO: describe
     * @return TODO: describe
     */
    Vector3f center(Vector3f dest);

    /**
     * TODO: document {@code radius}.
     *
     * @return TODO: describe
     */
    float radius();
}
