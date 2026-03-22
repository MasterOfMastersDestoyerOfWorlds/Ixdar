package ixdar.geometry.mesh.data;

import org.joml.Vector3f;

public interface MeshTopology {
    int NONE = -1;

    int vertexCount();

    int edgeCount();

    int faceCount();

    int halfEdgeCount();

    int vertexIdAt(int activeIndex);

    int edgeIdAt(int activeIndex);

    int faceIdAt(int activeIndex);

    int halfEdgeIdAt(int activeIndex);

    boolean hasVertex(int vertexId);

    boolean hasEdge(int edgeId);

    boolean hasFace(int faceId);

    boolean hasHalfEdge(int halfEdgeId);

    Vector3f vertexPosition(int vertexId, Vector3f dest);

    Vector3f vertexNormal(int vertexId, Vector3f dest);

    int vertexOutgoingHalfEdge(int vertexId);

    int vertexOutgoingHalfEdgeCount(int vertexId);

    int vertexOutgoingHalfEdgeAt(int vertexId, int adjacencyIndex);

    int vertexEdgeCount(int vertexId);

    int vertexEdgeAt(int vertexId, int adjacencyIndex);

    int vertexFaceCount(int vertexId);

    int vertexFaceAt(int vertexId, int adjacencyIndex);

    boolean isBoundaryVertex(int vertexId);

    int edgeHalfEdge(int edgeId);

    boolean isBoundaryEdge(int edgeId);

    int faceHalfEdge(int faceId);

    int faceHalfEdgeCount(int faceId);

    int faceHalfEdgeAt(int faceId, int adjacencyIndex);

    int faceVertexCount(int faceId);

    int faceVertexAt(int faceId, int adjacencyIndex);

    int faceEdgeCount(int faceId);

    int faceEdgeAt(int faceId, int adjacencyIndex);

    Vector3f faceNormal(int faceId, Vector3f dest);

    int halfEdgeVertex(int halfEdgeId);

    int halfEdgeEndVertex(int halfEdgeId);

    int halfEdgeTwin(int halfEdgeId);

    int halfEdgeNext(int halfEdgeId);

    int halfEdgePrev(int halfEdgeId);

    int halfEdgeFace(int halfEdgeId);

    int halfEdgeEdge(int halfEdgeId);

    boolean isBoundaryHalfEdge(int halfEdgeId);

    Vector3f boundsMin(Vector3f dest);

    Vector3f boundsMax(Vector3f dest);

    Vector3f center(Vector3f dest);

    float radius();
}
