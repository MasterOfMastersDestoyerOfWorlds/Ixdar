package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * Working copy of the input mesh for the re-embedding, rebuilt into a fresh
 * {@link HalfEdgeMesh}; the source is never mutated.
 *
 * <p>All local remeshing must go through this class, which maintains each copy
 * face's source face and each copy edge's and vertex's owning arc or node.
 *
 * <p>See also: LCBK19 Section 6
 */
public final class EmbeddedMeshTopology {

    /** Owner value for unclaimed elements. */
    public static final int UNCLAIMED = -1;

    /** Corners (and edges) of a triangle. */
    private static final int CORNERS = 3;

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

    /**
     * Copy face ids descending from each source active face, in insertion order, which the
     * child-face search relies on.
     */
    public final List<Set<Integer>> copyFacesBySourceFace;

    public int faceSplitCount;
    public int edgeSplitCount;
    public int edgeCollapseCount;

    /** Claim-transfer conflicts during collapses (kept existing claim). */
    public int claimConflictCount;

    private final Map<Integer, Integer> copyVertexBySourceVertexId = new HashMap<>();

    /**
     * Source active faces carrying a barycentric for each copy vertex, parallel
     * entry-for-entry to {@link #barycentricTriplesByVertex}. A vertex has one
     * entry per source face whose closure it lies in — a handful at most — so
     * lookups scan linearly with no hashing or boxing.
     */
    private final List<int[]> barycentricFacesByVertex = new ArrayList<>();

