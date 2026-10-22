package ixdar.geometry.mesh.data.representation;

import org.joml.Vector3f;

/**
 * Read-only mesh topology and geometry interface used by the editor and
 * geometry algorithms. Surfaces vertices, edges, faces, and (where
 * supported) half-edges with stable integer ids and adjacency lookups.
 */
public interface MeshTopology {
    int NONE = -1;

    int QUAD_CORNERS = 4;

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
     * One past the largest vertex id this mesh has issued, so an array indexed by vertex id has
     * this length; ids of removed vertices are unused slots.
     *
     * @return the vertex id bound, at least {@link #vertexCount()}
     */
    int vertexIdBound();

    /**
     * One past the largest edge id this mesh has issued, so an array indexed by edge id has this
     * length; ids of removed edges are unused slots.
     *
     * @return the edge id bound, at least {@link #edgeCount()}
     */
    int edgeIdBound();

    /**
     * One past the largest face id this mesh has issued, so an array indexed by face id has this
     * length; ids of removed faces are unused slots.
     *
     * @return the face id bound, at least {@link #faceCount()}
     */
    int faceIdBound();

    /**
     * One past the largest half-edge id this mesh has issued, so an array indexed by half-edge id
     * has this length; ids of removed half-edges are unused slots.
     *
     * @return the half-edge id bound, at least {@link #halfEdgeCount()}
     */
    int halfEdgeIdBound();

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
     * Half-edge of {@code edgeId} at the given dense index in {@code [0, edgeCount())}.
     *
     * @param activeIndex packed index over live edges
     * @return stable half-edge id at that position
     */
    int edgeHalfEdgeAtActiveIndex(int activeIndex);

    /**
     * Whether {@code edgeId} sits on the mesh boundary.
     *
     * @param edgeId edge to query
     * @return true if either side of the edge has no incident face
     */
    boolean isBoundaryEdge(int edgeId);

    /**
     * Endpoint of {@code edgeId} opposite {@code vertexId}.
     *
     * @param edgeId   edge to read
     * @param vertexId one endpoint of the edge
     * @return the other endpoint
     */
    default int edgeOtherVertex(int edgeId, int vertexId) {
        int halfEdge = edgeHalfEdge(edgeId);
        int tail = halfEdgeVertex(halfEdge);
        return tail == vertexId ? halfEdgeEndVertex(halfEdge) : tail;
    }

    /**
     * Edge joining two vertices, found among the first vertex's incident edges.
     *
     * @param vertexA first endpoint
     * @param vertexB second endpoint
     * @return the joining edge id, or {@link #NONE} when they share no edge
     */
    default int edgeBetween(int vertexA, int vertexB) {
        for (int index = 0; index < vertexEdgeCount(vertexA); index++) {
            int edgeId = vertexEdgeAt(vertexA, index);
            if (edgeOtherVertex(edgeId, vertexA) == vertexB) {
                return edgeId;
            }
        }
        return NONE;
    }

    /**
     * Face on one side of {@code edgeId}: side 0 is the face of
     * {@link #edgeHalfEdge}, side 1 the face of its twin.
     *
     * @param edgeId edge to read
     * @param side   0 or 1
     * @return the face on that side, or {@link #NONE} across a boundary
     */
    default int edgeFace(int edgeId, int side) {
        int halfEdge = edgeHalfEdge(edgeId);
        if (side != 0) {
            halfEdge = halfEdgeTwin(halfEdge);
        }
        return halfEdge == NONE ? NONE : halfEdgeFace(halfEdge);
    }

    /**
     * Euclidean distance between the endpoints of {@code edgeId}.
     *
     * @param edgeId edge to measure
     * @return the edge's length
     */
    float edgeLength(int edgeId);

    /**
     * Read the midpoint of {@code edgeId} into {@code dest}.
     *
     * @param edgeId edge to read
     * @param dest   scratch vector to fill
     * @return {@code dest} for chaining
     */
    Vector3f edgeMidpoint(int edgeId, Vector3f dest);

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
     * The axis a face's normal points along most strongly: the axis a planar projection of the
     * face drops so the projection never collapses.
     *
     * @param faceId face to query
     * @return 0 for x, 1 for y, 2 for z
     */
    default int faceDominantAxis(int faceId) {
        return faceNormal(faceId, new Vector3f()).maxComponent();
    }

    /**
     * Read the (cached) per-face normal into {@code dest}.
     *
     * @param activeIndex packed index over live faces
     * @param dest scratch vector to fill
     * @return {@code dest} for chaining
     */
    Vector3f faceNormalAtActiveIndex(int activeIndex, Vector3f dest);

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

    /**
     * Number of edges with a face on one side only.
     *
     * @return the boundary edge count, zero for a closed mesh
     */
    default int boundaryEdgeCount() {
        int boundary = 0;
        for (int activeEdge = 0; activeEdge < edgeCount(); activeEdge++) {
            boundary += isBoundaryEdge(edgeIdAt(activeEdge)) ? 1 : 0;
        }
        return boundary;
    }

    /**
     * Number of faces with fewer than three corners or whose first three corners are collinear or
     * coincide, so their first triangle has no area.
     *
     * @return the degenerate face count
     */
    default int degenerateFaceCount() {
        int degenerate = 0;
        Vector3f first = new Vector3f();
        Vector3f second = new Vector3f();
        Vector3f third = new Vector3f();
        for (int activeFace = 0; activeFace < faceCount(); activeFace++) {
            int faceId = faceIdAt(activeFace);
            if (faceVertexCount(faceId) < 3) {
                degenerate++;
                continue;
            }
            vertexPosition(faceVertexAt(faceId, 0), first);
            vertexPosition(faceVertexAt(faceId, 1), second).sub(first);
            vertexPosition(faceVertexAt(faceId, 2), third).sub(first);
            degenerate += second.cross(third).lengthSquared() == 0f ? 1 : 0;
        }
        return degenerate;
    }

    /**
     * Number of triangles {@link #triangulate} gives: {@code n - 2} for every face of {@code n}
     * corners.
     *
     * @return the triangle count over every live face
     */
    default int triangleCount() {
        int triangles = 0;
        for (int activeFace = 0; activeFace < faceCount(); activeFace++) {
            triangles += Math.max(0, faceVertexCount(faceIdAt(activeFace)) - 2);
        }
        return triangles;
    }

    /**
     * Every live face split into triangles by {@code rule}, faces in active order, each corner
     * written as its vertex id; a triangle face under {@link TriangulationRule#FAN} is itself.
     *
     * @param rule           how each face is split
     * @param faceOfTriangle receives the face id each triangle came from, at least
     *                       {@link #triangleCount()} long, or null when not wanted
     * @return corner vertex ids, three per triangle, {@link #triangleCount()} triangles
     */
    default int[] triangulate(TriangulationRule rule, int[] faceOfTriangle) {
        int[] triangles = new int[triangleCount() * 3];
        int[] corner = new int[3 * QUAD_CORNERS];
        int cursor = 0;
        int triangle = 0;
        for (int activeFace = 0; activeFace < faceCount(); activeFace++) {
            int faceId = faceIdAt(activeFace);
            int corners = faceVertexCount(faceId);
            if (corner.length < 3 * corners) {
                corner = new int[3 * corners];
            }
            int faceTriangles = faceTriangleCorners(faceId, rule, corner);
            for (int slot = 0; slot < 3 * faceTriangles; slot++) {
                triangles[cursor++] = faceVertexAt(faceId, corner[slot]);
            }
            for (int each = 0; faceOfTriangle != null && each < faceTriangles; each++) {
                faceOfTriangle[triangle + each] = faceId;
            }
            triangle += faceTriangles;
        }
        return triangles;
    }

    /**
     * {@link #triangleCorners} for one face of this mesh, a {@link TriangulationRule#SHORTER_DIAGONAL}
     * quad measured by its corner positions.
     *
     * @param faceId    face to split
     * @param rule      how the face is split
     * @param cornerOut receives the face's corner indices, three per triangle, at least
     *                  {@code 3 (corners - 2)} long
     * @return the triangles written
     */
    default int faceTriangleCorners(int faceId, TriangulationRule rule, int[] cornerOut) {
        int corners = faceVertexCount(faceId);
        boolean firstDiagonalShorter = true;
        if (rule == TriangulationRule.SHORTER_DIAGONAL && corners == QUAD_CORNERS) {
            Vector3f from = new Vector3f();
            Vector3f to = new Vector3f();
            vertexPosition(faceVertexAt(faceId, 0), from);
            vertexPosition(faceVertexAt(faceId, 2), to);
            float firstX = from.x - to.x;
            float firstY = from.y - to.y;
            float firstZ = from.z - to.z;
            vertexPosition(faceVertexAt(faceId, 1), from);
            vertexPosition(faceVertexAt(faceId, 3), to);
            float secondX = from.x - to.x;
            float secondY = from.y - to.y;
            float secondZ = from.z - to.z;
            firstDiagonalShorter = firstX * firstX + firstY * firstY + firstZ * firstZ
                    <= secondX * secondX + secondY * secondY + secondZ * secondZ;
        }
        return triangleCorners(rule, corners, firstDiagonalShorter, cornerOut);
    }

    /**
     * The one face triangulation: corner indices of the triangles {@code rule} splits a polygon
     * into, three per triangle wound like the polygon, fanned from corner 0, or from corner 1 for
     * a {@link TriangulationRule#SHORTER_DIAGONAL} quad whose 1-3 diagonal is the shorter.
     *
     * @param rule                 how the polygon is split
     * @param corners              the polygon's corner count
     * @param firstDiagonalShorter whether a quad's 0-2 diagonal is no longer than its 1-3; read
     *                             only by the shorter-diagonal rule
     * @param cornerOut            receives the corner indices, at least {@code 3 (corners - 2)}
     *                             long
     * @return the triangles written, {@code corners - 2} or none
     */
    static int triangleCorners(TriangulationRule rule, int corners, boolean firstDiagonalShorter,
            int[] cornerOut) {
        int apex = rule == TriangulationRule.SHORTER_DIAGONAL && corners == QUAD_CORNERS
                && !firstDiagonalShorter ? 1 : 0;
        int cursor = 0;
        for (int fan = 1; fan + 1 < corners; fan++) {
            cornerOut[cursor++] = apex;
            cornerOut[cursor++] = (apex + fan) % corners;
            cornerOut[cursor++] = (apex + fan + 1) % corners;
        }
        return Math.max(0, corners - 2);
    }
}
