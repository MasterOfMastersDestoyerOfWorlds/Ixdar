package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.MeshValue;
import ixdar.common.exceptions.InvalidMeshTopologyException;
import ixdar.graphics.render.model.HalfEdgeCompiledMeshData;

public class HalfEdgeMesh implements MeshTopology, MeshValue {
    public static final int FLOATS_PER_VERTEX = 3;

    final Map<Long, Integer> halfEdgesByDirection;
    final IntIdList activeVertexIds;
    final IntIdList activeEdgeIds;
    final IntIdList activeFaceIds;
    final IntIdList activeHalfEdgeIds;
    final ArrayList<IntIdList> vertexOutgoingHalfEdges;
    final ArrayList<IntIdList> vertexEdges;
    final ArrayList<IntIdList> vertexFaces;
    final ArrayList<IntIdList> faceHalfEdges;
    final ArrayList<IntIdList> faceVertices;
    final ArrayList<IntIdList> faceEdges;

    float[] vertexPositions;
    float[] vertexNormals;
    int[] vertexOutgoing;
    boolean[] vertexActive;

    int[] edgeHalfEdge;
    boolean[] edgeActive;

    int[] faceHalfEdge;
    float[] faceNormals;
    boolean[] faceActive;

    int[] halfEdgeTwin;
    int[] halfEdgeNext;
    int[] halfEdgePrev;
    int[] halfEdgeVertex;
    int[] halfEdgeFace;
    int[] halfEdgeEdge;
    boolean[] halfEdgeActive;

    int nextVertexId;
    int nextEdgeId;
    int nextFaceId;
    int nextHalfEdgeId;

    public HalfEdgeMesh() {
        this(4, 4, 4, 8);
    }

