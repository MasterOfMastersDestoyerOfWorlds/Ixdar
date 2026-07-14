package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * Working copy of the input mesh for LCBK19 §6 re-embedding, following the
 * mesh-nodes pattern: positions and faces are extracted from the source and
 * rebuilt into a fresh {@link HalfEdgeMesh}; the source is never mutated. All
 * local remeshing (face splits, edge splits, edge collapses) goes through this
 * class so the provenance and claim bookkeeping stays consistent: every copy
 * face knows which source active face it descends from (Dijkstra corridors
 * stay well defined across refinement), and every copy edge/vertex knows which
 * T-mesh arc or node owns it.
 */
public final class EmbeddedMeshTopology {

    /** Owner value for unclaimed elements. */
    public static final int UNCLAIMED = -1;

    public final HalfEdgeMesh sourceMesh;
    public final HalfEdgeMesh copy;

    /** Source active face index per raw copy face id; split children inherit. */
    public int[] sourceFaceByCopyFace;

    /**
     * Source active edge index per raw copy edge id, or {@link #UNCLAIMED}
     * for minted interior edges. Edge-split children inherit the parent's
     * tag — a trace crossing the parent may cross either half.
     */
    public int[] sourceEdgeByCopyEdge;

    /** Owning arc id per raw copy edge id, or {@link #UNCLAIMED}. */
    public int[] ownerArcByCopyEdge;

    /** Owning T-mesh node id per raw copy vertex id, or {@link #UNCLAIMED}. */
    public int[] ownerNodeByCopyVertex;

    /** Owning arc id per raw copy vertex id (interior path vertices). */
    public int[] ownerArcByCopyVertex;

    /** Copy face ids descending from each source active face. */
    public final List<List<Integer>> copyFacesBySourceFace;

    public int faceSplitCount;
    public int edgeSplitCount;
    public int edgeCollapseCount;

    /** Claim-transfer conflicts during collapses (kept existing claim). */
    public int claimConflictCount;

    private final Map<Integer, Integer> copyVertexBySourceVertexId = new HashMap<>();

    /** Exclusive bound on allocated copy edge ids, advanced per adopted face. */
    private int edgeIdBound;