    /** Barycentric triples of each copy vertex, one per registered source face. */
    private final List<double[][]> barycentricTriplesByVertex = new ArrayList<>();

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
            Set<Integer> children = new LinkedHashSet<>();
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

        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            for (int corner = 0; corner < CORNERS; corner++) {
                double[] barycentric = new double[CORNERS];
                barycentric[corner] = 1.0;
                registerBarycentric(activeFace, copy.faceVertexAt(activeFace, corner), barycentric);
            }
        }
    }

    /**
     * Barycentric coordinate of a copy vertex within a source active face.
     *
     * @param sourceFace source active face index
     * @param copyVertex copy vertex lying in that face's closure
     * @return the barycentric triple, or {@code null} when the vertex does not lie
     *         in that face's closure
     */
    public double[] barycentricOf(int sourceFace, int copyVertex) {
        if (copyVertex >= barycentricFacesByVertex.size()) {
            return null;
        }
        int[] faces = barycentricFacesByVertex.get(copyVertex);
        if (faces == null) {
            return null;
        }
        for (int index = 0; index < faces.length; index++) {
            if (faces[index] == sourceFace) {
                return barycentricTriplesByVertex.get(copyVertex)[index];
            }
        }
        return null;
    }

    /**
     * Record a copy vertex's barycentric coordinate within a source active face.
     *
     * @param sourceFace  source active face index
     * @param copyVertex  copy vertex lying in that face's closure
     * @param barycentric barycentric triple against the source face's corners
     */
    public void registerBarycentric(int sourceFace, int copyVertex, double[] barycentric) {
        while (barycentricFacesByVertex.size() <= copyVertex) {
            barycentricFacesByVertex.add(null);
            barycentricTriplesByVertex.add(null);
        }
        int[] faces = barycentricFacesByVertex.get(copyVertex);
        if (faces == null) {
            barycentricFacesByVertex.set(copyVertex, new int[] { sourceFace });
            barycentricTriplesByVertex.set(copyVertex, new double[][] { barycentric });
            return;
        }
        double[][] triples = barycentricTriplesByVertex.get(copyVertex);
        for (int index = 0; index < faces.length; index++) {
            if (faces[index] == sourceFace) {
                triples[index] = barycentric;
                return;
            }
        }
        int[] grownFaces = Arrays.copyOf(faces, faces.length + 1);
        double[][] grownTriples = Arrays.copyOf(triples, triples.length + 1);
        grownFaces[faces.length] = sourceFace;
        grownTriples[faces.length] = barycentric;
        barycentricFacesByVertex.set(copyVertex, grownFaces);
        barycentricTriplesByVertex.set(copyVertex, grownTriples);
    }

    /**
     * Lift a barycentric coordinate of a source active face to its 3D position on
     * the source surface. Every minted vertex is positioned this way, so the copy
     * mesh stays exactly on the source triangles however deeply it is refined.
     *
     * @param sourceFace  source active face index
     * @param barycentric barycentric triple against the source face's corners
     * @param destination receives the lifted position
     * @return {@code destination}
     */
    public Vector3f positionFromBarycentric(int sourceFace, double[] barycentric,
            Vector3f destination) {
        int sourceFaceId = sourceMesh.faceIdAt(sourceFace);
        destination.zero();
        Vector3f corner = new Vector3f();
        for (int index = 0; index < CORNERS; index++) {
            sourceMesh.vertexPosition(sourceMesh.faceVertexAt(sourceFaceId, index), corner);
            destination.fma((float) barycentric[index], corner);
        }
        return destination;
    }

    /**
     * Split a copy edge at an exact parameter along it, measured from the start
     * vertex of the edge's canonical half-edge. The minted vertex's barycentric is
     * interpolated in every source face incident to the edge and its position is
     * lifted from one of them, so the split introduces no geometric error.
     *
     * @param copyEdgeId copy edge to split
     * @param parameter  position along the edge in {@code (0, 1)}
     * @throws IllegalStateException when neither incident source face carries
     *                               barycentric coordinates for both endpoints
     * @return the minted vertex id
     */
    public int splitEdgeAtParameter(int copyEdgeId, double parameter) {
        int halfEdge = copy.edgeHalfEdge(copyEdgeId);
        int vertexA = copy.halfEdgeVertex(halfEdge);
        int vertexB = copy.halfEdgeEndVertex(halfEdge);
        int faceA = copy.halfEdgeFace(halfEdge);
        int faceB = copy.halfEdgeFace(copy.halfEdgeTwin(halfEdge));
        int sourceA = faceA >= 0 ? sourceFaceByCopyFace[faceA] : UNCLAIMED;
        int sourceB = faceB >= 0 ? sourceFaceByCopyFace[faceB] : UNCLAIMED;
        double[] barycentricA = interpolateBarycentric(sourceA, vertexA, vertexB, parameter);
        double[] barycentricB = sourceB == sourceA
                ? null
                : interpolateBarycentric(sourceB, vertexA, vertexB, parameter);
        if (barycentricA == null && barycentricB == null) {
            throw new IllegalStateException("copy edge " + copyEdgeId
                    + " has no source face with barycentric coordinates for both endpoints");
        }
        int carrier = barycentricA != null ? sourceA : sourceB;
        double[] carried = barycentricA != null ? barycentricA : barycentricB;
        int newVertex = splitEdgeAtPoint(copyEdgeId,
                positionFromBarycentric(carrier, carried, new Vector3f()));
        if (barycentricA != null) {
            registerBarycentric(sourceA, newVertex, barycentricA);
        }
        if (barycentricB != null) {
            registerBarycentric(sourceB, newVertex, barycentricB);
        }
        return newVertex;
    }

    /**
     * Split a copy face at an exact barycentric coordinate of its source face.
     *
     * <p>The point must lie <em>strictly inside</em> the face, since the split registers a
     * barycentric in one source face only. Use {@link #splitEdgeAtParameter} for a point on
     * the boundary.
     *
     * @param copyFaceId  copy face to split
     * @param barycentric barycentric triple against the source face's corners
     * @throws IllegalStateException when the point is not strictly inside the face
     * @return the minted vertex id
     */
    public int splitFaceAtBarycentric(int copyFaceId, double[] barycentric) {
        int sourceFace = sourceFaceByCopyFace[copyFaceId];
        requireStrictlyInside(copyFaceId, sourceFace, barycentric);
        int newVertex = splitFaceAtPoint(copyFaceId,
                positionFromBarycentric(sourceFace, barycentric, new Vector3f()));
        registerBarycentric(sourceFace, newVertex, barycentric);
        return newVertex;
    }

    /**
     * Assert that a barycentric point of a source face lies strictly inside one of that
     * face's children, i.e. off every one of its three edges.
     *
     * @param copyFaceId  child face the point should be interior to
     * @param sourceFace  source active face the coordinates are relative to
     * @param barycentric the point's barycentric triple
     * @throws IllegalStateException when the point lies on or outside an edge
     */
    private void requireStrictlyInside(int copyFaceId, int sourceFace, double[] barycentric) {
        if (!strictlyInside(copyFaceId, sourceFace, barycentric)) {
            throw new IllegalStateException("copy face " + copyFaceId
                    + " cannot be split at " + Arrays.toString(barycentric)
                    + ": the point is not strictly inside it");
        }
    }

    /**
     * Whether a barycentric point of a source face lies strictly inside one of that
     * face's children, decided by the exact orientation predicate.
     *
     * @param copyFaceId  child face the point is tested against
     * @param sourceFace  source active face the coordinates are relative to
     * @param barycentric the point's barycentric triple
     * @return true when the point is strictly off every edge, on the inner side
     */
    public boolean strictlyInside(int copyFaceId, int sourceFace, double[] barycentric) {
        for (int corner = 0; corner < CORNERS; corner++) {
            double[] from = barycentricOf(sourceFace, copy.faceVertexAt(copyFaceId, corner));
            double[] to = barycentricOf(sourceFace,
                    copy.faceVertexAt(copyFaceId, (corner + 1) % CORNERS));
            if (from == null || to == null) {
                return false;
            }
            if (ExactBarycentricOrient.sign(from, to, barycentric) <= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Barycentric coordinate of a point splitting a copy edge, within one source
     * face.
     *
     * @param sourceFace source active face, or {@link #UNCLAIMED}
     * @param vertexA    edge start vertex
     * @param vertexB    edge end vertex
     * @param parameter  position along the edge from {@code vertexA}
     * @return the interpolated triple, or {@code null} when either endpoint has no
     *         coordinate in that face
     */
    private double[] interpolateBarycentric(int sourceFace, int vertexA, int vertexB,
            double parameter) {
        if (sourceFace == UNCLAIMED) {
            return null;
        }
        double[] fromA = barycentricOf(sourceFace, vertexA);
        double[] fromB = barycentricOf(sourceFace, vertexB);
        if (fromA == null || fromB == null) {
            return null;
        }
        double[] blended = new double[CORNERS];
        for (int index = 0; index < CORNERS; index++) {
            blended[index] = fromA[index] + parameter * (fromB[index] - fromA[index]);
        }
        return blended;
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
        int vertexB = copy.halfEdgeEndVertex(halfEdge);
        int faceA = copy.halfEdgeFace(halfEdge);
        int faceB = copy.halfEdgeFace(copy.halfEdgeTwin(halfEdge));
        int edgeOwner = ownerArcByCopyEdge[copyEdgeId];
        int sourceEdge = sourceEdgeByCopyEdge[copyEdgeId];
        int sourceA = faceA >= 0 ? sourceFaceByCopyFace[faceA] : UNCLAIMED;
        int sourceB = faceB >= 0 ? sourceFaceByCopyFace[faceB] : UNCLAIMED;
        int newVertex = copy.splitEdge(copyEdgeId, position);
        int tailEdge = edgeBetween(newVertex, vertexB);
        int tailForward = copy.edgeHalfEdge(tailEdge);
        int childFaceA = copy.halfEdgeFace(tailForward);
        int childFaceB = copy.halfEdgeFace(copy.halfEdgeTwin(tailForward));
        if (childFaceA != UNCLAIMED) {
            adoptFace(childFaceA, sourceA);
        }
        if (childFaceB != UNCLAIMED) {
            adoptFace(childFaceB, sourceB);
        }
        ensureVertexCapacity(newVertex);
        ensureEdgeCapacity();
        if (edgeOwner != UNCLAIMED) {
            ownerArcByCopyEdge[tailEdge] = edgeOwner;
            ownerArcByCopyVertex[newVertex] = edgeOwner;
        }
        if (sourceEdge != UNCLAIMED) {
            sourceEdgeByCopyEdge[tailEdge] = sourceEdge;
        }
        edgeSplitCount++;
        return newVertex;
    }

    /**
     * Claim an embedded path's edges and interior vertices for its arc. Endpoint
     * vertices are left alone: they belong to the T-mesh nodes the arc runs between.
     *
     * @param arcId owning arc
     * @param path  the arc's embedded path
     */
    public void claimPath(int arcId, ArcEdgePath path) {
        for (int edgeId : path.copyEdgePath) {
            ownerArcByCopyEdge[edgeId] = arcId;
        }
        for (int index = 1; index < path.copyVertexPath.size() - 1; index++) {
            ownerArcByCopyVertex[path.copyVertexPath.get(index)] = arcId;
        }
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
     * Edge id between two copy vertices, found by walking the edges incident to one of them.
     *
     * @param vertexA first endpoint
     * @param vertexB second endpoint
     * @return edge id, or {@link #UNCLAIMED} when not connected
     */
    public int edgeBetween(int vertexA, int vertexB) {
        for (int index = 0; index < copy.vertexEdgeCount(vertexA); index++) {
            int edgeId = copy.vertexEdgeAt(vertexA, index);
            if (otherEndpoint(edgeId, vertexA) == vertexB) {
                return edgeId;
            }
        }
        return UNCLAIMED;
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

}
