package ixdar.geometry.mesh.data.representation;

import java.util.Arrays;

import org.joml.Vector3f;


/**
 * Topology-mutating operations on a {@link HalfEdgeMesh}: add/remove vertices,
 * edges, and faces; bulk allocation from indexed buffers; and quad subdivision.
 * All editing keeps the half-edge invariants (twin/next/prev, edge↔half-edge,
 * face↔half-edge) consistent.
 */
public class HalfEdgeMeshEngine {
    public static final String EDGE = "Edge ";
    public static final String POSITION_DATA_MUST_BE_XYZ_TRIPLES = "Position data must be XYZ triples";
    public static final String AND = " and ";

    /**
     * Allocates a new vertex slot at the given world-space position.
     *
     * @param mesh target mesh
     * @param x x coordinate
     * @param y y coordinate
     * @param z z coordinate
     * @return id of the newly created vertex
     */
    public static int addVertex(HalfEdgeMesh mesh, float x, float y, float z) {
        return mesh.createVertexSlot(x, y, z);
    }

    /**
     * Creates a fresh edge plus its two half-edges between the two existing vertices.
     *
     * @param mesh target mesh
     * @param startVertexId active vertex on one end
     * @param endVertexId active vertex on the other end (must differ from {@code startVertexId})
     * @return id of the newly created edge
     */
    public static int addEdge(HalfEdgeMesh mesh, int startVertexId, int endVertexId) {
        validateDistinctVertices(startVertexId, endVertexId);
        ensureDirectedEdgeAvailable(mesh, startVertexId, endVertexId);
        return createEdgePair(mesh, startVertexId, endVertexId);
    }

    /**
     * Adds a face without recomputing normals; call {@link #computeNormals} when the mesh is complete.
     *
     * @param mesh target mesh
     * @param vertexIds ordered vertex ids defining the face (length &ge; 3)
     * @return id of the newly created face
     */
    public static int addFace(HalfEdgeMesh mesh, int... vertexIds) {
        return addFaceInternal(mesh, vertexIds, false);
    }

    /**
     * Detaches a face from its half-edges, reaps any edges left isolated, and
     * recomputes normals.
     *
     * @param mesh target mesh
     * @param faceId active face id to remove
     */
    public static void removeFace(HalfEdgeMesh mesh, int faceId) {
        removeFaceKeepingNormals(mesh, faceId);
        computeNormals(mesh);
    }

    /**
     * Same as {@link #removeFace} but leaves normals stale. Callers performing
     * many local edits (incremental remeshing) batch a single
     * {@link #computeNormals} at the end instead of paying a full-mesh normal
     * pass per removal.
     *
     * @param mesh target mesh
     * @param faceId active face id to remove
     */
    public static void removeFaceKeepingNormals(HalfEdgeMesh mesh, int faceId) {
        int cornerCount = mesh.faceVertices.size(faceId);
        for (int index = 0; index < cornerCount; index++) {
            mesh.vertexFaces.removeValue(mesh.faceVertices.get(faceId, index), faceId);
        }

        int halfEdgeCount = mesh.faceHalfEdges.size(faceId);
        for (int index = 0; index < halfEdgeCount; index++) {
            int halfEdgeId = mesh.faceHalfEdges.get(faceId, index);
            mesh.halfEdgeFace[halfEdgeId] = MeshTopology.NONE;
            mesh.halfEdgeNext[halfEdgeId] = MeshTopology.NONE;
            mesh.halfEdgePrev[halfEdgeId] = MeshTopology.NONE;
        }

        int edgeCount = mesh.faceEdges.size(faceId);
        for (int index = 0; index < edgeCount; index++) {
            int edgeId = mesh.faceEdges.get(faceId, index);
            if (mesh.hasEdge(edgeId) && isIsolatedEdge(mesh, edgeId)) {
                removeEdge(mesh, edgeId);
            }
        }

        mesh.deactivateFace(faceId);
    }