    /**
     * Build the working copy from the source mesh's active vertices and faces.
     *
     * @param sourceMesh input triangle mesh (read-only; never mutated)
     */
    public EmbeddedMeshTopology(HalfEdgeMesh sourceMesh) {
        this.sourceMesh = sourceMesh;
        int vertexCount = sourceMesh.vertexCount();
        int faceCount = sourceMesh.faceCount();
        float[] positions = new float[vertexCount * 3];
        Map<Integer, Integer> denseBySourceVertexId = new HashMap<>(vertexCount * 2);
        Vector3f position = new Vector3f();
        for (int dense = 0; dense < vertexCount; dense++) {
            int sourceVertexId = sourceMesh.vertexIdAt(dense);
            sourceMesh.vertexPosition(sourceVertexId, position);
            positions[dense * 3] = position.x;
            positions[dense * 3 + 1] = position.y;
            positions[dense * 3 + 2] = position.z;
            denseBySourceVertexId.put(sourceVertexId, dense);
            copyVertexBySourceVertexId.put(sourceVertexId, dense);
        }
        int[] faceIndices = new int[faceCount * 3];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int sourceFaceId = sourceMesh.faceIdAt(activeFace);
            for (int corner = 0; corner < 3; corner++) {
                faceIndices[activeFace * 3 + corner] = denseBySourceVertexId
                        .get(sourceMesh.faceVertexAt(sourceFaceId, corner));
            }
        }
        this.copy = HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);

        sourceFaceByCopyFace = new int[faceCount];
        copyFacesBySourceFace = new ArrayList<>(faceCount);
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            sourceFaceByCopyFace[activeFace] = activeFace;
            List<Integer> children = new ArrayList<>(1);
            children.add(activeFace);
            copyFacesBySourceFace.add(children);
        }
        edgeIdBound = copy.edgeCount();
        ownerArcByCopyEdge = new int[edgeIdBound];
        sourceEdgeByCopyEdge = new int[edgeIdBound];
        ownerNodeByCopyVertex = new int[vertexCount];
        ownerArcByCopyVertex = new int[vertexCount];
        Arrays.fill(ownerArcByCopyEdge, UNCLAIMED);
        Arrays.fill(sourceEdgeByCopyEdge, UNCLAIMED);
        Arrays.fill(ownerNodeByCopyVertex, UNCLAIMED);
        Arrays.fill(ownerArcByCopyVertex, UNCLAIMED);
    }

    /**
     * Copy vertex corresponding to a source mesh vertex.
     *
     * @param sourceVertexId raw source vertex id
     * @return copy vertex id, or {@link #UNCLAIMED} when unknown
     */
    public int copyVertexForSourceVertexId(int sourceVertexId) {
        Integer copyVertex = copyVertexBySourceVertexId.get(sourceVertexId);
        return copyVertex == null ? UNCLAIMED : copyVertex;
    }

    /**
     * Split a copy face into three around a new vertex at the given position
     * (LCBK19 §6.1 refinement). Children inherit the parent's source face.
     *
     * @param copyFaceId copy face to split
     * @param position   3D position of the new vertex
     * @return new vertex id
     */
    public int splitFaceAtPoint(int copyFaceId, Vector3f position) {
        int vertex0 = copy.faceVertexAt(copyFaceId, 0);
        int vertex1 = copy.faceVertexAt(copyFaceId, 1);
        int vertex2 = copy.faceVertexAt(copyFaceId, 2);
        int sourceFace = sourceFaceByCopyFace[copyFaceId];
        retireFace(copyFaceId, sourceFace);
        int newVertex = copy.addVertex(position);
        adoptFace(copy.addFace(vertex0, vertex1, newVertex), sourceFace);
        adoptFace(copy.addFace(vertex1, vertex2, newVertex), sourceFace);
        adoptFace(copy.addFace(vertex2, vertex0, newVertex), sourceFace);
        ensureVertexCapacity(newVertex);
        ensureEdgeCapacity();
        faceSplitCount++;
        return newVertex;
    }

    /**
     * Split a copy edge at the given position, retriangulating both incident
     * faces (LCBK19 §6.1 refinement / lane creation). Children inherit their
     * respective parents' source faces; the split edge's claim, if any, is
     * transferred to both halves.
     *
     * @param copyEdgeId copy edge to split
     * @param position   3D position of the new vertex
     * @return new vertex id
     */
    public int splitEdgeAtPoint(int copyEdgeId, Vector3f position) {
        int halfEdge = copy.edgeHalfEdge(copyEdgeId);
        int twin = copy.halfEdgeTwin(halfEdge);
        int vertexA = copy.halfEdgeVertex(halfEdge);
        int vertexB = copy.halfEdgeEndVertex(halfEdge);
        int faceA = copy.halfEdgeFace(halfEdge);
        int faceB = copy.halfEdgeFace(twin);
        int edgeOwner = ownerArcByCopyEdge[copyEdgeId];
        int sourceEdge = sourceEdgeByCopyEdge[copyEdgeId];
        int oppositeA = faceA >= 0 ? oppositeVertex(faceA, vertexA, vertexB) : UNCLAIMED;
        int oppositeB = faceB >= 0 ? oppositeVertex(faceB, vertexA, vertexB) : UNCLAIMED;
        int sourceA = faceA >= 0 ? sourceFaceByCopyFace[faceA] : UNCLAIMED;
        int sourceB = faceB >= 0 ? sourceFaceByCopyFace[faceB] : UNCLAIMED;
        if (faceA >= 0) {
            retireFace(faceA, sourceA);
        }
        if (faceB >= 0) {
            retireFace(faceB, sourceB);
        }
        ownerArcByCopyEdge[copyEdgeId] = UNCLAIMED;
        sourceEdgeByCopyEdge[copyEdgeId] = UNCLAIMED;
        int newVertex = copy.addVertex(position);
        if (faceA >= 0) {
            adoptFace(copy.addFace(vertexA, newVertex, oppositeA), sourceA);
            adoptFace(copy.addFace(newVertex, vertexB, oppositeA), sourceA);
        }
        if (faceB >= 0) {
            adoptFace(copy.addFace(vertexB, newVertex, oppositeB), sourceB);
            adoptFace(copy.addFace(newVertex, vertexA, oppositeB), sourceB);
        }
        ensureVertexCapacity(newVertex);
        ensureEdgeCapacity();
        if (edgeOwner != UNCLAIMED) {
            claimEdgeBetween(vertexA, newVertex, edgeOwner);
            claimEdgeBetween(newVertex, vertexB, edgeOwner);
            ownerArcByCopyVertex[newVertex] = edgeOwner;
        }
        if (sourceEdge != UNCLAIMED) {
            tagSourceEdgeBetween(vertexA, newVertex, sourceEdge);
            tagSourceEdgeBetween(newVertex, vertexB, sourceEdge);
        }
        edgeSplitCount++;
        return newVertex;
    }

    /**
     * Tag the copy edge between two vertices with a source active edge (split
     * children inherit the parent's crossing tags).
     *
     * @param vertexA    first endpoint
     * @param vertexB    second endpoint
     * @param sourceEdge source active edge index
     */
    private void tagSourceEdgeBetween(int vertexA, int vertexB, int sourceEdge) {
        int edgeId = edgeBetween(vertexA, vertexB);
        if (edgeId != UNCLAIMED) {
            sourceEdgeByCopyEdge[edgeId] = sourceEdge;
        }
    }

    /**
     * Collapse a copy edge onto one of its endpoints (LCBK19 §6.1 zero-arc
     * contraction step). Fails without mutating when the link condition is
     * violated (the endpoints share a neighbor besides the two opposite
     * vertices of the collapsed edge); the caller then splits and retries.
     * Claims of edges around the discarded vertex transfer to the substituted
     * edges; node ownership of the discarded vertex transfers to the kept one
     * when the kept vertex is unowned.
     *
     * @param copyEdgeId   copy edge to collapse
     * @param keepVertexId endpoint that survives
     * @return true when collapsed, false when the link condition failed
     */
    public boolean collapseEdge(int copyEdgeId, int keepVertexId) {
        int halfEdge = copy.edgeHalfEdge(copyEdgeId);
        int vertexA = copy.halfEdgeVertex(halfEdge);
        int vertexB = copy.halfEdgeEndVertex(halfEdge);
        int discard = keepVertexId == vertexA ? vertexB : vertexA;
        int twin = copy.halfEdgeTwin(halfEdge);
        int faceA = copy.halfEdgeFace(halfEdge);
        int faceB = copy.halfEdgeFace(twin);
        int oppositeA = faceA >= 0 ? oppositeVertex(faceA, vertexA, vertexB) : UNCLAIMED;
        int oppositeB = faceB >= 0 ? oppositeVertex(faceB, vertexA, vertexB) : UNCLAIMED;

        for (int index = 0; index < copy.vertexEdgeCount(discard); index++) {
            int neighborEdge = copy.vertexEdgeAt(discard, index);
            int neighbor = otherEndpoint(neighborEdge, discard);
            if (neighbor == keepVertexId || neighbor == oppositeA || neighbor == oppositeB) {
                continue;
            }
            if (copy.halfEdgesByDirection.containsKey(copy.directedKey(keepVertexId, neighbor))) {
                return false;
            }
        }

        List<int[]> fanFaces = new ArrayList<>();
        List<Integer> fanSources = new ArrayList<>();
        List<int[]> fanEdgeClaims = new ArrayList<>();
        for (int index = 0; index < copy.vertexFaceCount(discard); index++) {
            int faceId = copy.vertexFaceAt(discard, index);
            int[] triangle = new int[3];
            for (int corner = 0; corner < 3; corner++) {
                int vertex = copy.faceVertexAt(faceId, corner);
                triangle[corner] = vertex == discard ? keepVertexId : vertex;
            }
            fanFaces.add(triangle);
            fanSources.add(sourceFaceByCopyFace[faceId]);
        }
        for (int index = 0; index < copy.vertexEdgeCount(discard); index++) {
            int neighborEdge = copy.vertexEdgeAt(discard, index);
            int owner = ownerArcByCopyEdge[neighborEdge];
            if (owner != UNCLAIMED) {
                fanEdgeClaims.add(new int[] { otherEndpoint(neighborEdge, discard), owner });
            }
        }

        List<Integer> facesToRemove = new ArrayList<>();
        for (int index = 0; index < copy.vertexFaceCount(discard); index++) {
            facesToRemove.add(copy.vertexFaceAt(discard, index));
        }
        for (int faceId : facesToRemove) {
            retireFace(faceId, sourceFaceByCopyFace[faceId]);
        }
        copy.removeVertex(discard);
        for (int index = 0; index < fanFaces.size(); index++) {
            int[] triangle = fanFaces.get(index);
            if (triangle[0] == triangle[1] || triangle[1] == triangle[2]
                    || triangle[2] == triangle[0]) {
                continue;
            }
            adoptFace(copy.addFace(triangle[0], triangle[1], triangle[2]), fanSources.get(index));
        }
        ensureEdgeCapacity();
        for (int[] claim : fanEdgeClaims) {
            if (claim[0] != keepVertexId) {
                claimEdgeBetween(keepVertexId, claim[0], claim[1]);
            }
        }
        if (ownerNodeByCopyVertex[discard] != UNCLAIMED
                && ownerNodeByCopyVertex[keepVertexId] == UNCLAIMED) {
            ownerNodeByCopyVertex[keepVertexId] = ownerNodeByCopyVertex[discard];
        }
        ownerNodeByCopyVertex[discard] = UNCLAIMED;
        ownerArcByCopyVertex[discard] = UNCLAIMED;
        edgeCollapseCount++;
        return true;
    }

    /**
     * Claim the edge between two copy vertices for an arc; an existing claim
     * by a different arc is kept and counted as a conflict.
     *
     * @param vertexA first endpoint
     * @param vertexB second endpoint
     * @param arcId   claiming arc
     */
    public void claimEdgeBetween(int vertexA, int vertexB, int arcId) {
        int edgeId = edgeBetween(vertexA, vertexB);
        if (edgeId == UNCLAIMED) {
            return;
        }
        if (ownerArcByCopyEdge[edgeId] != UNCLAIMED && ownerArcByCopyEdge[edgeId] != arcId) {
            claimConflictCount++;
            return;
        }
        ownerArcByCopyEdge[edgeId] = arcId;
    }

    /**
     * Edge id between two copy vertices.
     *
     * @param vertexA first endpoint
     * @param vertexB second endpoint
     * @return edge id, or {@link #UNCLAIMED} when not connected
     */
    public int edgeBetween(int vertexA, int vertexB) {
        Integer halfEdge = copy.halfEdgesByDirection.get(copy.directedKey(vertexA, vertexB));
        if (halfEdge == null) {
            return UNCLAIMED;
        }
        return copy.halfEdgeEdge(halfEdge);
    }

    /**
     * The other endpoint of a copy edge.
     *
     * @param copyEdgeId edge id
     * @param vertexId   one endpoint
     * @return the opposite endpoint
     */
    public int otherEndpoint(int copyEdgeId, int vertexId) {
        int halfEdge = copy.edgeHalfEdge(copyEdgeId);
        int start = copy.halfEdgeVertex(halfEdge);
        return start == vertexId ? copy.halfEdgeEndVertex(halfEdge) : start;
    }

    /**
     * Grow the per-edge claim array to cover all allocated edge ids.
     */
    private void ensureEdgeCapacity() {
        if (edgeIdBound > ownerArcByCopyEdge.length) {
            int oldLength = ownerArcByCopyEdge.length;
            int newLength = Math.max(edgeIdBound, oldLength * 2);
            ownerArcByCopyEdge = Arrays.copyOf(ownerArcByCopyEdge, newLength);
            sourceEdgeByCopyEdge = Arrays.copyOf(sourceEdgeByCopyEdge, newLength);
            Arrays.fill(ownerArcByCopyEdge, oldLength, newLength, UNCLAIMED);
            Arrays.fill(sourceEdgeByCopyEdge, oldLength, newLength, UNCLAIMED);
        }
    }

    /**
     * Grow the per-vertex claim arrays to cover a new vertex id.
     *
     * @param vertexId newly allocated vertex id
     */
    private void ensureVertexCapacity(int vertexId) {
        if (vertexId >= ownerNodeByCopyVertex.length) {
            int oldLength = ownerNodeByCopyVertex.length;
            int newLength = Math.max(vertexId + 1, oldLength * 2);
            ownerNodeByCopyVertex = Arrays.copyOf(ownerNodeByCopyVertex, newLength);
            ownerArcByCopyVertex = Arrays.copyOf(ownerArcByCopyVertex, newLength);
            Arrays.fill(ownerNodeByCopyVertex, oldLength, newLength, UNCLAIMED);
            Arrays.fill(ownerArcByCopyVertex, oldLength, newLength, UNCLAIMED);
        }
    }

    /**
     * Remove a face from the copy and from its source face's child list.
     *
     * @param copyFaceId copy face to remove
     * @param sourceFace its source active face
     */
    private void retireFace(int copyFaceId, int sourceFace) {
        copy.removeFaceKeepingNormals(copyFaceId);
        if (sourceFace >= 0) {
            copyFacesBySourceFace.get(sourceFace).remove(Integer.valueOf(copyFaceId));
        }
    }

    /**
     * Register a freshly added copy face under its source face.
     *
     * @param copyFaceId newly added copy face
     * @param sourceFace source active face it descends from
     */
    private void adoptFace(int copyFaceId, int sourceFace) {
        if (copyFaceId >= sourceFaceByCopyFace.length) {
            int oldLength = sourceFaceByCopyFace.length;
            sourceFaceByCopyFace = Arrays.copyOf(sourceFaceByCopyFace,
                    Math.max(copyFaceId + 1, oldLength * 2));
            Arrays.fill(sourceFaceByCopyFace, oldLength, sourceFaceByCopyFace.length, UNCLAIMED);
        }
        sourceFaceByCopyFace[copyFaceId] = sourceFace;
        if (sourceFace >= 0) {
            copyFacesBySourceFace.get(sourceFace).add(copyFaceId);
        }
        for (int corner = 0; corner < 3; corner++) {
            edgeIdBound = Math.max(edgeIdBound, copy.faceEdgeAt(copyFaceId, corner) + 1);
        }
    }

    /**
     * The vertex of a triangle face that is neither of the two given ones.
     *
     * @param faceId  triangle face
     * @param vertexA first excluded vertex
     * @param vertexB second excluded vertex
     * @return the remaining vertex
     */
    private int oppositeVertex(int faceId, int vertexA, int vertexB) {
        for (int corner = 0; corner < 3; corner++) {
            int vertex = copy.faceVertexAt(faceId, corner);
            if (vertex != vertexA && vertex != vertexB) {
                return vertex;
            }
        }
        return UNCLAIMED;
    }
}
