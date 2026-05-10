package ixdar.geometry.mesh.data.representation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.MeshValue;
import ixdar.common.exceptions.InvalidMeshTopologyException;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.graphics.render.model.HalfEdgeCompiledMeshData;

/**
 * Mutable half-edge mesh: separate id ranges for vertices, edges, faces, and
 * half-edges, each with an active flag and per-id parallel arrays plus
 * {@link IntIdList} adjacency. Editing methods delegate to
 * {@link HalfEdgeMeshEngine}.
 */
public class HalfEdgeMesh implements MeshTopology, MeshValue {
    public static final String IS_NOT_ACTIVE = " is not active";
    public static final int NUM_4 = 4;
    public static final int NUM_8 = 8;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0 = 0f;
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;
    public static final int FLOATS_PER_VERTEX = 3;

    public final Map<Long, Integer> halfEdgesByDirection;
    public final IntIdList activeVertexIds;
    public final IntIdList activeEdgeIds;
    public final IntIdList activeFaceIds;
    public final IntIdList activeHalfEdgeIds;
    public final ArrayList<IntIdList> vertexOutgoingHalfEdges;
    public final ArrayList<IntIdList> vertexEdges;
    public final ArrayList<IntIdList> vertexFaces;
    public final ArrayList<IntIdList> faceHalfEdges;
    public final ArrayList<IntIdList> faceVertices;
    public final ArrayList<IntIdList> faceEdges;

    public float[] vertexPositions;
    public float[] vertexNormals;
    public int[] vertexOutgoing;
    public boolean[] vertexActive;

    public int[] edgeHalfEdge;

    public int[] faceHalfEdge;
    public float[] faceNormals;

    public int[] halfEdgeTwin;
    public int[] halfEdgeNext;
    public int[] halfEdgePrev;
    public int[] halfEdgeVertex;
    public int[] halfEdgeFace;
    public int[] halfEdgeEdge;
    boolean[] edgeActive;
    boolean[] faceActive;
    boolean[] halfEdgeActive;

    int nextVertexId;
    int nextEdgeId;
    int nextFaceId;
    int nextHalfEdgeId;

    /**
     * Creates an empty mesh with small default capacity for incremental
     * construction.
     */
    public HalfEdgeMesh() {
        this(NUM_4, NUM_4, NUM_4, NUM_8);
    }

    /**
     * Creates an empty mesh with hinted capacities to skip array growth during
     * incremental construction. All capacities are clamped to safe minima.
     *
     * @param vertexCapacity   expected number of vertices
     * @param edgeCapacity     expected number of edges
     * @param faceCapacity     expected number of faces
     * @param halfEdgeCapacity expected number of half-edges
     */
    public HalfEdgeMesh(int vertexCapacity, int edgeCapacity, int faceCapacity, int halfEdgeCapacity) {
        int vc = Math.max(NUM_4, vertexCapacity);
        int ec = Math.max(NUM_4, edgeCapacity);
        int fc = Math.max(NUM_4, faceCapacity);
        int hc = Math.max(NUM_8, halfEdgeCapacity);

        this.halfEdgesByDirection = new HashMap<>(hc * NUM_4 / FLOATS_PER_VERTEX + 1);
        this.activeVertexIds = new IntIdList(vc);
        this.activeEdgeIds = new IntIdList(ec);
        this.activeFaceIds = new IntIdList(fc);
        this.activeHalfEdgeIds = new IntIdList(hc);
        this.vertexOutgoingHalfEdges = new ArrayList<>(vc);
        this.vertexEdges = new ArrayList<>(vc);
        this.vertexFaces = new ArrayList<>(vc);
        this.faceHalfEdges = new ArrayList<>(fc);
        this.faceVertices = new ArrayList<>(fc);
        this.faceEdges = new ArrayList<>(fc);
        this.vertexPositions = new float[vc * FLOATS_PER_VERTEX];
        this.vertexNormals = new float[vc * FLOATS_PER_VERTEX];
        this.vertexOutgoing = new int[vc];
        this.vertexActive = new boolean[vc];
        this.edgeHalfEdge = new int[ec];
        this.edgeActive = new boolean[ec];
        this.faceHalfEdge = new int[fc];
        this.faceNormals = new float[fc * FLOATS_PER_VERTEX];
        this.faceActive = new boolean[fc];
        this.halfEdgeTwin = new int[hc];
        this.halfEdgeNext = new int[hc];
        this.halfEdgePrev = new int[hc];
        this.halfEdgeVertex = new int[hc];
        this.halfEdgeFace = new int[hc];
        this.halfEdgeEdge = new int[hc];
        this.halfEdgeActive = new boolean[hc];
        this.nextVertexId = 0;
        this.nextEdgeId = 0;
        this.nextFaceId = 0;
        this.nextHalfEdgeId = 0;

        fillWithNone(vertexOutgoing);
        fillWithNone(edgeHalfEdge);
        fillWithNone(faceHalfEdge);
        fillWithNone(halfEdgeTwin);
        fillWithNone(halfEdgeNext);
        fillWithNone(halfEdgePrev);
        fillWithNone(halfEdgeVertex);
        fillWithNone(halfEdgeFace);
        fillWithNone(halfEdgeEdge);
    }