    /**
     * Splits an edge of a triangle mesh in place: the edge keeps its id as the half toward its
     * canonical start vertex, and the new half and faces inherit the layer values of what they
     * split from.
     *
     * @param mesh   target mesh
     * @param edgeId active edge to split; each incident face must be a triangle
     * @param x      x coordinate of the split point
     * @param y      y coordinate of the split point
     * @param z      z coordinate of the split point
     * @throws InvalidMeshTopologyException if an incident face is not a triangle
     * @return id of the newly created vertex on the split point
     */
    public static int splitEdge(HalfEdgeMesh mesh, int edgeId, float x, float y, float z) {
        int halfEdge = mesh.edgeHalfEdge[edgeId];
        int twin = mesh.halfEdgeTwin[halfEdge];
        int vertexA = mesh.halfEdgeVertex[halfEdge];
        int vertexB = mesh.halfEdgeVertex[twin];
        int faceA = mesh.halfEdgeFace[halfEdge];
        int faceB = mesh.halfEdgeFace[twin];
        if (faceA != MeshTopology.NONE && mesh.faceVertexCount(faceA) != 3
                || faceB != MeshTopology.NONE && mesh.faceVertexCount(faceB) != 3) {
            throw new InvalidMeshTopologyException(EDGE + edgeId
                    + " cannot be split in place: an incident face is not a triangle");
        }
        int nextA = mesh.halfEdgeNext[halfEdge];
        int prevA = mesh.halfEdgePrev[halfEdge];
        int nextB = mesh.halfEdgeNext[twin];
        int prevB = mesh.halfEdgePrev[twin];

        int newVertex = mesh.createVertexSlot(x, y, z);

        mesh.halfEdgeVertex[twin] = newVertex;
        mesh.vertexOutgoingHalfEdges.removeValue(vertexB, twin);
        mesh.vertexOutgoingHalfEdges.add(newVertex, twin);
        mesh.vertexEdges.removeValue(vertexB, edgeId);
        mesh.vertexEdges.addUnique(newVertex, edgeId);
        if (mesh.vertexOutgoing[vertexB] == twin) {
            mesh.vertexOutgoing[vertexB] = MeshTopology.NONE;
        }
        mesh.vertexOutgoing[newVertex] = twin;

        int tailEdge = createEdgePair(mesh, newVertex, vertexB);
        int tailForward = mesh.edgeHalfEdge[tailEdge];
        int tailBackward = mesh.halfEdgeTwin[tailForward];
        for (int[] layer : mesh.edgeLayers) {
            layer[tailEdge] = layer[edgeId];
        }
        for (int layer = 0; layer < mesh.vertexLayers.length; layer++) {
            int splitEdgeLayer = mesh.vertexLayerSplitEdgeLayer[layer];
            if (splitEdgeLayer != MeshTopology.NONE) {
                mesh.vertexLayers[layer][newVertex] = mesh.edgeLayers[splitEdgeLayer][edgeId];
            }
        }

        if (faceA != MeshTopology.NONE) {
            int oppositeA = mesh.halfEdgeVertex[prevA];
            int spokeEdgeA = createEdgePair(mesh, newVertex, oppositeA);
            int spokeForwardA = mesh.edgeHalfEdge[spokeEdgeA];
            int spokeBackwardA = mesh.halfEdgeTwin[spokeForwardA];
            int newFaceA = mesh.createFaceSlot();
            for (int[] layer : mesh.faceLayers) {
                layer[newFaceA] = layer[faceA];
            }

            mesh.halfEdgeNext[halfEdge] = spokeForwardA;
            mesh.halfEdgePrev[spokeForwardA] = halfEdge;
            mesh.halfEdgeNext[spokeForwardA] = prevA;
            mesh.halfEdgePrev[prevA] = spokeForwardA;
            mesh.halfEdgeFace[spokeForwardA] = faceA;
            mesh.faceHalfEdge[faceA] = halfEdge;
            mesh.faceHalfEdges.clear(faceA);
            mesh.faceVertices.clear(faceA);
            mesh.faceEdges.clear(faceA);
            mesh.faceHalfEdges.add(faceA, halfEdge);
            mesh.faceHalfEdges.add(faceA, spokeForwardA);
            mesh.faceHalfEdges.add(faceA, prevA);
            mesh.faceVertices.add(faceA, vertexA);
            mesh.faceVertices.add(faceA, newVertex);
            mesh.faceVertices.add(faceA, oppositeA);
            mesh.faceEdges.add(faceA, edgeId);
            mesh.faceEdges.add(faceA, spokeEdgeA);
            mesh.faceEdges.add(faceA, mesh.halfEdgeEdge[prevA]);

            mesh.halfEdgeFace[tailForward] = newFaceA;
            mesh.halfEdgeFace[nextA] = newFaceA;
            mesh.halfEdgeFace[spokeBackwardA] = newFaceA;
            mesh.halfEdgeNext[tailForward] = nextA;
            mesh.halfEdgePrev[nextA] = tailForward;
            mesh.halfEdgeNext[nextA] = spokeBackwardA;
            mesh.halfEdgePrev[spokeBackwardA] = nextA;
            mesh.halfEdgeNext[spokeBackwardA] = tailForward;
            mesh.halfEdgePrev[tailForward] = spokeBackwardA;
            mesh.faceHalfEdge[newFaceA] = tailForward;
            mesh.faceHalfEdges.add(newFaceA, tailForward);
            mesh.faceHalfEdges.add(newFaceA, nextA);
            mesh.faceHalfEdges.add(newFaceA, spokeBackwardA);
            mesh.faceVertices.add(newFaceA, newVertex);
            mesh.faceVertices.add(newFaceA, vertexB);
            mesh.faceVertices.add(newFaceA, oppositeA);
            mesh.faceEdges.add(newFaceA, tailEdge);
            mesh.faceEdges.add(newFaceA, mesh.halfEdgeEdge[nextA]);
            mesh.faceEdges.add(newFaceA, spokeEdgeA);

            mesh.vertexFaces.removeValue(vertexB, faceA);
            mesh.vertexFaces.add(vertexB, newFaceA);
            mesh.vertexFaces.add(newVertex, faceA);
            mesh.vertexFaces.add(newVertex, newFaceA);
            mesh.vertexFaces.add(oppositeA, newFaceA);
        }

        if (faceB != MeshTopology.NONE) {
            int oppositeB = mesh.halfEdgeVertex[prevB];
            int spokeEdgeB = createEdgePair(mesh, newVertex, oppositeB);
            int spokeForwardB = mesh.edgeHalfEdge[spokeEdgeB];
            int spokeBackwardB = mesh.halfEdgeTwin[spokeForwardB];
            int newFaceB = mesh.createFaceSlot();
            for (int[] layer : mesh.faceLayers) {
                layer[newFaceB] = layer[faceB];
            }

            mesh.halfEdgeNext[nextB] = spokeBackwardB;
            mesh.halfEdgePrev[spokeBackwardB] = nextB;
            mesh.halfEdgeNext[spokeBackwardB] = twin;
            mesh.halfEdgePrev[twin] = spokeBackwardB;
            mesh.halfEdgeFace[spokeBackwardB] = faceB;
            mesh.faceHalfEdge[faceB] = twin;
            mesh.faceHalfEdges.clear(faceB);
            mesh.faceVertices.clear(faceB);
            mesh.faceEdges.clear(faceB);
            mesh.faceHalfEdges.add(faceB, twin);
            mesh.faceHalfEdges.add(faceB, nextB);
            mesh.faceHalfEdges.add(faceB, spokeBackwardB);
            mesh.faceVertices.add(faceB, newVertex);
            mesh.faceVertices.add(faceB, vertexA);
            mesh.faceVertices.add(faceB, oppositeB);
            mesh.faceEdges.add(faceB, edgeId);
            mesh.faceEdges.add(faceB, mesh.halfEdgeEdge[nextB]);
            mesh.faceEdges.add(faceB, spokeEdgeB);

            mesh.halfEdgeFace[tailBackward] = newFaceB;
            mesh.halfEdgeFace[spokeForwardB] = newFaceB;
            mesh.halfEdgeFace[prevB] = newFaceB;
            mesh.halfEdgeNext[tailBackward] = spokeForwardB;
            mesh.halfEdgePrev[spokeForwardB] = tailBackward;
            mesh.halfEdgeNext[spokeForwardB] = prevB;
            mesh.halfEdgePrev[prevB] = spokeForwardB;
            mesh.halfEdgeNext[prevB] = tailBackward;
            mesh.halfEdgePrev[tailBackward] = prevB;
            mesh.faceHalfEdge[newFaceB] = tailBackward;
            mesh.faceHalfEdges.add(newFaceB, tailBackward);
            mesh.faceHalfEdges.add(newFaceB, spokeForwardB);
            mesh.faceHalfEdges.add(newFaceB, prevB);
            mesh.faceVertices.add(newFaceB, vertexB);
            mesh.faceVertices.add(newFaceB, newVertex);
            mesh.faceVertices.add(newFaceB, oppositeB);
            mesh.faceEdges.add(newFaceB, tailEdge);
            mesh.faceEdges.add(newFaceB, spokeEdgeB);
            mesh.faceEdges.add(newFaceB, mesh.halfEdgeEdge[prevB]);

            mesh.vertexFaces.removeValue(vertexB, faceB);
            mesh.vertexFaces.add(vertexB, newFaceB);
            mesh.vertexFaces.add(newVertex, faceB);
            mesh.vertexFaces.add(newVertex, newFaceB);
            mesh.vertexFaces.add(oppositeB, newFaceB);
        }

        if (mesh.vertexOutgoing[vertexB] == MeshTopology.NONE) {
            mesh.vertexOutgoing[vertexB] = mesh.vertexOutgoingHalfEdges.isEmpty(vertexB)
                    ? MeshTopology.NONE
                    : mesh.vertexOutgoingHalfEdges.get(vertexB, 0);
        }
        return newVertex;
    }