    /**
     * @param vertexCapacity expected number of vertices
     * @param edgeCapacity   expected number of edges
     * @param faceCapacity   expected number of faces
     * @param halfEdgeCapacity expected number of half-edges
     */
    public HalfEdgeMesh(int vertexCapacity, int edgeCapacity, int faceCapacity, int halfEdgeCapacity) {
        int vc = Math.max(4, vertexCapacity);
        int ec = Math.max(4, edgeCapacity);
        int fc = Math.max(4, faceCapacity);
        int hc = Math.max(8, halfEdgeCapacity);

        this.halfEdgesByDirection = new HashMap<>(hc * 4 / 3 + 1);
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
     * Pre-sized topology for bulk quad construction (e.g. Catmull–Clark). Avoids array growth and
     * HashMap rehash during {@link HalfEdgeMeshEngine#addFaceInternal}.
     */
    HalfEdgeMesh(int maxV, int maxE, int maxF, int maxHe, int mapCapacity) {
        this.halfEdgesByDirection = new HashMap<>(mapCapacity, 1.0f);
        this.activeVertexIds = new IntIdList(Math.max(4, maxV));
        this.activeEdgeIds = new IntIdList(Math.max(4, maxE));
        this.activeFaceIds = new IntIdList(Math.max(4, maxF));
        this.activeHalfEdgeIds = new IntIdList(Math.max(4, maxHe));
        this.vertexOutgoingHalfEdges = new ArrayList<>(maxV);
        this.vertexEdges = new ArrayList<>(maxV);
        this.vertexFaces = new ArrayList<>(maxV);
        for (int i = 0; i < maxV; i++) {
            this.vertexOutgoingHalfEdges.add(new IntIdList(8));
            this.vertexEdges.add(new IntIdList(8));
            this.vertexFaces.add(new IntIdList(8));
        }
        this.faceHalfEdges = new ArrayList<>(maxF);
        this.faceVertices = new ArrayList<>(maxF);
        this.faceEdges = new ArrayList<>(maxF);
        for (int i = 0; i < maxF; i++) {
            this.faceHalfEdges.add(new IntIdList(4));
            this.faceVertices.add(new IntIdList(4));
            this.faceEdges.add(new IntIdList(4));
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

    public int addVertex(float x, float y, float z) {
        return HalfEdgeMeshEngine.addVertex(this, x, y, z);
    }

    public int addVertex(Vector3f position) {
        return addVertex(position.x, position.y, position.z);
    }

    public int addEdge(int startVertexId, int endVertexId) {
        return HalfEdgeMeshEngine.addEdge(this, startVertexId, endVertexId);
    }

    /**
     * Adds a face without updating normals. Call {@link #computeNormals()} when the mesh is finished
     * (or after edits that should refresh shading).
     */
    public int addFace(int... vertexIds) {
        return HalfEdgeMeshEngine.addFace(this, vertexIds);
    }

    public void removeFace(int faceId) {
        HalfEdgeMeshEngine.removeFace(this, faceId);
    }

    public void removeEdge(int edgeId) {
        HalfEdgeMeshEngine.removeEdge(this, edgeId);
    }

    public void removeVertex(int vertexId) {
        HalfEdgeMeshEngine.removeVertex(this, vertexId);
    }

    public void computeNormals() {
        HalfEdgeMeshEngine.computeNormals(this);
    }

    public HalfEdgeCompiledMeshData compileSurfaceData() {
        return HalfEdgeMeshEngine.compileSurfaceData(this);
    }

    public static HalfEdgeMesh buildFromIndexedMesh(float[] positions, int[] faceIndices) {
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
    }

    /**
     * Pre-sized mesh build for uniform face sizes (3 for triangles, 4 for quads). Does not compute
     * normals — call {@link #computeNormals()} when the mesh is ready for rendering.
     */
    public static HalfEdgeMesh bulkAllocate(float[] positions, int[] faceIndices, int vertsPerFace) {
        return HalfEdgeMeshEngine.bulkAllocate(positions, faceIndices, vertsPerFace);
    }

    @Override
    public int vertexCount() {
        return activeVertexIds.size();
    }

    @Override
    public int edgeCount() {
        return activeEdgeIds.size();
    }

    @Override
    public int faceCount() {
        return activeFaceIds.size();
    }

    @Override
    public int halfEdgeCount() {
        return activeHalfEdgeIds.size();
    }

    @Override
    public int vertexIdAt(int activeIndex) {
        return activeVertexIds.get(activeIndex);
    }

    @Override
    public int edgeIdAt(int activeIndex) {
        return activeEdgeIds.get(activeIndex);
    }

    @Override
    public int faceIdAt(int activeIndex) {
        return activeFaceIds.get(activeIndex);
    }

    @Override
    public int halfEdgeIdAt(int activeIndex) {
        return activeHalfEdgeIds.get(activeIndex);
    }

    @Override
    public boolean hasVertex(int vertexId) {
        return isActive(vertexActive, vertexId);
    }

    @Override
    public boolean hasEdge(int edgeId) {
        return isActive(edgeActive, edgeId);
    }

    @Override
    public boolean hasFace(int faceId) {
        return isActive(faceActive, faceId);
    }

    @Override
    public boolean hasHalfEdge(int halfEdgeId) {
        return isActive(halfEdgeActive, halfEdgeId);
    }

    @Override
    public Vector3f vertexPosition(int vertexId, Vector3f dest) {
        requireActiveVertex(vertexId);
        int offset = vertexOffset(vertexId);
        return dest.set(vertexPositions[offset], vertexPositions[offset + 1], vertexPositions[offset + 2]);
    }

    @Override
    public Vector3f vertexNormal(int vertexId, Vector3f dest) {
        requireActiveVertex(vertexId);
        int offset = vertexOffset(vertexId);
        return dest.set(vertexNormals[offset], vertexNormals[offset + 1], vertexNormals[offset + 2]);
    }

    @Override
    public int vertexOutgoingHalfEdge(int vertexId) {
        requireActiveVertex(vertexId);
        return vertexOutgoing[vertexId];
    }

    @Override
    public int vertexOutgoingHalfEdgeCount(int vertexId) {
        requireActiveVertex(vertexId);
        return vertexOutgoingHalfEdges.get(vertexId).size();
    }

    @Override
    public int vertexOutgoingHalfEdgeAt(int vertexId, int adjacencyIndex) {
        requireActiveVertex(vertexId);
        return vertexOutgoingHalfEdges.get(vertexId).get(adjacencyIndex);
    }

    @Override
    public int vertexEdgeCount(int vertexId) {
        requireActiveVertex(vertexId);
        return vertexEdges.get(vertexId).size();
    }

    @Override
    public int vertexEdgeAt(int vertexId, int adjacencyIndex) {
        requireActiveVertex(vertexId);
        return vertexEdges.get(vertexId).get(adjacencyIndex);
    }

    @Override
    public int vertexFaceCount(int vertexId) {
        requireActiveVertex(vertexId);
        return vertexFaces.get(vertexId).size();
    }

    @Override
    public int vertexFaceAt(int vertexId, int adjacencyIndex) {
        requireActiveVertex(vertexId);
        return vertexFaces.get(vertexId).get(adjacencyIndex);
    }

    @Override
    public boolean isBoundaryVertex(int vertexId) {
        requireActiveVertex(vertexId);
        IntIdList edges = vertexEdges.get(vertexId);
        for (int i = 0; i < edges.size(); i++) {
            if (isBoundaryEdge(edges.get(i))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int edgeHalfEdge(int edgeId) {
        requireActiveEdge(edgeId);
        return edgeHalfEdge[edgeId];
    }

    @Override
    public boolean isBoundaryEdge(int edgeId) {
        requireActiveEdge(edgeId);
        int firstHalfEdge = edgeHalfEdge[edgeId];
        int secondHalfEdge = halfEdgeTwin[firstHalfEdge];
        return halfEdgeFace[firstHalfEdge] == NONE || halfEdgeFace[secondHalfEdge] == NONE;
    }

    @Override
    public int faceHalfEdge(int faceId) {
        requireActiveFace(faceId);
        return faceHalfEdge[faceId];
    }

    @Override
    public int faceHalfEdgeCount(int faceId) {
        requireActiveFace(faceId);
        return faceHalfEdges.get(faceId).size();
    }

    @Override
    public int faceHalfEdgeAt(int faceId, int adjacencyIndex) {
        requireActiveFace(faceId);
        return faceHalfEdges.get(faceId).get(adjacencyIndex);
    }

    @Override
    public int faceVertexCount(int faceId) {
        requireActiveFace(faceId);
        return faceVertices.get(faceId).size();
    }

    @Override
    public int faceVertexAt(int faceId, int adjacencyIndex) {
        requireActiveFace(faceId);
        return faceVertices.get(faceId).get(adjacencyIndex);
    }

    @Override
    public int faceEdgeCount(int faceId) {
        requireActiveFace(faceId);
        return faceEdges.get(faceId).size();
    }

    @Override
    public int faceEdgeAt(int faceId, int adjacencyIndex) {
        requireActiveFace(faceId);
        return faceEdges.get(faceId).get(adjacencyIndex);
    }

    @Override
    public Vector3f faceNormal(int faceId, Vector3f dest) {
        requireActiveFace(faceId);
        int offset = faceOffset(faceId);
        return dest.set(faceNormals[offset], faceNormals[offset + 1], faceNormals[offset + 2]);
    }

    @Override
    public int halfEdgeVertex(int halfEdgeId) {
        requireActiveHalfEdge(halfEdgeId);
        return halfEdgeVertex[halfEdgeId];
    }

    @Override
    public int halfEdgeEndVertex(int halfEdgeId) {
        requireActiveHalfEdge(halfEdgeId);
        int next = halfEdgeNext[halfEdgeId];
        requireActiveHalfEdge(next);
        return halfEdgeVertex[next];
    }

    @Override
    public int halfEdgeTwin(int halfEdgeId) {
        requireActiveHalfEdge(halfEdgeId);
        return halfEdgeTwin[halfEdgeId];
    }

    @Override
    public int halfEdgeNext(int halfEdgeId) {
        requireActiveHalfEdge(halfEdgeId);
        return halfEdgeNext[halfEdgeId];
    }

    @Override
    public int halfEdgePrev(int halfEdgeId) {
        requireActiveHalfEdge(halfEdgeId);
        return halfEdgePrev[halfEdgeId];
    }

    @Override
    public int halfEdgeFace(int halfEdgeId) {
        requireActiveHalfEdge(halfEdgeId);
        return halfEdgeFace[halfEdgeId];
    }

    @Override
    public int halfEdgeEdge(int halfEdgeId) {
        requireActiveHalfEdge(halfEdgeId);
        return halfEdgeEdge[halfEdgeId];
    }

    @Override
    public boolean isBoundaryHalfEdge(int halfEdgeId) {
        requireActiveHalfEdge(halfEdgeId);
        return halfEdgeFace[halfEdgeId] == NONE;
    }

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

    @Override
    public Vector3f center(Vector3f dest) {
        Vector3f min = boundsMin(new Vector3f());
        Vector3f max = boundsMax(new Vector3f());
        return dest.set(min).add(max).mul(0.5f);
    }

    @Override
    public float radius() {
        if (activeVertexIds.isEmpty()) {
            return 0f;
        }
        Vector3f center = center(new Vector3f());
        float maxDistanceSquared = 0f;
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

    int createVertexSlot(float x, float y, float z) {
        int vertexId = nextVertexId++;
        ensureVertexCapacity(vertexId + 1);
        vertexActive[vertexId] = true;
        activeVertexIds.add(vertexId);
        vertexOutgoing[vertexId] = NONE;
        setVector(vertexPositions, vertexId, x, y, z);
        setVector(vertexNormals, vertexId, 0f, 0f, 0f);
        ensureVertexAdjacencySlot(vertexOutgoingHalfEdges, vertexId).clear();
        ensureVertexAdjacencySlot(vertexEdges, vertexId).clear();
        ensureVertexAdjacencySlot(vertexFaces, vertexId).clear();
        return vertexId;
    }

    int createEdgeSlot() {
        int edgeId = nextEdgeId++;
        ensureEdgeCapacity(edgeId + 1);
        edgeActive[edgeId] = true;
        activeEdgeIds.add(edgeId);
        edgeHalfEdge[edgeId] = NONE;
        return edgeId;
    }

    int createFaceSlot() {
        int faceId = nextFaceId++;
        ensureFaceCapacity(faceId + 1);
        faceActive[faceId] = true;
        activeFaceIds.add(faceId);
        faceHalfEdge[faceId] = NONE;
        setVector(faceNormals, faceId, 0f, 0f, 0f);
        ensureFaceAdjacencySlot(faceHalfEdges, faceId).clear();
        ensureFaceAdjacencySlot(faceVertices, faceId).clear();
        ensureFaceAdjacencySlot(faceEdges, faceId).clear();
        return faceId;
    }

    int createHalfEdgeSlot(int vertexId) {
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

    void deactivateFace(int faceId) {
        faceActive[faceId] = false;
        activeFaceIds.removeValue(faceId);
        faceHalfEdge[faceId] = NONE;
        setVector(faceNormals, faceId, 0f, 0f, 0f);
        faceHalfEdges.get(faceId).clear();
        faceVertices.get(faceId).clear();
        faceEdges.get(faceId).clear();
    }

    void deactivateEdge(int edgeId) {
        edgeActive[edgeId] = false;
        activeEdgeIds.removeValue(edgeId);
        edgeHalfEdge[edgeId] = NONE;
    }

    void deactivateHalfEdge(int halfEdgeId) {
        halfEdgeActive[halfEdgeId] = false;
        activeHalfEdgeIds.removeValue(halfEdgeId);
        halfEdgeTwin[halfEdgeId] = NONE;
        halfEdgeNext[halfEdgeId] = NONE;
        halfEdgePrev[halfEdgeId] = NONE;
        halfEdgeVertex[halfEdgeId] = NONE;
        halfEdgeFace[halfEdgeId] = NONE;
        halfEdgeEdge[halfEdgeId] = NONE;
    }

    void deactivateVertex(int vertexId) {
        vertexActive[vertexId] = false;
        activeVertexIds.removeValue(vertexId);
        vertexOutgoing[vertexId] = NONE;
        setVector(vertexPositions, vertexId, 0f, 0f, 0f);
        setVector(vertexNormals, vertexId, 0f, 0f, 0f);
        vertexOutgoingHalfEdges.get(vertexId).clear();
        vertexEdges.get(vertexId).clear();
        vertexFaces.get(vertexId).clear();
    }

    void requireActiveVertex(int vertexId) {
        if (!hasVertex(vertexId)) {
            throw new InvalidMeshTopologyException("Vertex " + vertexId + " is not active");
        }
    }

    void requireActiveEdge(int edgeId) {
        if (!hasEdge(edgeId)) {
            throw new InvalidMeshTopologyException("Edge " + edgeId + " is not active");
        }
    }

    void requireActiveFace(int faceId) {
        if (!hasFace(faceId)) {
            throw new InvalidMeshTopologyException("Face " + faceId + " is not active");
        }
    }

    void requireActiveHalfEdge(int halfEdgeId) {
        if (!hasHalfEdge(halfEdgeId)) {
            throw new InvalidMeshTopologyException("Half-edge " + halfEdgeId + " is not active");
        }
    }

    long directedKey(int startIndex, int endIndex) {
        return (((long) startIndex) << 32) | (endIndex & 0xffffffffL);
    }

    int vertexOffset(int vertexId) {
        return vertexId * FLOATS_PER_VERTEX;
    }

    int faceOffset(int faceId) {
        return faceId * FLOATS_PER_VERTEX;
    }

    private void ensureVertexCapacity(int requiredVertexCount) {
        if (requiredVertexCount <= vertexActive.length) {
            return;
        }
        int nextCapacity = nextCapacity(vertexActive.length, requiredVertexCount);
        vertexActive = resizeBooleanArray(vertexActive, nextCapacity);
        vertexOutgoing = resizeIntArray(vertexOutgoing, nextCapacity);
        vertexPositions = resizeFloatTupleArray(vertexPositions, nextCapacity);
        vertexNormals = resizeFloatTupleArray(vertexNormals, nextCapacity);
    }

    private void ensureEdgeCapacity(int requiredEdgeCount) {
        if (requiredEdgeCount <= edgeActive.length) {
            return;
        }
        int nextCapacity = nextCapacity(edgeActive.length, requiredEdgeCount);
        edgeActive = resizeBooleanArray(edgeActive, nextCapacity);
        edgeHalfEdge = resizeIntArray(edgeHalfEdge, nextCapacity);
    }

    private void ensureFaceCapacity(int requiredFaceCount) {
        if (requiredFaceCount <= faceActive.length) {
            return;
        }
        int nextCapacity = nextCapacity(faceActive.length, requiredFaceCount);
        faceActive = resizeBooleanArray(faceActive, nextCapacity);
        faceHalfEdge = resizeIntArray(faceHalfEdge, nextCapacity);
        faceNormals = resizeFloatTupleArray(faceNormals, nextCapacity);
    }

    private void ensureHalfEdgeCapacity(int requiredHalfEdgeCount) {
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

    private IntIdList ensureVertexAdjacencySlot(ArrayList<IntIdList> adjacency, int vertexId) {
        while (adjacency.size() <= vertexId) {
            adjacency.add(new IntIdList());
        }
        return adjacency.get(vertexId);
    }

    private IntIdList ensureFaceAdjacencySlot(ArrayList<IntIdList> adjacency, int faceId) {
        while (adjacency.size() <= faceId) {
            adjacency.add(new IntIdList());
        }
        return adjacency.get(faceId);
    }

    private static boolean isActive(boolean[] active, int id) {
        return id >= 0 && id < active.length && active[id];
    }

    private static void setVector(float[] target, int id, float x, float y, float z) {
        int offset = id * FLOATS_PER_VERTEX;
        target[offset] = x;
        target[offset + 1] = y;
        target[offset + 2] = z;
    }

    private static boolean[] resizeBooleanArray(boolean[] source, int nextCapacity) {
        boolean[] resized = new boolean[nextCapacity];
        System.arraycopy(source, 0, resized, 0, source.length);
        return resized;
    }

    private static int[] resizeIntArray(int[] source, int nextCapacity) {
        int[] resized = new int[nextCapacity];
        fillWithNone(resized);
        System.arraycopy(source, 0, resized, 0, source.length);
        return resized;
    }

    private static float[] resizeFloatTupleArray(float[] source, int nextCapacity) {
        float[] resized = new float[nextCapacity * FLOATS_PER_VERTEX];
        System.arraycopy(source, 0, resized, 0, source.length);
        return resized;
    }

    private static int nextCapacity(int currentCapacity, int requiredCapacity) {
        return Math.max(requiredCapacity, Math.max(4, currentCapacity * 2));
    }

    private static void fillWithNone(int[] values) {
        Arrays.fill(values, NONE);
    }
    
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
}