    /**
     * Pre-sized topology for bulk quad construction (e.g. Catmull–Clark). Avoids
     * array growth and HashMap rehash during
     * {@link HalfEdgeMeshEngine#addFaceInternal}.
     *
     * @param maxV        exact vertex capacity to allocate
     * @param maxE        exact edge capacity to allocate
     * @param maxF        exact face capacity to allocate
     * @param maxHe       exact half-edge capacity to allocate
     * @param mapCapacity initial capacity for the directed-edge HashMap (load
     *                    factor 1.0)
     */
    public HalfEdgeMesh(int maxV, int maxE, int maxF, int maxHe, int mapCapacity) {
        this.halfEdgesByDirection = new HashMap<>(mapCapacity, 1.0f);
        this.activeVertexIds = new IntIdList(Math.max(NUM_4, maxV));
        this.activeEdgeIds = new IntIdList(Math.max(NUM_4, maxE));
        this.activeFaceIds = new IntIdList(Math.max(NUM_4, maxF));
        this.activeHalfEdgeIds = new IntIdList(Math.max(NUM_4, maxHe));
        this.vertexOutgoingHalfEdges = new ArrayList<>(maxV);
        this.vertexEdges = new ArrayList<>(maxV);
        this.vertexFaces = new ArrayList<>(maxV);
        for (int i = 0; i < maxV; i++) {
            this.vertexOutgoingHalfEdges.add(new IntIdList(NUM_8));
            this.vertexEdges.add(new IntIdList(NUM_8));
            this.vertexFaces.add(new IntIdList(NUM_8));
        }
        this.faceHalfEdges = new ArrayList<>(maxF);
        this.faceVertices = new ArrayList<>(maxF);
        this.faceEdges = new ArrayList<>(maxF);
        for (int i = 0; i < maxF; i++) {
            this.faceHalfEdges.add(new IntIdList(NUM_4));
            this.faceVertices.add(new IntIdList(NUM_4));
            this.faceEdges.add(new IntIdList(NUM_4));
        }
        this.vertexPositions = new float[maxV * FLOATS_PER_VERTEX];
        this.vertexNormals = new float[maxV * FLOATS_PER_VERTEX];
        this.vertexOutgoing = new int[maxV];
        Arrays.fill(this.vertexOutgoing, MeshTopology.NONE);
        this.vertexActive = new boolean[maxV];
        this.edgeHalfEdge = new int[maxE];
        Arrays.fill(this.edgeHalfEdge, MeshTopology.NONE);
        this.edgeActive = new boolean[maxE];
        this.faceHalfEdge = new int[maxF];
        Arrays.fill(this.faceHalfEdge, MeshTopology.NONE);
        this.faceNormals = new float[maxF * FLOATS_PER_VERTEX];
        this.faceActive = new boolean[maxF];
        this.halfEdgeTwin = new int[maxHe];
        this.halfEdgeNext = new int[maxHe];
        this.halfEdgePrev = new int[maxHe];
        this.halfEdgeVertex = new int[maxHe];
        this.halfEdgeFace = new int[maxHe];
        this.halfEdgeEdge = new int[maxHe];
        Arrays.fill(this.halfEdgeTwin, MeshTopology.NONE);
        Arrays.fill(this.halfEdgeNext, MeshTopology.NONE);
        Arrays.fill(this.halfEdgePrev, MeshTopology.NONE);
        Arrays.fill(this.halfEdgeVertex, MeshTopology.NONE);
        Arrays.fill(this.halfEdgeFace, MeshTopology.NONE);
        Arrays.fill(this.halfEdgeEdge, MeshTopology.NONE);
        this.halfEdgeActive = new boolean[maxHe];
        this.nextVertexId = 0;
        this.nextEdgeId = 0;
        this.nextFaceId = 0;
        this.nextHalfEdgeId = 0;
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshEngine#addVertex(HalfEdgeMesh, float, float, float)}.
     *
     * @param x x coordinate
     * @param y y coordinate
     * @param z z coordinate
     * @return id of the newly created vertex
     */
    public int addVertex(float x, float y, float z) {
        return HalfEdgeMeshEngine.addVertex(this, x, y, z);
    }

    /**
     * Vector overload of {@link #addVertex(float, float, float)}.
     *
     * @param position position vector
     * @return id of the newly created vertex
     */
    public int addVertex(Vector3f position) {
        return addVertex(position.x, position.y, position.z);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshEngine#addEdge(HalfEdgeMesh, int, int)}.
     *
     * @param startVertexId active vertex on one end
     * @param endVertexId   active vertex on the other end
     * @return id of the newly created edge
     */
    public int addEdge(int startVertexId, int endVertexId) {
        return HalfEdgeMeshEngine.addEdge(this, startVertexId, endVertexId);
    }

    /**
     * Adds a face without updating normals. Call {@link #computeNormals()} when the
     * mesh is finished (or after edits that should refresh shading).
     *
     * @param vertexIds ordered vertex ids defining the face (length &ge; 3)
     * @return id of the newly created face
     */
    public int addFace(int... vertexIds) {
        return HalfEdgeMeshEngine.addFace(this, vertexIds);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshEngine#removeFace(HalfEdgeMesh, int)}.
     *
     * @param faceId active face id to remove
     */
    public void removeFace(int faceId) {
        HalfEdgeMeshEngine.removeFace(this, faceId);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshEngine#removeEdge(HalfEdgeMesh, int)}.
     *
     * @param edgeId active edge id with no incident faces
     */
    public void removeEdge(int edgeId) {
        HalfEdgeMeshEngine.removeEdge(this, edgeId);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshEngine#removeVertex(HalfEdgeMesh, int)}.
     *
     * @param vertexId active vertex id with no incident topology
     */
    public void removeVertex(int vertexId) {
        HalfEdgeMeshEngine.removeVertex(this, vertexId);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshEngine#computeNormals(HalfEdgeMesh)}.
     */
    public void computeNormals() {
        HalfEdgeMeshEngine.computeNormals(this);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshEngine#compileSurfaceData(HalfEdgeMesh)}.
     *
     * @return GPU-ready interleaved vertex data, triangulated indices, bounds, and
     *         bounding sphere
     */
    public HalfEdgeCompiledMeshData compileSurfaceData() {
        return HalfEdgeMeshEngine.compileSurfaceData(this);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshEngine#buildFromIndexedMesh(float[], int[])}.
     *
     * @param positions   packed xyz triples
     * @param faceIndices triangle vertex indices in groups of three
     * @return populated half-edge mesh with normals computed
     */
    public static HalfEdgeMesh buildFromIndexedMesh(float[] positions, int[] faceIndices) {
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
    }

    /**
     * Pre-sized mesh build for uniform face sizes (3 for triangles, 4 for quads).
     * Does not compute normals — call {@link #computeNormals()} when the mesh is
     * ready for rendering.
     *
     * @param positions    packed xyz triples
     * @param faceIndices  flat face index buffer, grouped by {@code vertsPerFace}
     * @param vertsPerFace fixed face arity
     * @return populated half-edge mesh; normals are not computed
     */
    public static HalfEdgeMesh bulkAllocate(float[] positions, int[] faceIndices, int vertsPerFace) {
        return HalfEdgeMeshEngine.bulkAllocate(positions, faceIndices, vertsPerFace);
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexCount() {
        return activeVertexIds.size();
    }

    /** {@inheritDoc}. */
    @Override
    public int edgeCount() {
        return activeEdgeIds.size();
    }

    /** {@inheritDoc}. */
    @Override
    public int faceCount() {
        return activeFaceIds.size();
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgeCount() {
        return activeHalfEdgeIds.size();
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexIdAt(int activeIndex) {
        return activeVertexIds.get(activeIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public int edgeIdAt(int activeIndex) {
        return activeEdgeIds.get(activeIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public int faceIdAt(int activeIndex) {
        return activeFaceIds.get(activeIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgeIdAt(int activeIndex) {
        return activeHalfEdgeIds.get(activeIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public boolean hasVertex(int vertexId) {
        return isActive(vertexActive, vertexId);
    }

    /** {@inheritDoc}. */
    @Override
    public boolean hasEdge(int edgeId) {
        return isActive(edgeActive, edgeId);
    }

    /** {@inheritDoc}. */
    @Override
    public boolean hasFace(int faceId) {
        return isActive(faceActive, faceId);
    }

    /** {@inheritDoc}. */
    @Override
    public boolean hasHalfEdge(int halfEdgeId) {
        return isActive(halfEdgeActive, halfEdgeId);
    }

    /** {@inheritDoc}. */
    @Override
    public Vector3f vertexPosition(int vertexId, Vector3f dest) {
        int offset = vertexOffset(vertexId);
        return dest.set(vertexPositions[offset], vertexPositions[offset + 1], vertexPositions[offset + 2]);
    }

    /** {@inheritDoc}. */
    @Override
    public Vector3f vertexNormal(int vertexId, Vector3f dest) {
        int offset = vertexOffset(vertexId);
        return dest.set(vertexNormals[offset], vertexNormals[offset + 1], vertexNormals[offset + 2]);
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexOutgoingHalfEdge(int vertexId) {
        return vertexOutgoing[vertexId];
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexOutgoingHalfEdgeCount(int vertexId) {
        return vertexOutgoingHalfEdges.get(vertexId).size();
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexOutgoingHalfEdgeAt(int vertexId, int adjacencyIndex) {
        return vertexOutgoingHalfEdges.get(vertexId).get(adjacencyIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexEdgeCount(int vertexId) {
        return vertexEdges.get(vertexId).size();
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexEdgeAt(int vertexId, int adjacencyIndex) {
        return vertexEdges.get(vertexId).get(adjacencyIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexFaceCount(int vertexId) {
        return vertexFaces.get(vertexId).size();
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexFaceAt(int vertexId, int adjacencyIndex) {
        return vertexFaces.get(vertexId).get(adjacencyIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public boolean isBoundaryVertex(int vertexId) {
        IntIdList edges = vertexEdges.get(vertexId);
        for (int i = 0; i < edges.size(); i++) {
            if (isBoundaryEdge(edges.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** {@inheritDoc}. */
    @Override
    public int edgeHalfEdge(int edgeId) {
        return edgeHalfEdge[edgeId];
    }

    /** {@inheritDoc}. */
    @Override
    public int edgeHalfEdgeAtActiveIndex(int activeIndex) {
        return edgeHalfEdge[activeEdgeIds.get(activeIndex)];
    }

    /**
     * {@inheritDoc} An edge is a boundary edge if either of its half-edges is
     * unattached to a face.
     */
    @Override
    public boolean isBoundaryEdge(int edgeId) {
        int firstHalfEdge = edgeHalfEdge[edgeId];
        int secondHalfEdge = halfEdgeTwin[firstHalfEdge];
        return halfEdgeFace[firstHalfEdge] == NONE || halfEdgeFace[secondHalfEdge] == NONE;
    }

    /** {@inheritDoc}. */
    @Override
    public int faceHalfEdge(int faceId) {
        return faceHalfEdge[faceId];
    }

    /** {@inheritDoc}. */
    @Override
    public int faceHalfEdgeCount(int faceId) {
        return faceHalfEdges.get(faceId).size();
    }

    /** {@inheritDoc}. */
    @Override
    public int faceHalfEdgeAt(int faceId, int adjacencyIndex) {
        return faceHalfEdges.get(faceId).get(adjacencyIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public int faceVertexCount(int faceId) {
        return faceVertices.get(faceId).size();
    }

    /** {@inheritDoc}. */
    @Override
    public int faceVertexAt(int faceId, int adjacencyIndex) {
        return faceVertices.get(faceId).get(adjacencyIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public int faceEdgeCount(int faceId) {
        return faceEdges.get(faceId).size();
    }

    /** {@inheritDoc}. */
    @Override
    public int faceEdgeAt(int faceId, int adjacencyIndex) {
        return faceEdges.get(faceId).get(adjacencyIndex);
    }

    /**
     * {@inheritDoc} Returns the most recently computed face normal (see
     * {@link #computeNormals()}).
     */
    @Override
    public Vector3f faceNormal(int faceId, Vector3f dest) {

        int offset = faceOffset(faceId);
        return dest.set(faceNormals[offset], faceNormals[offset + 1], faceNormals[offset + 2]);
    }

    /**
     * {@inheritDoc} Returns the most recently computed face normal (see
     * {@link #computeNormals()}).
     */
    @Override
    public Vector3f faceNormalAtActiveIndex(int activeIndex, Vector3f dest) {

        int offset = faceOffset(activeFaceIds.get(activeIndex));
        return dest.set(faceNormals[offset], faceNormals[offset + 1], faceNormals[offset + 2]);
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgeVertex(int halfEdgeId) {
        return halfEdgeVertex[halfEdgeId];
    }

    /** {@inheritDoc} Reads the start vertex of {@code halfEdgeNext}. */
    @Override
    public int halfEdgeEndVertex(int halfEdgeId) {
        int next = halfEdgeNext[halfEdgeId];
        return halfEdgeVertex[next];
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgeTwin(int halfEdgeId) {
        return halfEdgeTwin[halfEdgeId];
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgeNext(int halfEdgeId) {
        return halfEdgeNext[halfEdgeId];
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgePrev(int halfEdgeId) {
        return halfEdgePrev[halfEdgeId];
    }

    /** {@inheritDoc} Returns {@link MeshTopology#NONE} for boundary half-edges. */
    @Override
    public int halfEdgeFace(int halfEdgeId) {
        return halfEdgeFace[halfEdgeId];
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgeEdge(int halfEdgeId) {
        return halfEdgeEdge[halfEdgeId];
    }

    /** {@inheritDoc}. */
    @Override
    public boolean isBoundaryHalfEdge(int halfEdgeId) {
        return halfEdgeFace[halfEdgeId] == NONE;
    }

    /**
     * {@inheritDoc} Recomputed on every call by linear scan over active vertices.
     */
    @Override
    public Vector3f boundsMin(Vector3f dest) {
        if (activeVertexIds.isEmpty()) {
            return dest.zero();
        }

        int firstVertexId = activeVertexIds.get(0);
        int offset = vertexOffset(firstVertexId);
        dest.set(vertexPositions[offset], vertexPositions[offset + 1], vertexPositions[offset + 2]);
        for (int i = 1; i < activeVertexIds.size(); i++) {
            int vertexId = activeVertexIds.get(i);
            int vertexOffset = vertexOffset(vertexId);
            dest.x = Math.min(dest.x, vertexPositions[vertexOffset]);
            dest.y = Math.min(dest.y, vertexPositions[vertexOffset + 1]);
            dest.z = Math.min(dest.z, vertexPositions[vertexOffset + 2]);
        }
        return dest;
    }

    /**
     * {@inheritDoc} Recomputed on every call by linear scan over active vertices.
     */
    @Override
    public Vector3f boundsMax(Vector3f dest) {
        if (activeVertexIds.isEmpty()) {
            return dest.zero();
        }

        int firstVertexId = activeVertexIds.get(0);
        int offset = vertexOffset(firstVertexId);
        dest.set(vertexPositions[offset], vertexPositions[offset + 1], vertexPositions[offset + 2]);
        for (int i = 1; i < activeVertexIds.size(); i++) {
            int vertexId = activeVertexIds.get(i);
            int vertexOffset = vertexOffset(vertexId);
            dest.x = Math.max(dest.x, vertexPositions[vertexOffset]);
            dest.y = Math.max(dest.y, vertexPositions[vertexOffset + 1]);
            dest.z = Math.max(dest.z, vertexPositions[vertexOffset + 2]);
        }
        return dest;
    }

    /** {@inheritDoc} Returns the center of the axis-aligned bounding box. */
    @Override
    public Vector3f center(Vector3f dest) {
        Vector3f min = boundsMin(new Vector3f());
        Vector3f max = boundsMax(new Vector3f());
        return dest.set(min).add(max).mul(NUM_0_5);
    }

    /**
     * {@inheritDoc} Bounding-sphere radius about {@link #center(Vector3f)};
     * recomputed each call.
     */
    @Override
    public float radius() {
        if (activeVertexIds.isEmpty()) {
            return NUM_0;
        }
        Vector3f center = center(new Vector3f());
        float maxDistanceSquared = NUM_0;
        for (int i = 0; i < activeVertexIds.size(); i++) {
            int vertexId = activeVertexIds.get(i);
            int offset = vertexOffset(vertexId);
            float dx = vertexPositions[offset] - center.x;
            float dy = vertexPositions[offset + 1] - center.y;
            float dz = vertexPositions[offset + 2] - center.z;
            float distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > maxDistanceSquared) {
                maxDistanceSquared = distanceSquared;
            }
        }
        return (float) Math.sqrt(maxDistanceSquared);
    }

    /**
     * Allocates and activates a fresh vertex slot at the given position with zeroed
     * normal and empty adjacency.
     *
     * @param x x coordinate
     * @param y y coordinate
     * @param z z coordinate
     * @return id of the newly allocated vertex
     */
    public int createVertexSlot(float x, float y, float z) {
        int vertexId = nextVertexId++;
        ensureVertexCapacity(vertexId + 1);
        vertexActive[vertexId] = true;
        activeVertexIds.add(vertexId);
        vertexOutgoing[vertexId] = NONE;
        setVector(vertexPositions, vertexId, x, y, z);
        setVector(vertexNormals, vertexId, NUM_0, NUM_0, NUM_0);
        ensureVertexAdjacencySlot(vertexOutgoingHalfEdges, vertexId).clear();
        ensureVertexAdjacencySlot(vertexEdges, vertexId).clear();
        ensureVertexAdjacencySlot(vertexFaces, vertexId).clear();
        return vertexId;
    }

    /**
     * Allocates and activates a fresh edge slot with no associated half-edge yet.
     *
     * @return id of the newly allocated edge
     */
    public int createEdgeSlot() {
        int edgeId = nextEdgeId++;
        ensureEdgeCapacity(edgeId + 1);
        edgeActive[edgeId] = true;
        activeEdgeIds.add(edgeId);
        edgeHalfEdge[edgeId] = NONE;
        return edgeId;
    }

    /**
     * Allocates and activates a fresh face slot with zeroed normal and empty
     * adjacency.
     *
     * @return id of the newly allocated face
     */
    public int createFaceSlot() {
        int faceId = nextFaceId++;
        ensureFaceCapacity(faceId + 1);
        faceActive[faceId] = true;
        activeFaceIds.add(faceId);
        faceHalfEdge[faceId] = NONE;
        setVector(faceNormals, faceId, NUM_0, NUM_0, NUM_0);
        ensureFaceAdjacencySlot(faceHalfEdges, faceId).clear();
        ensureFaceAdjacencySlot(faceVertices, faceId).clear();
        ensureFaceAdjacencySlot(faceEdges, faceId).clear();
        return faceId;
    }

    /**
     * Allocates and activates a fresh half-edge slot anchored at the given start
     * vertex; twin/next/prev/face/edge are left unset.
     *
     * @param vertexId start vertex of the new half-edge
     * @return id of the newly allocated half-edge
     */
    public int createHalfEdgeSlot(int vertexId) {
        int halfEdgeId = nextHalfEdgeId++;
        ensureHalfEdgeCapacity(halfEdgeId + 1);
        halfEdgeActive[halfEdgeId] = true;
        activeHalfEdgeIds.add(halfEdgeId);
        halfEdgeVertex[halfEdgeId] = vertexId;
        halfEdgeTwin[halfEdgeId] = NONE;
        halfEdgeNext[halfEdgeId] = NONE;
        halfEdgePrev[halfEdgeId] = NONE;
        halfEdgeFace[halfEdgeId] = NONE;
        halfEdgeEdge[halfEdgeId] = NONE;
        return halfEdgeId;
    }

    /**
     * Marks a face slot inactive and clears its normal and adjacency lists.
     *
     * @param faceId face slot to deactivate
     */
    public void deactivateFace(int faceId) {
        faceActive[faceId] = false;
        activeFaceIds.removeValue(faceId);
        faceHalfEdge[faceId] = NONE;
        setVector(faceNormals, faceId, NUM_0, NUM_0, NUM_0);
        faceHalfEdges.get(faceId).clear();
        faceVertices.get(faceId).clear();
        faceEdges.get(faceId).clear();
    }

    /**
     * Marks an edge slot inactive and detaches its half-edge reference.
     *
     * @param edgeId edge slot to deactivate
     */
    public void deactivateEdge(int edgeId) {
        edgeActive[edgeId] = false;
        activeEdgeIds.removeValue(edgeId);
        edgeHalfEdge[edgeId] = NONE;
    }

    /**
     * Marks a half-edge slot inactive and clears all
     * twin/next/prev/vertex/face/edge links.
     *
     * @param halfEdgeId half-edge slot to deactivate
     */
    public void deactivateHalfEdge(int halfEdgeId) {
        halfEdgeActive[halfEdgeId] = false;
        activeHalfEdgeIds.removeValue(halfEdgeId);
        halfEdgeTwin[halfEdgeId] = NONE;
        halfEdgeNext[halfEdgeId] = NONE;
        halfEdgePrev[halfEdgeId] = NONE;
        halfEdgeVertex[halfEdgeId] = NONE;
        halfEdgeFace[halfEdgeId] = NONE;
        halfEdgeEdge[halfEdgeId] = NONE;
    }

    /**
     * Marks a vertex slot inactive and zeroes its position, normal, and adjacency.
     *
     * @param vertexId vertex slot to deactivate
     */
    public void deactivateVertex(int vertexId) {
        vertexActive[vertexId] = false;
        activeVertexIds.removeValue(vertexId);
        vertexOutgoing[vertexId] = NONE;
        setVector(vertexPositions, vertexId, NUM_0, NUM_0, NUM_0);
        setVector(vertexNormals, vertexId, NUM_0, NUM_0, NUM_0);
        vertexOutgoingHalfEdges.get(vertexId).clear();
        vertexEdges.get(vertexId).clear();
        vertexFaces.get(vertexId).clear();
    }

    /**
     * Packs a directed (start, end) vertex pair into a single long for use as the
     * {@link #halfEdgesByDirection} key.
     *
     * @param startIndex source vertex id (high 32 bits)
     * @param endIndex   destination vertex id (low 32 bits)
     * @return packed key suitable for the directed-edge map
     */
    public long directedKey(int startIndex, int endIndex) {
        return (((long) startIndex) << NUM_32) | (endIndex & NUM_0xffffffff);
    }

    /**
     * Index of the first float for this vertex within {@link #vertexPositions} or
     * {@link #vertexNormals}.
     *
     * @param vertexId vertex id
     * @return base offset into the packed xyz array
     */
    public int vertexOffset(int vertexId) {
        return vertexId * FLOATS_PER_VERTEX;
    }

    int faceOffset(int faceId) {
        return faceId * FLOATS_PER_VERTEX;
    }

    /**
     * Grows the per-vertex parallel arrays so they can address at least
     * {@code requiredVertexCount} vertex ids.
     *
     * @param requiredVertexCount minimum addressable vertex count
     */
    public void ensureVertexCapacity(int requiredVertexCount) {
        if (requiredVertexCount <= vertexActive.length) {
            return;
        }
        int nextCapacity = nextCapacity(vertexActive.length, requiredVertexCount);
        vertexActive = resizeBooleanArray(vertexActive, nextCapacity);
        vertexOutgoing = resizeIntArray(vertexOutgoing, nextCapacity);
        vertexPositions = resizeFloatTupleArray(vertexPositions, nextCapacity);
        vertexNormals = resizeFloatTupleArray(vertexNormals, nextCapacity);
    }

    /**
     * Grows the per-edge parallel arrays so they can address at least
     * {@code requiredEdgeCount} edge ids.
     *
     * @param requiredEdgeCount minimum addressable edge count
     */
    public void ensureEdgeCapacity(int requiredEdgeCount) {
        if (requiredEdgeCount <= edgeActive.length) {
            return;
        }
        int nextCapacity = nextCapacity(edgeActive.length, requiredEdgeCount);
        edgeActive = resizeBooleanArray(edgeActive, nextCapacity);
        edgeHalfEdge = resizeIntArray(edgeHalfEdge, nextCapacity);
    }

    /**
     * Grows the per-face parallel arrays so they can address at least
     * {@code requiredFaceCount} face ids.
     *
     * @param requiredFaceCount minimum addressable face count
     */
    public void ensureFaceCapacity(int requiredFaceCount) {
        if (requiredFaceCount <= faceActive.length) {
            return;
        }
        int nextCapacity = nextCapacity(faceActive.length, requiredFaceCount);
        faceActive = resizeBooleanArray(faceActive, nextCapacity);
        faceHalfEdge = resizeIntArray(faceHalfEdge, nextCapacity);
        faceNormals = resizeFloatTupleArray(faceNormals, nextCapacity);
    }

    /**
     * Grows the per-half-edge parallel arrays so they can address at least
     * {@code requiredHalfEdgeCount} ids.
     *
     * @param requiredHalfEdgeCount minimum addressable half-edge count
     */
    public void ensureHalfEdgeCapacity(int requiredHalfEdgeCount) {
        if (requiredHalfEdgeCount <= halfEdgeActive.length) {
            return;
        }
        int nextCapacity = nextCapacity(halfEdgeActive.length, requiredHalfEdgeCount);
        halfEdgeActive = resizeBooleanArray(halfEdgeActive, nextCapacity);
        halfEdgeTwin = resizeIntArray(halfEdgeTwin, nextCapacity);
        halfEdgeNext = resizeIntArray(halfEdgeNext, nextCapacity);
        halfEdgePrev = resizeIntArray(halfEdgePrev, nextCapacity);
        halfEdgeVertex = resizeIntArray(halfEdgeVertex, nextCapacity);
        halfEdgeFace = resizeIntArray(halfEdgeFace, nextCapacity);
        halfEdgeEdge = resizeIntArray(halfEdgeEdge, nextCapacity);
    }

    /**
     * Pads a vertex-indexed adjacency list with empty slots up to {@code vertexId}
     * and returns the entry at that id.
     *
     * @param adjacency vertex-indexed list of {@link IntIdList} adjacency buckets
     * @param vertexId  vertex id whose slot must exist
     * @return the {@link IntIdList} bucket for {@code vertexId}
     */
    public IntIdList ensureVertexAdjacencySlot(ArrayList<IntIdList> adjacency, int vertexId) {
        while (adjacency.size() <= vertexId) {
            adjacency.add(new IntIdList());
        }
        return adjacency.get(vertexId);
    }

    /**
     * Pads a face-indexed adjacency list with empty slots up to {@code faceId} and
     * returns the entry at that id.
     *
     * @param adjacency face-indexed list of {@link IntIdList} adjacency buckets
     * @param faceId    face id whose slot must exist
     * @return the {@link IntIdList} bucket for {@code faceId}
     */
    public IntIdList ensureFaceAdjacencySlot(ArrayList<IntIdList> adjacency, int faceId) {
        while (adjacency.size() <= faceId) {
            adjacency.add(new IntIdList());
        }
        return adjacency.get(faceId);
    }

    /**
     * Bounds-safe lookup into an active-flag array.
     *
     * @param active per-id active flags
     * @param id     slot id
     * @return true when {@code id} is in range and the slot is flagged active
     */
    public static boolean isActive(boolean[] active, int id) {
        return id >= 0 && id < active.length && active[id];
    }

    /**
     * Writes an xyz triple into a packed float array at the slot for the given id.
     *
     * @param target packed xyz array
     * @param id     slot id (vertex or face)
     * @param x      x component
     * @param y      y component
     * @param z      z component
     */
    public static void setVector(float[] target, int id, float x, float y, float z) {
        int offset = id * FLOATS_PER_VERTEX;
        target[offset] = x;
        target[offset + 1] = y;
        target[offset + 2] = z;
    }

    /**
     * Returns a boolean array of the requested length with the contents of
     * {@code source} copied to the prefix.
     *
     * @param source       array to copy from
     * @param nextCapacity length of the returned array
     * @return new boolean array sized to {@code nextCapacity}
     */
    public static boolean[] resizeBooleanArray(boolean[] source, int nextCapacity) {
        boolean[] resized = new boolean[nextCapacity];
        System.arraycopy(source, 0, resized, 0, source.length);
        return resized;
    }

    /**
     * Returns an int array of the requested length, prefilled with
     * {@link MeshTopology#NONE} and overlaid with {@code source}.
     *
     * @param source       array to copy from
     * @param nextCapacity length of the returned array
     * @return new int array sized to {@code nextCapacity}
     */
    public static int[] resizeIntArray(int[] source, int nextCapacity) {
        int[] resized = new int[nextCapacity];
        fillWithNone(resized);
        System.arraycopy(source, 0, resized, 0, source.length);
        return resized;
    }

    /**
     * Returns a packed xyz float array sized for {@code nextCapacity} entries (i.e.
     * length {@code nextCapacity * 3}) with {@code source} copied into the prefix.
     *
     * @param source       array to copy from
     * @param nextCapacity number of xyz entries the returned array should hold
     * @return new float array sized to {@code nextCapacity * FLOATS_PER_VERTEX}
     */
    public static float[] resizeFloatTupleArray(float[] source, int nextCapacity) {
        float[] resized = new float[nextCapacity * FLOATS_PER_VERTEX];
        System.arraycopy(source, 0, resized, 0, source.length);
        return resized;
    }

    /**
     * Growth policy for parallel arrays: doubles the current capacity but never
     * returns less than the required size or the floor of 4.
     *
     * @param currentCapacity  existing array length
     * @param requiredCapacity minimum length the caller needs
     * @return new capacity to allocate
     */
    public static int nextCapacity(int currentCapacity, int requiredCapacity) {
        return Math.max(requiredCapacity, Math.max(NUM_4, currentCapacity * 2));
    }

    /**
     * Fills an int array with the {@link MeshTopology#NONE} sentinel.
     *
     * @param values array to overwrite in place
     */
    public static void fillWithNone(int[] values) {
        Arrays.fill(values, NONE);
    }

    /**
     * Builds a flat (v0, v1) edge index buffer with one pair per active edge.
     *
     * @return array of length {@code 2 * edgeCount()} holding endpoint vertex ids
     */
    public int[] getEdgeIndices() {
        int[] indices = new int[activeEdgeIds.size() * 2];
        for (int i = 0; i < activeEdgeIds.size(); i++) {
            int edgeId = activeEdgeIds.get(i);
            int he = edgeHalfEdge[edgeId];

            indices[i * 2] = halfEdgeVertex[he];
            indices[i * 2 + 1] = halfEdgeVertex[halfEdgeNext[he]];
        }
        return indices;
    }

    /**
     * Mean Euclidean length of active edges.
     *
     * @return mean Euclidean length of active edges, or 1 for an empty mesh
     */
    public float computeAverageEdgeLength() {
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        double sum = 0;
        int count = 0;
        for (int i = 0; i < activeEdgeIds.size(); i++) {
            int halfEdge = edgeHalfEdge[activeEdgeIds.get(i)];
            vertexPosition(halfEdgeVertex[halfEdge], a);
            vertexPosition(halfEdgeVertex[halfEdgeNext[halfEdge]], b);
            sum += Math.sqrt(
                    (b.x - a.x) * (b.x - a.x) +
                            (b.y - a.y) * (b.y - a.y) +
                            (b.z - a.z) * (b.z - a.z));
            count++;
        }
        return count == 0 ? 1f : (float) (sum / count);
    }

    /**
     * Bounding-box diagonal length over active vertices.
     *
     * @return diagonal of the axis-aligned bounding box of the active vertices, or
     *         0 for an empty mesh
     */
    public float computeBoundingBoxDiagonal() {
        if (vertexCount() == 0) {
            return 0f;
        }
        Vector3f p = new Vector3f();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < activeVertexIds.size(); i++) {
            vertexPosition(activeVertexIds.get(i), p);
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

}