    /**
     * Splits a face into a fan of triangles around a new vertex, one per side. The face's id is
     * retired and the fan takes fresh consecutive ids, each inheriting the face's layer values.
     * Normals are left stale.
     *
     * @param mesh   target mesh
     * @param faceId active face to split
     * @param x      x coordinate of the new vertex
     * @param y      y coordinate of the new vertex
     * @param z      z coordinate of the new vertex
     * @return id of the new vertex
     */
    public static int splitFace(HalfEdgeMesh mesh, int faceId, float x, float y, float z) {
        int cornerCount = mesh.faceVertexCount(faceId);
        int newVertex = mesh.createVertexSlot(x, y, z);
        int[] fan = new int[cornerCount * HalfEdgeMesh.TRIANGLE_CORNERS];
        for (int corner = 0; corner < cornerCount; corner++) {
            fan[corner * HalfEdgeMesh.TRIANGLE_CORNERS] = mesh.faceVertexAt(faceId, corner);
            fan[corner * HalfEdgeMesh.TRIANGLE_CORNERS + 1] = mesh.faceVertexAt(faceId,
                    (corner + 1) % cornerCount);
            fan[corner * HalfEdgeMesh.TRIANGLE_CORNERS + 2] = newVertex;
        }
        replaceFaces(mesh, faceId, new int[] { faceId }, fan);
        return newVertex;
    }

    /**
     * Replaces the diagonal two triangles share by their other diagonal. Both triangles retire
     * and the two new ones take fresh ids, each inheriting the layer values of the face on the
     * edge's canonical half-edge; the new diagonal is a fresh edge with the layers' fill.
     * Normals are left stale.
     *
     * @param mesh   target mesh
     * @param edgeId interior edge between two triangles
     * @throws InvalidMeshTopologyException when the edge is on the boundary, a face is not a
     *         triangle, or the opposite corners are already joined
     * @return id of the new diagonal
     */
    public static int flipEdge(HalfEdgeMesh mesh, int edgeId) {
        int halfEdge = mesh.edgeHalfEdge[edgeId];
        int twin = mesh.halfEdgeTwin[halfEdge];
        int faceA = mesh.halfEdgeFace[halfEdge];
        int faceB = mesh.halfEdgeFace[twin];
        if (faceA == MeshTopology.NONE || faceB == MeshTopology.NONE
                || mesh.faceVertexCount(faceA) != 3 || mesh.faceVertexCount(faceB) != 3) {
            throw new InvalidMeshTopologyException(EDGE + edgeId
                    + " cannot be flipped: it does not join two triangles");
        }
        int vertexA = mesh.halfEdgeVertex[halfEdge];
        int vertexB = mesh.halfEdgeVertex[twin];
        int vertexC = mesh.halfEdgeVertex[mesh.halfEdgePrev[halfEdge]];
        int vertexD = mesh.halfEdgeVertex[mesh.halfEdgePrev[twin]];
        if (vertexC == vertexD || findHalfEdge(mesh, vertexC, vertexD) != MeshTopology.NONE) {
            throw new InvalidMeshTopologyException(EDGE + edgeId + " cannot be flipped: its"
                    + " opposite corners " + vertexC + AND + vertexD + " are already joined");
        }
        replaceFaces(mesh, faceA, new int[] { faceA, faceB },
                new int[] { vertexA, vertexD, vertexC, vertexD, vertexB, vertexC });
        return mesh.halfEdgeEdge[findHalfEdge(mesh, vertexC, vertexD)];
    }

    /**
     * Collapses an edge onto its canonical start vertex, which moves and keeps its id and
     * values; the end vertex's other faces are rebuilt on it with fresh ids, inheriting what
     * they replace. Normals are left stale.
     *
     * @param mesh   target mesh
     * @param edgeId edge to collapse; the faces on it must be triangles
     * @param x      x coordinate of the kept vertex afterwards
     * @param y      y coordinate of the kept vertex afterwards
     * @param z      z coordinate of the kept vertex afterwards
     * @throws InvalidMeshTopologyException when a face on the edge is not a triangle or the
     *         collapse would leave the mesh non-manifold: the endpoints share a neighbour other
     *         than the edge's opposite corners, an interior edge joins two boundary vertices, or
     *         a face would be duplicated
     * @return id of the kept vertex
     */
    public static int collapseEdge(HalfEdgeMesh mesh, int edgeId, float x, float y, float z) {
        int halfEdge = mesh.edgeHalfEdge[edgeId];
        int twin = mesh.halfEdgeTwin[halfEdge];
        int kept = mesh.halfEdgeVertex[halfEdge];
        int removed = mesh.halfEdgeVertex[twin];
        int faceA = mesh.halfEdgeFace[halfEdge];
        int faceB = mesh.halfEdgeFace[twin];
        if (faceA != MeshTopology.NONE && mesh.faceVertexCount(faceA) != 3
                || faceB != MeshTopology.NONE && mesh.faceVertexCount(faceB) != 3) {
            throw new InvalidMeshTopologyException(EDGE + edgeId
                    + " cannot be collapsed: a face on it is not a triangle");
        }
        int oppositeA = faceA == MeshTopology.NONE ? MeshTopology.NONE
                : mesh.halfEdgeVertex[mesh.halfEdgePrev[halfEdge]];
        int oppositeB = faceB == MeshTopology.NONE ? MeshTopology.NONE
                : mesh.halfEdgeVertex[mesh.halfEdgePrev[twin]];
        for (int index = 0; index < mesh.vertexEdges.size(removed); index++) {
            int neighbourHalfEdge = mesh.edgeHalfEdge[mesh.vertexEdges.get(removed, index)];
            int neighbour = mesh.halfEdgeVertex[neighbourHalfEdge] == removed
                    ? mesh.halfEdgeVertex[mesh.halfEdgeTwin[neighbourHalfEdge]]
                    : mesh.halfEdgeVertex[neighbourHalfEdge];
            if (neighbour != kept && neighbour != oppositeA && neighbour != oppositeB
                    && findHalfEdge(mesh, kept, neighbour) != MeshTopology.NONE) {
                throw new InvalidMeshTopologyException(EDGE + edgeId + " cannot be collapsed:"
                        + " its endpoints share the neighbour " + neighbour);
            }
        }
        if (faceA != MeshTopology.NONE && faceB != MeshTopology.NONE
                && mesh.isBoundaryVertex(kept) && mesh.isBoundaryVertex(removed)) {
            throw new InvalidMeshTopologyException(EDGE + edgeId + " cannot be collapsed: it is"
                    + " interior but joins two boundary vertices");
        }
        int[] fan = new int[mesh.vertexFaces.size(removed)];
        int[] fanStarts = new int[fan.length + 1];
        for (int index = 0; index < fan.length; index++) {
            fan[index] = mesh.vertexFaces.get(removed, index);
            fanStarts[index + 1] = fanStarts[index] + mesh.faceVertexCount(fan[index]);
        }
        int[] fanCorners = new int[fanStarts[fan.length]];
        for (int index = 0; index < fan.length; index++) {
            int faceId = fan[index];
            int sharedCorners = 0;
            for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                int vertexId = mesh.faceVertexAt(faceId, corner);
                fanCorners[fanStarts[index] + corner] = vertexId == removed ? kept : vertexId;
                sharedCorners += vertexId == oppositeA || vertexId == oppositeB ? 1 : 0;
            }
            if (faceId != faceA && faceId != faceB && sharedCorners == 2
                    && mesh.faceVertexCount(faceId) == 3) {
                throw new InvalidMeshTopologyException(EDGE + edgeId + " cannot be collapsed:"
                        + " face " + faceId + " would duplicate a face of the kept vertex");
            }
        }
        recordReplacedEdges(mesh, fan);
        for (int faceId : fan) {
            removeFaceKeepingNormals(mesh, faceId);
        }
        removeVertex(mesh, removed);
        setVector(mesh.vertexPositions, kept, x, y, z);
        int edgeIdBound = mesh.nextEdgeId;
        int firstFaceId = mesh.nextFaceId;
        for (int index = 0; index < fan.length; index++) {
            if (fan[index] == faceA || fan[index] == faceB) {
                continue;
            }
            int newFace = addFace(mesh,
                    Arrays.copyOfRange(fanCorners, fanStarts[index], fanStarts[index + 1]));
            for (int[] layer : mesh.faceLayers) {
                layer[newFace] = layer[fan[index]];
            }
        }
        restoreReplacedEdgeLayers(mesh, edgeIdBound, firstFaceId, removed, kept);
        return kept;
    }

    /**
     * Replaces faces by triangles under fresh consecutive ids that inherit the parent face's
     * layer values. Normals are left stale.
     *
     * @param mesh            target mesh
     * @param parentFaceId    face whose layer values every new face inherits, retired or not
     * @param retiredFaceIds  active faces to remove, in removal order
     * @param triangleCorners corners of the new triangles, three per face, in creation order
     * @throws InvalidMeshTopologyException when a new triangle is degenerate or non-manifold,
     *         leaving the faces added before it in place
     * @return id of the first new face; the rest follow consecutively
     */
    public static int replaceFaces(HalfEdgeMesh mesh, int parentFaceId, int[] retiredFaceIds,
            int[] triangleCorners) {
        recordReplacedEdges(mesh, retiredFaceIds);
        for (int faceId : retiredFaceIds) {
            removeFaceKeepingNormals(mesh, faceId);
        }
        int edgeIdBound = mesh.nextEdgeId;
        int firstFaceId = mesh.nextFaceId;
        for (int corner = 0; corner < triangleCorners.length; corner += HalfEdgeMesh.TRIANGLE_CORNERS) {
            int faceId = addFace(mesh, triangleCorners[corner], triangleCorners[corner + 1],
                    triangleCorners[corner + 2]);
            for (int[] layer : mesh.faceLayers) {
                layer[faceId] = layer[parentFaceId];
            }
        }
        restoreReplacedEdgeLayers(mesh, edgeIdBound, firstFaceId, MeshTopology.NONE,
                MeshTopology.NONE);
        return firstFaceId;
    }

    /**
     * Records every edge of the faces about to be replaced with its endpoints, which removal
     * erases, so {@link #restoreReplacedEdgeLayers} can match re-created edges. Records
     * nothing when the mesh declares no edge layer.
     *
     * @param mesh    target mesh
     * @param faceIds faces about to be removed
     */
    private static void recordReplacedEdges(HalfEdgeMesh mesh, int[] faceIds) {
        mesh.replacedEdgeSize = 0;
        if (mesh.edgeLayers.length == 0) {
            return;
        }
        for (int faceId : faceIds) {
            int edgeCount = mesh.faceEdges.size(faceId);
            if (mesh.replacedEdgeIds.length < mesh.replacedEdgeSize + edgeCount) {
                int capacity = HalfEdgeMesh.nextCapacity(mesh.replacedEdgeIds.length,
                        mesh.replacedEdgeSize + edgeCount);
                mesh.replacedEdgeIds = Arrays.copyOf(mesh.replacedEdgeIds, capacity);
                mesh.replacedEdgeStarts = Arrays.copyOf(mesh.replacedEdgeStarts, capacity);
                mesh.replacedEdgeEnds = Arrays.copyOf(mesh.replacedEdgeEnds, capacity);
            }
            for (int index = 0; index < edgeCount; index++) {
                int edgeId = mesh.faceEdges.get(faceId, index);
                int halfEdge = mesh.edgeHalfEdge[edgeId];
                mesh.replacedEdgeIds[mesh.replacedEdgeSize] = edgeId;
                mesh.replacedEdgeStarts[mesh.replacedEdgeSize] = mesh.halfEdgeVertex[halfEdge];
                mesh.replacedEdgeEnds[mesh.replacedEdgeSize] = mesh.halfEdgeVertex[mesh.halfEdgeTwin[halfEdge]];
                mesh.replacedEdgeSize++;
            }
        }
    }

    /**
     * Gives every edge created since {@code edgeIdBound} on the faces created since
     * {@code firstFaceId} the layer values of a recorded edge joining the same two vertices,
     * one recorded at {@code renamedVertex} counting as at {@code survivingVertex}. An exact
     * match wins over a renamed one; an edge with no match keeps the fill.
     *
     * @param mesh            target mesh
     * @param edgeIdBound     first edge id the edit created
     * @param firstFaceId     first face id the edit created
     * @param renamedVertex   vertex the edit merged away, or {@link MeshTopology#NONE}
     * @param survivingVertex vertex it merged into, or {@link MeshTopology#NONE}
     */
    private static void restoreReplacedEdgeLayers(HalfEdgeMesh mesh, int edgeIdBound,
            int firstFaceId, int renamedVertex, int survivingVertex) {
        if (mesh.replacedEdgeSize == 0) {
            return;
        }
        for (int faceId = firstFaceId; faceId < mesh.nextFaceId; faceId++) {
            for (int index = 0; index < mesh.faceEdges.size(faceId); index++) {
                int edgeId = mesh.faceEdges.get(faceId, index);
                if (edgeId < edgeIdBound) {
                    continue;
                }
                int halfEdge = mesh.edgeHalfEdge[edgeId];
                int start = mesh.halfEdgeVertex[halfEdge];
                int end = mesh.halfEdgeVertex[mesh.halfEdgeTwin[halfEdge]];
                int match = MeshTopology.NONE;
                for (int record = 0; record < mesh.replacedEdgeSize; record++) {
                    int recordStart = mesh.replacedEdgeStarts[record];
                    int recordEnd = mesh.replacedEdgeEnds[record];
                    boolean exact = recordStart == start && recordEnd == end
                            || recordStart == end && recordEnd == start;
                    if (exact) {
                        match = mesh.replacedEdgeIds[record];
                        break;
                    }
                    recordStart = recordStart == renamedVertex ? survivingVertex : recordStart;
                    recordEnd = recordEnd == renamedVertex ? survivingVertex : recordEnd;
                    if (match == MeshTopology.NONE && (recordStart == start && recordEnd == end
                            || recordStart == end && recordEnd == start)) {
                        match = mesh.replacedEdgeIds[record];
                    }
                }
                if (match != MeshTopology.NONE) {
                    for (int[] layer : mesh.edgeLayers) {
                        layer[edgeId] = layer[match];
                    }
                }
            }
        }
        mesh.replacedEdgeSize = 0;
    }

    /**
     * Removes an edge that is no longer attached to any face; both half-edges are
     * deactivated and unregistered from the directed-edge map.
     *
     * @param mesh target mesh
     * @param edgeId active edge id with no incident faces
     * @throws InvalidMeshTopologyException if the edge is incomplete or still attached to a face
     */
    public static void removeEdge(HalfEdgeMesh mesh, int edgeId) {

        int firstHalfEdge = mesh.edgeHalfEdge[edgeId];
        int secondHalfEdge = mesh.halfEdgeTwin[firstHalfEdge];
        if (firstHalfEdge == MeshTopology.NONE || secondHalfEdge == MeshTopology.NONE) {
            throw new InvalidMeshTopologyException(EDGE + edgeId + " is incomplete");
        }
        if (mesh.halfEdgeFace[firstHalfEdge] != MeshTopology.NONE || mesh.halfEdgeFace[secondHalfEdge] != MeshTopology.NONE) {
            throw new InvalidMeshTopologyException(EDGE + edgeId + " still belongs to a face");
        }

        unregisterHalfEdge(mesh, firstHalfEdge);
        unregisterHalfEdge(mesh, secondHalfEdge);
        mesh.deactivateEdge(edgeId);
    }

    /**
     * Deactivates an isolated vertex (no incident half-edges, edges, or faces).
     *
     * @param mesh target mesh
     * @param vertexId active vertex id
     * @throws InvalidMeshTopologyException if the vertex is still connected to any topology
     */
    public static void removeVertex(HalfEdgeMesh mesh, int vertexId) {
        if (!mesh.vertexOutgoingHalfEdges.isEmpty(vertexId)
                || !mesh.vertexEdges.isEmpty(vertexId)
                || !mesh.vertexFaces.isEmpty(vertexId)) {
            throw new InvalidMeshTopologyException("Vertex " + vertexId + " is still connected");
        }
        mesh.deactivateVertex(vertexId);
    }

    /**
     * Recomputes per-face and per-vertex normals in place. Face normal is the
     * unit cross of the first two edges; vertex normals accumulate area-weighted
     * face normals (via the unnormalized cross product) and are then normalized.
     *
     * @param mesh target mesh
     */
    public static void computeNormals(HalfEdgeMesh mesh) {
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f edgeA = new Vector3f();
        Vector3f edgeB = new Vector3f();
        Vector3f areaNormal = new Vector3f();

        for (int i = 0; i < mesh.vertexCount(); i++) {
            int vertexId = mesh.vertexIdAt(i);
            setVector(mesh.vertexNormals, vertexId, 0f, 0f, 0f);
        }

        for (int i = 0; i < mesh.faceCount(); i++) {
            int faceId = mesh.faceIdAt(i);
            setVector(mesh.faceNormals, faceId, 0f, 0f, 0f);
            if (mesh.faceVertexCount(faceId) < 3) {
                continue;
            }

            mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), p0);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 1), p1);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 2), p2);
            edgeA.set(p1).sub(p0);
            edgeB.set(p2).sub(p0);
            edgeA.cross(edgeB, areaNormal);
            if (areaNormal.lengthSquared() == 0f) {
                continue;
            }

            Vector3f normalized = new Vector3f(areaNormal).normalize();
            setVector(mesh.faceNormals, faceId, normalized.x, normalized.y, normalized.z);
            for (int j = 0; j < mesh.faceVertexCount(faceId); j++) {
                int vertexId = mesh.faceVertexAt(faceId, j);
                addVector(mesh.vertexNormals, vertexId, areaNormal.x, areaNormal.y, areaNormal.z);
            }
        }

        for (int i = 0; i < mesh.vertexCount(); i++) {
            int vertexId = mesh.vertexIdAt(i);
            int offset = mesh.vertexOffset(vertexId);
            p0.set(mesh.vertexNormals[offset], mesh.vertexNormals[offset + 1], mesh.vertexNormals[offset + 2]);
            if (p0.lengthSquared() > 0f) {
                p0.normalize();
                setVector(mesh.vertexNormals, vertexId, p0.x, p0.y, p0.z);
            }
        }
    }

    /**
     * Builds a triangle {@link HalfEdgeMesh} from packed positions and a flat triangle
     * index buffer, computing normals.
     *
     * @param positions packed xyz triples
     * @param faceIndices vertex indices in groups of three
     * @throws IllegalArgumentException if {@code positions.length} is not divisible by 3 or
     *         {@code faceIndices.length} is not divisible by 3
     * @return populated half-edge mesh with normals computed
     */
    public static HalfEdgeMesh buildFromIndexedMesh(float[] positions, int[] faceIndices) {
        if (positions.length % HalfEdgeMesh.FLOATS_PER_VERTEX != 0) {
            throw new IllegalArgumentException(POSITION_DATA_MUST_BE_XYZ_TRIPLES);
        }
        if (faceIndices.length % 3 != 0) {
            throw new IllegalArgumentException("Face indices must be triangles");
        }
        HalfEdgeMesh mesh = packedBuild(positions, uniformFaceStarts(faceIndices.length, 3),
                faceIndices);
        computeNormals(mesh);
        return mesh;
    }

    /**
     * Pre-sized mesh build for faces of uniform vertex count (3 for triangles, 4 for quads).
     * For closed manifolds the edge/half-edge counts are derived exactly from total face vertices.
     * Does not compute normals — caller must call {@link HalfEdgeMesh#computeNormals()} when ready.
     *
     * @param positions    xyz triples
     * @param faceIndices  flat array of vertex indices, grouped by {@code vertsPerFace}
     * @param vertsPerFace vertices per face (3 or 4)
     * @throws IllegalArgumentException if positions are not xyz triples or the index buffer
     *         is not a multiple of {@code vertsPerFace}
     * @return populated half-edge mesh; caller must invoke {@code computeNormals()} when ready
     */
    public static HalfEdgeMesh bulkAllocate(float[] positions, int[] faceIndices, int vertsPerFace) {
        if (positions.length % HalfEdgeMesh.FLOATS_PER_VERTEX != 0) {
            throw new IllegalArgumentException(POSITION_DATA_MUST_BE_XYZ_TRIPLES);
        }
        if (faceIndices.length % vertsPerFace != 0) {
            throw new IllegalArgumentException("Face indices must be groups of " + vertsPerFace);
        }
        return packedBuild(positions, uniformFaceStarts(faceIndices.length, vertsPerFace),
                faceIndices);
    }

    /**
     * Bulk-allocate with mixed polygon sizes. Each face's vertex count is given by
     * {@code faceVertexCounts[i]}, and {@code faceIndicesFlat} packs all faces' indices
     * end-to-end. Use {@link #bulkAllocate} instead when every face has the same size.
     *
     * @param positions packed xyz triples
     * @param faceVertexCounts per-face vertex count (each &ge; 3)
     * @param faceIndicesFlat all face indices concatenated end-to-end
     * @throws IllegalArgumentException if positions are not xyz triples, any face count is
     *         &lt; 3, or the flat buffer length disagrees with the sum of face counts
     * @return populated half-edge mesh; normals are not computed
     */
    public static HalfEdgeMesh bulkAllocateMixed(float[] positions,
                                                 int[] faceVertexCounts,
                                                 int[] faceIndicesFlat) {
        if (positions.length % HalfEdgeMesh.FLOATS_PER_VERTEX != 0) {
            throw new IllegalArgumentException(POSITION_DATA_MUST_BE_XYZ_TRIPLES);
        }
        int totalFaceVerts = 0;
        for (int c : faceVertexCounts) {
            if (c < 3) {
                throw new IllegalArgumentException("Face vertex count must be >= 3, got " + c);
            }
            totalFaceVerts += c;
        }
        if (totalFaceVerts != faceIndicesFlat.length) {
            throw new IllegalArgumentException(
                    "Flat face indices length " + faceIndicesFlat.length
                            + " does not match sum of face vertex counts " + totalFaceVerts);
        }
        int[] faceStarts = new int[faceVertexCounts.length + 1];
        for (int faceId = 0; faceId < faceVertexCounts.length; faceId++) {
            faceStarts[faceId + 1] = faceStarts[faceId] + faceVertexCounts[faceId];
        }
        return packedBuild(positions, faceStarts, faceIndicesFlat);
    }

    /**
     * Face starts for faces that all have the same corner count.
     *
     * @param cornerCount  total corners
     * @param faceCorners  corners per face
     * @return {@code cornerCount / faceCorners + 1} offsets, a multiple of {@code faceCorners} each
     */
    private static int[] uniformFaceStarts(int cornerCount, int faceCorners) {
        int[] faceStarts = new int[cornerCount / faceCorners + 1];
        for (int faceId = 1; faceId < faceStarts.length; faceId++) {
            faceStarts[faceId] = faceId * faceCorners;
        }
        return faceStarts;
    }

    /**
     * Builds a half-edge mesh in linear passes over whole-mesh arrays, identical id for id to
     * adding every vertex and then every face through {@link #addFace}. Normals are left zero.
     *
     * @param positions   packed xyz triples
     * @param faceStarts  {@code faceCount + 1} offsets of each face's first corner in
     *                    {@code faceIndices}
     * @param faceIndices every face's vertex ids, end to end
     * @throws InvalidMeshTopologyException when a corner names a missing vertex, an edge is
     *         degenerate, or directed half-edges collide; a collision lists every offender
     * @return the populated mesh, every adjacency row packed
     */
    private static HalfEdgeMesh packedBuild(float[] positions, int[] faceStarts, int[] faceIndices) {
        int vertexCount = positions.length / HalfEdgeMesh.FLOATS_PER_VERTEX;
        int faceCount = faceStarts.length - 1;
        int cornerCount = faceIndices.length;

        int[] cornerEnd = new int[cornerCount];
        int[] lowVertexStart = new int[vertexCount + 1];
        int[] faceRowStart = new int[vertexCount + 1];
        for (int faceId = 0; faceId < faceCount; faceId++) {
            int first = faceStarts[faceId];
            int end = faceStarts[faceId + 1];
            for (int corner = first; corner < end; corner++) {
                int startVertex = faceIndices[corner];
                int endVertex = faceIndices[corner + 1 < end ? corner + 1 : first];
                if (startVertex < 0 || startVertex >= vertexCount
                        || endVertex < 0 || endVertex >= vertexCount) {
                    throw new InvalidMeshTopologyException("Face " + faceId
                            + " names a vertex outside the " + vertexCount + " given");
                }
                validateDistinctVertices(startVertex, endVertex);
                cornerEnd[corner] = endVertex;
                lowVertexStart[Math.min(startVertex, endVertex) + 1]++;
                faceRowStart[startVertex + 1]++;
            }
        }
        for (int vertexId = 0; vertexId < vertexCount; vertexId++) {
            lowVertexStart[vertexId + 1] += lowVertexStart[vertexId];
            faceRowStart[vertexId + 1] += faceRowStart[vertexId];
        }
        int[] cornersByLowVertex = new int[cornerCount];
        int[] highVertexBySlot = new int[cornerCount];
        boolean[] runsUpwardBySlot = new boolean[cornerCount];
        int[] fillCursor = Arrays.copyOf(lowVertexStart, vertexCount);
        for (int corner = 0; corner < cornerCount; corner++) {
            int startVertex = faceIndices[corner];
            int endVertex = cornerEnd[corner];
            int slot = fillCursor[Math.min(startVertex, endVertex)]++;
            cornersByLowVertex[slot] = corner;
            highVertexBySlot[slot] = Math.max(startVertex, endVertex);
            runsUpwardBySlot[slot] = startVertex < endVertex;
        }

        int[] firstCornerOfEdge = new int[cornerCount];
        int[] highVertexSeenAtLow = fillCursor;
        Arrays.fill(highVertexSeenAtLow, MeshTopology.NONE);
        int[] firstCornerAtHighVertex = new int[vertexCount];
        boolean[] firstRunsUpwardAtHighVertex = new boolean[vertexCount];
        boolean[] pairedAtHighVertex = new boolean[vertexCount];
        IntIdList collidingCorners = new IntIdList(0);
        int edgeCount = 0;
        for (int lowVertex = 0; lowVertex < vertexCount; lowVertex++) {
            for (int slot = lowVertexStart[lowVertex]; slot < lowVertexStart[lowVertex + 1]; slot++) {
                int corner = cornersByLowVertex[slot];
                int highVertex = highVertexBySlot[slot];
                if (highVertexSeenAtLow[highVertex] != lowVertex) {
                    highVertexSeenAtLow[highVertex] = lowVertex;
                    firstCornerAtHighVertex[highVertex] = corner;
                    firstRunsUpwardAtHighVertex[highVertex] = runsUpwardBySlot[slot];
                    pairedAtHighVertex[highVertex] = false;
                    firstCornerOfEdge[corner] = corner;
                    edgeCount++;
                } else if (!pairedAtHighVertex[highVertex]
                        && firstRunsUpwardAtHighVertex[highVertex] != runsUpwardBySlot[slot]) {
                    pairedAtHighVertex[highVertex] = true;
                    firstCornerOfEdge[corner] = firstCornerAtHighVertex[highVertex];
                } else {
                    firstCornerOfEdge[corner] = MeshTopology.NONE;
                    collidingCorners.add(corner);
                }
            }
        }
        if (!collidingCorners.isEmpty()) {
            int[] colliding = collidingCorners.toArray();
            Arrays.sort(colliding);
            StringBuilder message = new StringBuilder().append(colliding.length)
                    .append(" non-manifold or duplicate directed half-edges:");
            for (int corner : colliding) {
                message.append(' ').append(faceIndices[corner]).append("->").append(cornerEnd[corner]);
            }
            throw new InvalidMeshTopologyException(message.toString());
        }

        int halfEdgeCount = 2 * edgeCount;
        int[] halfEdgeOfCorner = new int[cornerCount];
        int[] halfEdgeVertex = new int[halfEdgeCount];
        int[] halfEdgeFace = new int[halfEdgeCount];
        int[] halfEdgeNext = new int[halfEdgeCount];
        int[] halfEdgePrev = new int[halfEdgeCount];
        int nextEdgeId = 0;
        for (int corner = 0; corner < cornerCount; corner++) {
            int firstCorner = firstCornerOfEdge[corner];
            if (firstCorner != corner) {
                halfEdgeOfCorner[corner] = halfEdgeOfCorner[firstCorner] + 1;
                continue;
            }
            int forward = 2 * nextEdgeId++;
            halfEdgeOfCorner[corner] = forward;
            halfEdgeVertex[forward] = faceIndices[corner];
            halfEdgeVertex[forward + 1] = cornerEnd[corner];
            halfEdgeFace[forward + 1] = MeshTopology.NONE;
            halfEdgeNext[forward + 1] = MeshTopology.NONE;
            halfEdgePrev[forward + 1] = MeshTopology.NONE;
        }

        int[] faceHalfEdge = new int[faceCount];
        for (int faceId = 0; faceId < faceCount; faceId++) {
            int first = faceStarts[faceId];
            int last = faceStarts[faceId + 1] - 1;
            faceHalfEdge[faceId] = halfEdgeOfCorner[first];
            for (int corner = first; corner <= last; corner++) {
                int halfEdgeId = halfEdgeOfCorner[corner];
                halfEdgeFace[halfEdgeId] = faceId;
                halfEdgeNext[halfEdgeId] = halfEdgeOfCorner[corner < last ? corner + 1 : first];
                halfEdgePrev[halfEdgeId] = halfEdgeOfCorner[corner > first ? corner - 1 : last];
            }
        }
        int[] halfEdgeTwin = new int[halfEdgeCount];
        int[] halfEdgeEdge = new int[halfEdgeCount];
        int[] edgeHalfEdge = new int[edgeCount];
        for (int halfEdgeId = 0; halfEdgeId < halfEdgeCount; halfEdgeId++) {
            halfEdgeTwin[halfEdgeId] = halfEdgeId ^ 1;
            halfEdgeEdge[halfEdgeId] = halfEdgeId >> 1;
        }
        for (int edgeId = 0; edgeId < edgeCount; edgeId++) {
            edgeHalfEdge[edgeId] = 2 * edgeId;
        }

        int[] outgoingStart = new int[vertexCount + 1];
        for (int halfEdgeId = 0; halfEdgeId < halfEdgeCount; halfEdgeId++) {
            outgoingStart[halfEdgeVertex[halfEdgeId] + 1]++;
        }
        for (int vertexId = 0; vertexId < vertexCount; vertexId++) {
            outgoingStart[vertexId + 1] += outgoingStart[vertexId];
        }
        int[] outgoingHalfEdges = new int[halfEdgeCount];
        int[] outgoingEdges = new int[halfEdgeCount];
        System.arraycopy(outgoingStart, 0, fillCursor, 0, vertexCount);
        for (int halfEdgeId = 0; halfEdgeId < halfEdgeCount; halfEdgeId++) {
            int slot = fillCursor[halfEdgeVertex[halfEdgeId]]++;
            outgoingHalfEdges[slot] = halfEdgeId;
            outgoingEdges[slot] = halfEdgeId >> 1;
        }
        int[] vertexOutgoing = new int[vertexCount];
        for (int vertexId = 0; vertexId < vertexCount; vertexId++) {
            vertexOutgoing[vertexId] = outgoingStart[vertexId] < outgoingStart[vertexId + 1]
                    ? outgoingHalfEdges[outgoingStart[vertexId]]
                    : MeshTopology.NONE;
        }
        int[] vertexFaceIds = new int[cornerCount];
        System.arraycopy(faceRowStart, 0, fillCursor, 0, vertexCount);
        for (int faceId = 0; faceId < faceCount; faceId++) {
            for (int corner = faceStarts[faceId]; corner < faceStarts[faceId + 1]; corner++) {
                vertexFaceIds[fillCursor[faceIndices[corner]]++] = faceId;
            }
        }
        int[] faceEdgeIds = new int[cornerCount];
        for (int corner = 0; corner < cornerCount; corner++) {
            faceEdgeIds[corner] = halfEdgeOfCorner[corner] >> 1;
        }

        HalfEdgeMesh mesh = new HalfEdgeMesh();
        mesh.vertexPositions = Arrays.copyOf(positions, vertexCount * HalfEdgeMesh.FLOATS_PER_VERTEX);
        mesh.vertexNormals = new float[mesh.vertexPositions.length];
        mesh.vertexOutgoing = vertexOutgoing;
        mesh.vertexActive = new boolean[vertexCount];
        Arrays.fill(mesh.vertexActive, true);
        mesh.edgeHalfEdge = edgeHalfEdge;
        mesh.edgeActive = new boolean[edgeCount];
        Arrays.fill(mesh.edgeActive, true);
        mesh.faceHalfEdge = faceHalfEdge;
        mesh.faceNormals = new float[faceCount * HalfEdgeMesh.FLOATS_PER_VERTEX];
        mesh.faceActive = new boolean[faceCount];
        Arrays.fill(mesh.faceActive, true);
        mesh.halfEdgeTwin = halfEdgeTwin;
        mesh.halfEdgeNext = halfEdgeNext;
        mesh.halfEdgePrev = halfEdgePrev;
        mesh.halfEdgeVertex = halfEdgeVertex;
        mesh.halfEdgeFace = halfEdgeFace;
        mesh.halfEdgeEdge = halfEdgeEdge;
        mesh.halfEdgeActive = new boolean[halfEdgeCount];
        Arrays.fill(mesh.halfEdgeActive, true);
        mesh.nextVertexId = vertexCount;
        mesh.nextEdgeId = edgeCount;
        mesh.nextFaceId = faceCount;
        mesh.nextHalfEdgeId = halfEdgeCount;
        mesh.activeVertexIds.addFirstIds(vertexCount);
        mesh.activeEdgeIds.addFirstIds(edgeCount);
        mesh.activeFaceIds.addFirstIds(faceCount);
        mesh.activeHalfEdgeIds.addFirstIds(halfEdgeCount);
        mesh.vertexOutgoingHalfEdges.pack(outgoingStart, outgoingHalfEdges);
        mesh.vertexEdges.pack(outgoingStart, outgoingEdges);
        mesh.vertexFaces.pack(faceRowStart, vertexFaceIds);
        mesh.faceHalfEdges.pack(faceStarts, halfEdgeOfCorner);
        mesh.faceVertices.pack(faceStarts, Arrays.copyOf(faceIndices, cornerCount));
        mesh.faceEdges.pack(faceStarts, faceEdgeIds);
        return mesh;
    }

    static int addFaceInternal(HalfEdgeMesh mesh, int[] vertexIds, boolean recomputeNormals) {
        if (vertexIds.length < 3) {
            throw new InvalidMeshTopologyException("A face needs at least three vertices");
        }

        int faceId = mesh.createFaceSlot();
        int[] faceHalfEdges = new int[vertexIds.length];
        for (int i = 0; i < vertexIds.length; i++) {
            int startVertexId = vertexIds[i];
            int endVertexId = vertexIds[(i + 1) % vertexIds.length];
            validateDistinctVertices(startVertexId, endVertexId);

            int halfEdgeId = ensureEdgePair(mesh, startVertexId, endVertexId);
            if (mesh.halfEdgeFace[halfEdgeId] != MeshTopology.NONE) {
                throw new InvalidMeshTopologyException(
                        "Non-manifold or duplicate face edge between " + startVertexId + AND + endVertexId);
            }
            faceHalfEdges[i] = halfEdgeId;
        }

        mesh.faceHalfEdges.clear(faceId);
        mesh.faceVertices.clear(faceId);
        mesh.faceEdges.clear(faceId);
        mesh.faceHalfEdge[faceId] = faceHalfEdges[0];

        for (int i = 0; i < faceHalfEdges.length; i++) {
            int halfEdgeId = faceHalfEdges[i];
            int nextHalfEdgeId = faceHalfEdges[(i + 1) % faceHalfEdges.length];
            int prevHalfEdgeId = faceHalfEdges[(i + faceHalfEdges.length - 1) % faceHalfEdges.length];
            int vertexId = mesh.halfEdgeVertex[halfEdgeId];
            int edgeId = mesh.halfEdgeEdge[halfEdgeId];

            mesh.halfEdgeFace[halfEdgeId] = faceId;
            mesh.halfEdgeNext[halfEdgeId] = nextHalfEdgeId;
            mesh.halfEdgePrev[halfEdgeId] = prevHalfEdgeId;
            mesh.faceHalfEdges.add(faceId, halfEdgeId);
            mesh.faceVertices.add(faceId, vertexId);
            mesh.faceEdges.add(faceId, edgeId);
            mesh.vertexFaces.add(vertexId, faceId);
        }

        if (recomputeNormals) {
            computeNormals(mesh);
        }
        return faceId;
    }

    static int ensureEdgePair(HalfEdgeMesh mesh, int startVertexId, int endVertexId) {
        int existing = findHalfEdge(mesh, startVertexId, endVertexId);
        if (existing != MeshTopology.NONE) {
            return existing;
        }
        int edgeId = createEdgePair(mesh, startVertexId, endVertexId);
        return mesh.edgeHalfEdge[edgeId];
    }

    /**
     * The half-edge running from one vertex to another, or {@link MeshTopology#NONE} when the pair
     * is not connected.
     *
     * <p>Walks the source vertex's outgoing adjacency, which holds every outgoing half-edge whether
     * or not it carries a face.
     *
     * @param mesh          mesh to search
     * @param startVertexId source vertex
     * @param endVertexId   destination vertex
     * @return the directed half-edge id, or {@link MeshTopology#NONE}
     */
    static int findHalfEdge(HalfEdgeMesh mesh, int startVertexId, int endVertexId) {
        int outgoingCount = mesh.vertexOutgoingHalfEdges.size(startVertexId);
        for (int index = 0; index < outgoingCount; index++) {
            int halfEdgeId = mesh.vertexOutgoingHalfEdges.get(startVertexId, index);
            if (mesh.halfEdgeVertex[mesh.halfEdgeTwin[halfEdgeId]] == endVertexId) {
                return halfEdgeId;
            }
        }
        return MeshTopology.NONE;
    }

    static int createEdgePair(HalfEdgeMesh mesh, int startVertexId, int endVertexId) {
        int edgeId = mesh.createEdgeSlot();
        int forwardHalfEdgeId = mesh.createHalfEdgeSlot(startVertexId);
        int backwardHalfEdgeId = mesh.createHalfEdgeSlot(endVertexId);

        mesh.halfEdgeTwin[forwardHalfEdgeId] = backwardHalfEdgeId;
        mesh.halfEdgeTwin[backwardHalfEdgeId] = forwardHalfEdgeId;
        mesh.halfEdgeEdge[forwardHalfEdgeId] = edgeId;
        mesh.halfEdgeEdge[backwardHalfEdgeId] = edgeId;
        mesh.edgeHalfEdge[edgeId] = forwardHalfEdgeId;

        mesh.vertexOutgoingHalfEdges.add(startVertexId, forwardHalfEdgeId);
        mesh.vertexOutgoingHalfEdges.add(endVertexId, backwardHalfEdgeId);
        mesh.vertexEdges.addUnique(startVertexId, edgeId);
        mesh.vertexEdges.addUnique(endVertexId, edgeId);
        if (mesh.vertexOutgoing[startVertexId] == MeshTopology.NONE) {
            mesh.vertexOutgoing[startVertexId] = forwardHalfEdgeId;
        }
        if (mesh.vertexOutgoing[endVertexId] == MeshTopology.NONE) {
            mesh.vertexOutgoing[endVertexId] = backwardHalfEdgeId;
        }

        return edgeId;
    }

    static void unregisterHalfEdge(HalfEdgeMesh mesh, int halfEdgeId) {
        int startVertexId = mesh.halfEdgeVertex[halfEdgeId];
        int edgeId = mesh.halfEdgeEdge[halfEdgeId];

        mesh.vertexOutgoingHalfEdges.removeValue(startVertexId, halfEdgeId);
        mesh.vertexEdges.removeValue(startVertexId, edgeId);
        if (mesh.vertexOutgoing[startVertexId] == halfEdgeId) {
            mesh.vertexOutgoing[startVertexId] = mesh.vertexOutgoingHalfEdges.isEmpty(startVertexId)
                    ? MeshTopology.NONE
                    : mesh.vertexOutgoingHalfEdges.get(startVertexId, 0);
        }
        mesh.deactivateHalfEdge(halfEdgeId);
    }

    static void ensureDirectedEdgeAvailable(HalfEdgeMesh mesh, int startVertexId, int endVertexId) {
        if (findHalfEdge(mesh, startVertexId, endVertexId) != MeshTopology.NONE) {
            throw new InvalidMeshTopologyException(
                    "Edge between " + startVertexId + AND + endVertexId + " already exists");
        }
    }

    static boolean isIsolatedEdge(HalfEdgeMesh mesh, int edgeId) {
        int firstHalfEdge = mesh.edgeHalfEdge[edgeId];
        int secondHalfEdge = mesh.halfEdgeTwin[firstHalfEdge];
        return mesh.halfEdgeFace[firstHalfEdge] == MeshTopology.NONE && mesh.halfEdgeFace[secondHalfEdge] == MeshTopology.NONE;
    }

    static void validateDistinctVertices(int startVertexId, int endVertexId) {
        if (startVertexId == endVertexId) {
            throw new InvalidMeshTopologyException("Degenerate edge on vertex " + startVertexId);
        }
    }

    private static void setVector(float[] target, int id, float x, float y, float z) {
        int offset = id * HalfEdgeMesh.FLOATS_PER_VERTEX;
        target[offset] = x;
        target[offset + 1] = y;
        target[offset + 2] = z;
    }

    private static void addVector(float[] target, int id, float x, float y, float z) {
        int offset = id * HalfEdgeMesh.FLOATS_PER_VERTEX;
        target[offset] += x;
        target[offset + 1] += y;
        target[offset + 2] += z;
    }
}
