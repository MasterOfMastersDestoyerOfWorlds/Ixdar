package ixdar.geometry.mesh.data.representation;

import java.util.Arrays;

import org.joml.Vector3f;


/**
 * Mutable half-edge mesh: separate id ranges for vertices, edges, faces, and
 * half-edges, each with an active flag and per-id parallel arrays plus
 * {@link IntIdTable} adjacency. Editing methods delegate to
 * {@link HalfEdgeMeshOps}.
 */
public class HalfEdgeMesh implements MeshTopology {
    public static final String IS_NOT_ACTIVE = " is not active";
    public static final int MIN_CAPACITY = 4;
    public static final int DEFAULT_ADJACENCY_CAPACITY = 8;
    public static final int FLOATS_PER_VERTEX = 3;
    public static final int TRIANGLE_CORNERS = 3;

    public static final String SOURCE_VERTEX = "source_vertex";

    public static final String SOURCE_EDGE = "source_edge";

    public static final String SOURCE_FACE = "source_face";

    public static final String SOURCE_OPERAND = "source_operand";

    public final ActiveIdSet activeVertexIds;
    public final ActiveIdSet activeEdgeIds;
    public final ActiveIdSet activeFaceIds;
    public final ActiveIdSet activeHalfEdgeIds;
    public final IntIdTable vertexOutgoingHalfEdges;
    public final IntIdTable vertexEdges;
    public final IntIdTable vertexFaces;
    public final IntIdTable faceHalfEdges;
    public final IntIdTable faceVertices;
    public final IntIdTable faceEdges;

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

    /**
     * One array per declared vertex layer, indexed by vertex id. A vertex minted on a split edge
     * takes the value of the edge layer {@link #vertexLayerSplitEdgeLayer} names; every other new
     * vertex takes the layer's fill, and a kept vertex keeps its value.
     */
    public int[][] vertexLayers = new int[0][];
    public int[] vertexLayerFill = new int[0];

    /** Edge layer each vertex layer reads on an edge split, or {@link MeshTopology#NONE}. */
    public int[] vertexLayerSplitEdgeLayer = new int[0];

    /**
     * One array per declared edge layer, indexed by edge id. Both halves of a split edge inherit
     * its value, an edge re-created between the same two vertices gets its old value back, and
     * every other new edge takes the layer's fill.
     */
    public int[][] edgeLayers = new int[0][];
    public int[] edgeLayerFill = new int[0];

    /**
     * One array per declared face layer, indexed by face id. Every face an operator creates
     * inherits the value of the face it replaces or splits off from.
     */
    public int[][] faceLayers = new int[0][];
    public int[] faceLayerFill = new int[0];

    /** Name of each vertex layer, or null for one only its declarer addresses by index. */
    public String[] vertexLayerName = new String[0];

    /** Name of each face layer, or null for one only its declarer addresses by index. */
    public String[] faceLayerName = new String[0];

    /** Edges of faces being replaced, kept so a re-created edge recovers its layer values. */
    public int[] replacedEdgeIds = new int[0];
    public int[] replacedEdgeStarts = new int[0];
    public int[] replacedEdgeEnds = new int[0];
    public int replacedEdgeSize;

    public int nextVertexId;
    public int nextEdgeId;
    public int nextFaceId;
    public int nextHalfEdgeId;

    boolean[] edgeActive;
    boolean[] faceActive;
    boolean[] halfEdgeActive;

    /** Packed xyz by vertex id; written only through methods that advance {@link #geometryVersion}. */
    private float[] vertexPositions;

    /** Advanced by every position write and every vertex or face added or removed. */
    private int geometryVersion;

    /**
     * Cached bounding box, minimum xyz then maximum xyz, valid while {@link #boundsVersion} equals
     * {@link #geometryVersion}.
     *
     * <p>Each cache's volatile version is written after its value, so a reader seeing the current
     * version sees the whole value.
     */
    private final float[] boundsCache = new float[2 * FLOATS_PER_VERTEX];
    private float radiusCache;
    private volatile int boundsVersion = -1;
    private double signedVolumeCache;
    private volatile int signedVolumeVersion = -1;
    private float[] vertexAreasCache;
    private volatile int vertexAreasVersion = -1;

    /**
     * Creates an empty mesh with small default capacity for incremental
     * construction.
     */
    public HalfEdgeMesh() {
        this(MIN_CAPACITY, MIN_CAPACITY, MIN_CAPACITY, DEFAULT_ADJACENCY_CAPACITY);
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
        int vc = Math.max(MIN_CAPACITY, vertexCapacity);
        int ec = Math.max(MIN_CAPACITY, edgeCapacity);
        int fc = Math.max(MIN_CAPACITY, faceCapacity);
        int hc = Math.max(DEFAULT_ADJACENCY_CAPACITY, halfEdgeCapacity);

        this.activeVertexIds = new ActiveIdSet(vc);
        this.activeEdgeIds = new ActiveIdSet(ec);
        this.activeFaceIds = new ActiveIdSet(fc);
        this.activeHalfEdgeIds = new ActiveIdSet(hc);
        this.vertexOutgoingHalfEdges = new IntIdTable(DEFAULT_ADJACENCY_CAPACITY);
        this.vertexEdges = new IntIdTable(DEFAULT_ADJACENCY_CAPACITY);
        this.vertexFaces = new IntIdTable(DEFAULT_ADJACENCY_CAPACITY);
        this.faceHalfEdges = new IntIdTable(MIN_CAPACITY);
        this.faceVertices = new IntIdTable(MIN_CAPACITY);
        this.faceEdges = new IntIdTable(MIN_CAPACITY);
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
     * Convenience wrapper for
     * {@link HalfEdgeMeshOps#addVertex(HalfEdgeMesh, float, float, float)}.
     *
     * @param x x coordinate
     * @param y y coordinate
     * @param z z coordinate
     * @return id of the newly created vertex
     */
    public int addVertex(float x, float y, float z) {
        return HalfEdgeMeshOps.addVertex(this, x, y, z);
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
     * {@link HalfEdgeMeshOps#addEdge(HalfEdgeMesh, int, int)}.
     *
     * @param startVertexId active vertex on one end
     * @param endVertexId   active vertex on the other end
     * @return id of the newly created edge
     */
    public int addEdge(int startVertexId, int endVertexId) {
        return HalfEdgeMeshOps.addEdge(this, startVertexId, endVertexId);
    }

    /**
     * Adds a face without updating normals. Call {@link #computeNormals()} when the
     * mesh is finished (or after edits that should refresh shading).
     *
     * @param vertexIds ordered vertex ids defining the face (length &ge; 3)
     * @return id of the newly created face
     */
    public int addFace(int... vertexIds) {
        return HalfEdgeMeshOps.addFace(this, vertexIds);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshOps#splitEdge(HalfEdgeMesh, int, float, float, float)}.
     *
     * @param edgeId   active edge to split; each incident face must be a triangle
     * @param position 3D position of the split point
     * @return id of the newly created vertex on the split point
     */
    public int splitEdge(int edgeId, Vector3f position) {
        return HalfEdgeMeshOps.splitEdge(this, edgeId, position.x, position.y, position.z);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshOps#splitFace(HalfEdgeMesh, int, float, float, float)}.
     *
     * @param faceId   active face to split
     * @param position 3D position of the new vertex
     * @return id of the new vertex
     */
    public int splitFace(int faceId, Vector3f position) {
        return HalfEdgeMeshOps.splitFace(this, faceId, position.x, position.y, position.z);
    }

    /**
     * Convenience wrapper for {@link HalfEdgeMeshOps#flipEdge(HalfEdgeMesh, int)}.
     *
     * @param edgeId interior edge between two triangles
     * @return id of the new diagonal
     */
    public int flipEdge(int edgeId) {
        return HalfEdgeMeshOps.flipEdge(this, edgeId);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshOps#collapseEdge(HalfEdgeMesh, int, float, float, float)}.
     *
     * @param edgeId   edge to collapse
     * @param position where the kept vertex ends up
     * @return id of the kept vertex
     */
    public int collapseEdge(int edgeId, Vector3f position) {
        return HalfEdgeMeshOps.collapseEdge(this, edgeId, position.x, position.y, position.z);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshOps#replaceFaces(HalfEdgeMesh, int, int[], int[])}.
     *
     * @param parentFaceId     face whose layer values every new face inherits
     * @param retiredFaceIds   active faces to remove, in removal order
     * @param triangleCorners  corners of the new triangles, three per face, in creation order
     * @return id of the first new face; the rest follow consecutively
     */
    public int replaceFaces(int parentFaceId, int[] retiredFaceIds, int[] triangleCorners) {
        return HalfEdgeMeshOps.replaceFaces(this, parentFaceId, retiredFaceIds, triangleCorners);
    }

    /**
     * Adds a vertex layer, every existing vertex set to the fill.
     *
     * @param fill           value of a vertex the layer has no other value for
     * @param splitEdgeLayer edge layer a vertex minted on a split edge copies, or
     *                       {@link MeshTopology#NONE} for the fill
     * @return the layer's index into {@link #vertexLayers}
     */
    public int declareVertexLayer(int fill, int splitEdgeLayer) {
        int layer = vertexLayers.length;
        vertexLayers = Arrays.copyOf(vertexLayers, layer + 1);
        vertexLayers[layer] = growLayer(new int[0], vertexActive.length, fill);
        vertexLayerFill = Arrays.copyOf(vertexLayerFill, layer + 1);
        vertexLayerFill[layer] = fill;
        vertexLayerSplitEdgeLayer = Arrays.copyOf(vertexLayerSplitEdgeLayer, layer + 1);
        vertexLayerSplitEdgeLayer[layer] = splitEdgeLayer;
        vertexLayerName = Arrays.copyOf(vertexLayerName, layer + 1);
        return layer;
    }

    /**
     * The vertex layer of a name.
     *
     * @param name layer name, such as {@link #SOURCE_VERTEX}
     * @return its index into {@link #vertexLayers}, or {@link MeshTopology#NONE} when undeclared
     */
    public int vertexLayer(String name) {
        for (int layer = 0; layer < vertexLayerName.length; layer++) {
            if (name.equals(vertexLayerName[layer])) {
                return layer;
            }
        }
        return NONE;
    }

    /**
     * The face layer of a name.
     *
     * @param name layer name, such as {@link #SOURCE_FACE}
     * @return its index into {@link #faceLayers}, or {@link MeshTopology#NONE} when undeclared
     */
    public int faceLayer(String name) {
        for (int layer = 0; layer < faceLayerName.length; layer++) {
            if (name.equals(faceLayerName[layer])) {
                return layer;
            }
        }
        return NONE;
    }

    /**
     * Writes a named vertex layer, declaring it filled with {@link MeshTopology#NONE} when absent.
     *
     * @param name   layer name
     * @param values value per vertex id; ids past its end keep the fill
     * @return the layer's index into {@link #vertexLayers}
     */
    public int writeVertexLayer(String name, int[] values) {
        int layer = vertexLayer(name);
        if (layer == NONE) {
            layer = declareVertexLayer(NONE, NONE);
            vertexLayerName[layer] = name;
        }
        System.arraycopy(values, 0, vertexLayers[layer], 0,
                Math.min(values.length, vertexLayers[layer].length));
        return layer;
    }

    /**
     * Writes a named face layer, declaring it filled with {@link MeshTopology#NONE} when absent.
     *
     * @param name   layer name
     * @param values value per face id; ids past its end keep the fill
     * @return the layer's index into {@link #faceLayers}
     */
    public int writeFaceLayer(String name, int[] values) {
        int layer = faceLayer(name);
        if (layer == NONE) {
            layer = declareFaceLayer(NONE);
            faceLayerName[layer] = name;
        }
        System.arraycopy(values, 0, faceLayers[layer], 0,
                Math.min(values.length, faceLayers[layer].length));
        return layer;
    }

    /**
     * Records where an op that built this mesh took each element from in its input, as the
     * {@link #SOURCE_VERTEX}, {@link #SOURCE_EDGE} and {@link #SOURCE_FACE} layers the slot carry
     * reads. A null array leaves that layer undeclared, so slots of that domain do not follow.
     *
     * @param sourceVertex input vertex id per vertex id, {@link MeshTopology#NONE} for a minted one
     * @param sourceEdge   input edge a minted vertex lies on, per vertex id, or null
     * @param sourceFace   input face id per face id
     */
    public void recordSources(int[] sourceVertex, int[] sourceEdge, int[] sourceFace) {
        if (sourceVertex != null) {
            writeVertexLayer(SOURCE_VERTEX, sourceVertex);
        }
        if (sourceEdge != null) {
            writeVertexLayer(SOURCE_EDGE, sourceEdge);
        }
        if (sourceFace != null) {
            writeFaceLayer(SOURCE_FACE, sourceFace);
        }
    }

    /**
     * Records which input of a multi-input op each vertex and face came from, as the
     * {@link #SOURCE_OPERAND} layers; the source layers then index that input's mesh.
     *
     * @param vertexOperand input index per vertex id, or null when the op tracks no vertices
     * @param faceOperand   input index per face id
     */
    public void recordOperands(int[] vertexOperand, int[] faceOperand) {
        if (vertexOperand != null) {
            writeVertexLayer(SOURCE_OPERAND, vertexOperand);
        }
        writeFaceLayer(SOURCE_OPERAND, faceOperand);
    }

    /**
     * Adds an edge layer, every existing edge set to the fill.
     *
     * @param fill value of an edge the layer has no other value for
     * @return the layer's index into {@link #edgeLayers}
     */
    public int declareEdgeLayer(int fill) {
        int layer = edgeLayers.length;
        edgeLayers = Arrays.copyOf(edgeLayers, layer + 1);
        edgeLayers[layer] = growLayer(new int[0], edgeActive.length, fill);
        edgeLayerFill = Arrays.copyOf(edgeLayerFill, layer + 1);
        edgeLayerFill[layer] = fill;
        return layer;
    }

    /**
     * Adds a face layer, every existing face set to the fill.
     *
     * @param fill value of a face the layer has no other value for
     * @return the layer's index into {@link #faceLayers}
     */
    public int declareFaceLayer(int fill) {
        int layer = faceLayers.length;
        faceLayers = Arrays.copyOf(faceLayers, layer + 1);
        faceLayers[layer] = growLayer(new int[0], faceActive.length, fill);
        faceLayerFill = Arrays.copyOf(faceLayerFill, layer + 1);
        faceLayerFill[layer] = fill;
        faceLayerName = Arrays.copyOf(faceLayerName, layer + 1);
        return layer;
    }

    /**
     * The corner of a triangle that is neither given vertex.
     *
     * @param faceId  triangle face to read
     * @param vertexA first excluded corner
     * @param vertexB second excluded corner
     * @throws IllegalStateException when the face has no third corner
     * @return the remaining corner
     */
    public int faceOppositeCorner(int faceId, int vertexA, int vertexB) {
        for (int corner = 0; corner < 3; corner++) {
            int vertex = faceVertexAt(faceId, corner);
            if (vertex != vertexA && vertex != vertexB) {
                return vertex;
            }
        }
        throw new IllegalStateException("face " + faceId + " has no corner besides "
                + vertexA + " and " + vertexB);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshOps#removeFace(HalfEdgeMesh, int)}.
     *
     * @param faceId active face id to remove
     */
    public void removeFace(int faceId) {
        HalfEdgeMeshOps.removeFace(this, faceId);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshOps#removeFaceKeepingNormals(HalfEdgeMesh, int)}.
     *
     * @param faceId active face id to remove
     */
    public void removeFaceKeepingNormals(int faceId) {
        HalfEdgeMeshOps.removeFaceKeepingNormals(this, faceId);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshOps#removeEdge(HalfEdgeMesh, int)}.
     *
     * @param edgeId active edge id with no incident faces
     */
    public void removeEdge(int edgeId) {
        HalfEdgeMeshOps.removeEdge(this, edgeId);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshOps#removeVertex(HalfEdgeMesh, int)}.
     *
     * @param vertexId active vertex id with no incident topology
     */
    public void removeVertex(int vertexId) {
        HalfEdgeMeshOps.removeVertex(this, vertexId);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshOps#computeNormals(HalfEdgeMesh)}.
     */
    public void computeNormals() {
        HalfEdgeMeshOps.computeNormals(this);
    }

    /**
     * Convenience wrapper for
     * {@link HalfEdgeMeshOps#buildFromIndexedMesh(float[], int[])}.
     *
     * @param positions   packed xyz triples
     * @param faceIndices triangle vertex indices in groups of three
     * @return populated half-edge mesh with normals computed
     */
    public static HalfEdgeMesh buildFromIndexedMesh(float[] positions, int[] faceIndices) {
        return HalfEdgeMeshOps.buildFromIndexedMesh(positions, faceIndices);
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
        return HalfEdgeMeshOps.bulkAllocate(positions, faceIndices, vertsPerFace);
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexCount() {
        return activeVertexIds.size();
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexIdBound() {
        return nextVertexId;
    }

    /** {@inheritDoc}. */
    @Override
    public int edgeIdBound() {
        return nextEdgeId;
    }

    /** {@inheritDoc}. */
    @Override
    public int faceIdBound() {
        return nextFaceId;
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgeIdBound() {
        return nextHalfEdgeId;
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

    /**
     * The active-face index of a face id, the inverse of {@link #faceIdAt}.
     *
     * @param faceId face id to look up
     * @return the face's active index, or -1 when the face is not active
     */
    public int activeFaceIndexOf(int faceId) {
        return activeFaceIds.indexOf(faceId);
    }

    /**
     * The active-edge index of an edge id, the inverse of {@link #edgeIdAt}.
     *
     * @param edgeId edge id to look up
     * @return the edge's active index, or -1 when the edge is not active
     */
    public int activeEdgeIndexOf(int edgeId) {
        return activeEdgeIds.indexOf(edgeId);
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

    /**
     * Allocating convenience form of {@link #vertexPosition(int, Vector3f)}.
     *
     * @param vertexId vertex id
     * @return new {@link Vector3f} with the vertex position
     */
    public Vector3f vertexPosition(int vertexId) {
        int offset = vertexOffset(vertexId);
        return new Vector3f(vertexPositions[offset], vertexPositions[offset + 1], vertexPositions[offset + 2]);
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
        return vertexOutgoingHalfEdges.size(vertexId);
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexOutgoingHalfEdgeAt(int vertexId, int adjacencyIndex) {
        return vertexOutgoingHalfEdges.get(vertexId, adjacencyIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexEdgeCount(int vertexId) {
        return vertexEdges.size(vertexId);
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexEdgeAt(int vertexId, int adjacencyIndex) {
        return vertexEdges.get(vertexId, adjacencyIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexFaceCount(int vertexId) {
        return vertexFaces.size(vertexId);
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexFaceAt(int vertexId, int adjacencyIndex) {
        return vertexFaces.get(vertexId, adjacencyIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public boolean isBoundaryVertex(int vertexId) {
        int edgeCount = vertexEdges.size(vertexId);
        for (int i = 0; i < edgeCount; i++) {
            if (isBoundaryEdge(vertexEdges.get(vertexId, i))) {
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
    public float edgeLength(int edgeId) {
        int halfEdge = edgeHalfEdge[edgeId];
        int tail = vertexOffset(halfEdgeVertex(halfEdge));
        int head = vertexOffset(halfEdgeEndVertex(halfEdge));
        float dx = vertexPositions[head] - vertexPositions[tail];
        float dy = vertexPositions[head + 1] - vertexPositions[tail + 1];
        float dz = vertexPositions[head + 2] - vertexPositions[tail + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** {@inheritDoc}. */
    @Override
    public Vector3f edgeMidpoint(int edgeId, Vector3f dest) {
        int halfEdge = edgeHalfEdge[edgeId];
        int tail = vertexOffset(halfEdgeVertex(halfEdge));
        int head = vertexOffset(halfEdgeEndVertex(halfEdge));
        return dest.set(vertexPositions[tail] + vertexPositions[head],
                vertexPositions[tail + 1] + vertexPositions[head + 1],
                vertexPositions[tail + 2] + vertexPositions[head + 2]).mul(0.5f);
    }

    /**
     * The face on the far side of one of {@code faceId}'s edges.
     *
     * @param faceId face the edge belongs to
     * @param edgeId edge to cross
     * @return the neighbouring face id, or {@link MeshTopology#NONE} at a boundary
     */
    public int faceAcrossEdge(int faceId, int edgeId) {
        int halfEdge = edgeHalfEdge[edgeId];
        int face = halfEdgeFace[halfEdge];
        return face != faceId ? face : halfEdgeFace[halfEdgeTwin[halfEdge]];
    }

    /** {@inheritDoc}. */
    @Override
    public int faceHalfEdge(int faceId) {
        return faceHalfEdge[faceId];
    }

    /** {@inheritDoc}. */
    @Override
    public int faceHalfEdgeCount(int faceId) {
        return faceHalfEdges.size(faceId);
    }

    /** {@inheritDoc}. */
    @Override
    public int faceHalfEdgeAt(int faceId, int adjacencyIndex) {
        return faceHalfEdges.get(faceId, adjacencyIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public int faceVertexCount(int faceId) {
        return faceVertices.size(faceId);
    }

    /** {@inheritDoc}. */
    @Override
    public int faceVertexAt(int faceId, int adjacencyIndex) {
        return faceVertices.get(faceId, adjacencyIndex);
    }

    /** {@inheritDoc}. */
    @Override
    public int faceEdgeCount(int faceId) {
        return faceEdges.size(faceId);
    }

    /** {@inheritDoc}. */
    @Override
    public int faceEdgeAt(int faceId, int adjacencyIndex) {
        return faceEdges.get(faceId, adjacencyIndex);
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
     * Allocating convenience form of {@link #faceNormal(int, Vector3f)}.
     *
     * @param faceId face id
     * @return new {@link Vector3f} with the cached face normal
     */
    public Vector3f faceNormal(int faceId) {
        int offset = faceOffset(faceId);
        return new Vector3f(faceNormals[offset], faceNormals[offset + 1], faceNormals[offset + 2]);
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

    /**
     * {@inheritDoc} Reads the twin's start vertex rather than {@code halfEdgeNext}'s, so a
     * boundary half-edge — which carries no face and therefore no {@code next} — still answers.
     */
    @Override
    public int halfEdgeEndVertex(int halfEdgeId) {
        return halfEdgeVertex[halfEdgeTwin[halfEdgeId]];
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

    /** {@inheritDoc} Cached until the geometry next changes. */
    @Override
    public Vector3f boundsMin(Vector3f dest) {
        float[] box = bounds();
        return dest.set(box[0], box[1], box[2]);
    }

    /** {@inheritDoc} Cached until the geometry next changes. */
    @Override
    public Vector3f boundsMax(Vector3f dest) {
        float[] box = bounds();
        return dest.set(box[3], box[4], box[5]);
    }

    /** {@inheritDoc} Cached until the geometry next changes. */
    @Override
    public Vector3f center(Vector3f dest) {
        float[] box = bounds();
        return dest.set(box[0] + box[3], box[1] + box[4], box[2] + box[5]).mul(0.5f);
    }

    /** {@inheritDoc} Cached until the geometry next changes. */
    @Override
    public float radius() {
        bounds();
        return radiusCache;
    }

    /** {@inheritDoc} Cached until the geometry next changes. */
    @Override
    public double signedVolume() {
        int version = geometryVersion;
        if (signedVolumeVersion != version) {
            signedVolumeCache = MeshTopology.super.signedVolume();
            signedVolumeVersion = version;
        }
        return signedVolumeCache;
    }

    /** {@inheritDoc} Cached until the geometry next changes; each call returns a fresh copy. */
    @Override
    public float[] vertexAreas() {
        int version = geometryVersion;
        if (vertexAreasVersion != version) {
            vertexAreasCache = MeshTopology.super.vertexAreas();
            vertexAreasVersion = version;
        }
        return vertexAreasCache.clone();
    }

    /**
     * Move one vertex.
     *
     * @param vertexId active vertex to move
     * @param x        new x coordinate
     * @param y        new y coordinate
     * @param z        new z coordinate
     */
    public void setVertexPosition(int vertexId, float x, float y, float z) {
        setVector(vertexPositions, vertexId, x, y, z);
        geometryVersion++;
    }

    /**
     * Replace the position storage with a copy of a bulk build's positions, indexed by vertex id.
     *
     * @param positions   packed xyz by vertex id, at least {@code vertexCount} triples
     * @param vertexCount number of vertex ids the copy addresses
     */
    public void setPositionsById(float[] positions, int vertexCount) {
        vertexPositions = Arrays.copyOf(positions, vertexCount * FLOATS_PER_VERTEX);
        geometryVersion++;
    }

    /**
     * The cached box at the current geometry version, refilled when stale: one pass for the box,
     * one for the radius about its centre; all zero for a mesh with no vertices.
     *
     * @return {@link #boundsCache}
     */
    private float[] bounds() {
        int version = geometryVersion;
        if (boundsVersion == version) {
            return boundsCache;
        }
        Vector3f min = new Vector3f();
        Vector3f max = new Vector3f();
        Vector3f position = new Vector3f();
        int vertexCount = vertexCount();
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            vertexPosition(vertexIdAt(activeVertex), position);
            if (activeVertex == 0) {
                min.set(position);
                max.set(position);
            } else {
                min.min(position);
                max.max(position);
            }
        }
        Vector3f center = new Vector3f(min).add(max).mul(0.5f);
        float farthestSquared = 0f;
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            farthestSquared = Math.max(farthestSquared,
                    vertexPosition(vertexIdAt(activeVertex), position).distanceSquared(center));
        }
        boundsCache[0] = min.x;
        boundsCache[1] = min.y;
        boundsCache[2] = min.z;
        boundsCache[FLOATS_PER_VERTEX] = max.x;
        boundsCache[FLOATS_PER_VERTEX + 1] = max.y;
        boundsCache[FLOATS_PER_VERTEX + 2] = max.z;
        radiusCache = (float) Math.sqrt(farthestSquared);
        boundsVersion = version;
        return boundsCache;
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
        geometryVersion++;
        setVector(vertexNormals, vertexId, 0f, 0f, 0f);
        vertexOutgoingHalfEdges.clear(vertexId);
        vertexEdges.clear(vertexId);
        vertexFaces.clear(vertexId);
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
        geometryVersion++;
        faceHalfEdge[faceId] = NONE;
        setVector(faceNormals, faceId, 0f, 0f, 0f);
        faceHalfEdges.clear(faceId);
        faceVertices.clear(faceId);
        faceEdges.clear(faceId);
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
        activeFaceIds.remove(faceId);
        geometryVersion++;
        faceHalfEdge[faceId] = NONE;
        setVector(faceNormals, faceId, 0f, 0f, 0f);
        faceHalfEdges.clear(faceId);
        faceVertices.clear(faceId);
        faceEdges.clear(faceId);
    }

    /**
     * Marks an edge slot inactive and detaches its half-edge reference.
     *
     * @param edgeId edge slot to deactivate
     */
    public void deactivateEdge(int edgeId) {
        edgeActive[edgeId] = false;
        activeEdgeIds.remove(edgeId);
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
        activeHalfEdgeIds.remove(halfEdgeId);
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
        activeVertexIds.remove(vertexId);
        vertexOutgoing[vertexId] = NONE;
        setVector(vertexPositions, vertexId, 0f, 0f, 0f);
        geometryVersion++;
        setVector(vertexNormals, vertexId, 0f, 0f, 0f);
        vertexOutgoingHalfEdges.clear(vertexId);
        vertexEdges.clear(vertexId);
        vertexFaces.clear(vertexId);
    }

    /**
     * Index of the first float for this vertex within the packed position or
     * {@link #vertexNormals} arrays.
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
        for (int layer = 0; layer < vertexLayers.length; layer++) {
            vertexLayers[layer] = growLayer(vertexLayers[layer], nextCapacity, vertexLayerFill[layer]);
        }
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
        for (int layer = 0; layer < edgeLayers.length; layer++) {
            edgeLayers[layer] = growLayer(edgeLayers[layer], nextCapacity, edgeLayerFill[layer]);
        }
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
        for (int layer = 0; layer < faceLayers.length; layer++) {
            faceLayers[layer] = growLayer(faceLayers[layer], nextCapacity, faceLayerFill[layer]);
        }
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
     * Returns a layer array of the requested length, {@code source} copied into the prefix and
     * the rest set to the fill.
     *
     * @param source       layer to copy from
     * @param nextCapacity length of the returned array
     * @param fill         value of every slot past {@code source}
     * @return new int array sized to {@code nextCapacity}
     */
    public static int[] growLayer(int[] source, int nextCapacity, int fill) {
        int[] grown = Arrays.copyOf(source, nextCapacity);
        Arrays.fill(grown, source.length, nextCapacity, fill);
        return grown;
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
        return Math.max(requiredCapacity, Math.max(MIN_CAPACITY, currentCapacity * 2));
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
     * Interior angle of face {@code fId} at vertex {@code vId}.
     *
     * @param fId  face id
     * @param vId  vertex id (must be a corner of {@code fId})
     * @param vPos position of {@code vId}
     * @param a    scratch vector overwritten with the previous-corner edge
     *             direction
     * @param b    scratch vector overwritten with the next-corner edge direction
     * @return angle in radians, or 0 for degenerate triangles or when {@code vId}
     *         is not a corner of {@code fId}
     */
    public float interiorAngleAtVertex(int fId, int vId, Vector3f vPos, Vector3f a, Vector3f b) {
        int adj = faceHalfEdgeCount(fId);
        for (int i = 0; i < adj; i++) {
            int he = faceHalfEdgeAt(fId, i);
            if (halfEdgeVertex(he) == vId) {
                int prev = halfEdgePrev(he);
                int prevStart = halfEdgeVertex(prev);
                int nextEnd = halfEdgeEndVertex(he);
                vertexPosition(prevStart, a);
                vertexPosition(nextEnd, b);
                a.sub(vPos);
                b.sub(vPos);
                float la = a.length();
                float lb = b.length();
                float c = a.dot(b) / (la * lb);
                c = Math.max(-1f, Math.min(1f, c));
                return (float) Math.acos(c);
            }
        }
        return 0f;
    }

    /**
     * Project a 3D direction into a face's local (x, y) frame and return atan2(y,
     * x).
     *
     * @param dirWorld  3D direction in world coordinates
     * @param faceIndex face active index
     * @param faceY     face-local y basis vector
     * @param faceX     face-local x basis vector
     * @return signed angle of the projected direction in face-local frame, in
     *         radians
     */
    public float projectDirectionToFaceAngle(Vector3f dirWorld, int faceIndex, Vector3f faceY, Vector3f faceX) {
        Vector3f n = new Vector3f();
        faceNormal(faceIdAt(faceIndex), n);
        float dotN = dirWorld.dot(n);
        Vector3f planar = new Vector3f(
                dirWorld.x - dotN * n.x,
                dirWorld.y - dotN * n.y,
                dirWorld.z - dotN * n.z);
        return (float) Math.atan2(planar.dot(faceY), planar.dot(faceX));
    }

    /**
     * Edge-incidence struct for a given active edge index.
     *
     * @param activeIndex active edge index
     * @return both half-edges, both incident faces, and both endpoint vertices
     */
    public EdgeFaceIds edgeFaceIds(int activeIndex) {
        int eId = edgeIdAt(activeIndex);
        int he = edgeHalfEdge(eId);
        int edgeStartVertex = halfEdgeVertex(he);
        int edgeEndVertex = halfEdgeEndVertex(he);
        int twin = halfEdgeTwin(he);
        int faceA = halfEdgeFace(he);
        int faceB = halfEdgeFace(twin);
        return new EdgeFaceIds(eId, edgeStartVertex, edgeEndVertex, he, twin, faceA, faceB);
    }

    /**
     * Vertex-edge-incidence struct for one outgoing edge of a vertex.
     *
     * @param vertexId    vertex id
     * @param activeIndex index into the vertex's outgoing-edge fan
     * @return both half-edges, both incident faces, and both endpoint vertices
     */
    public VertexFaceIds vertexFaceIds(int vertexId, int activeIndex) {
        int edgeId = vertexEdgeAt(vertexId, activeIndex);
        int halfEdge = edgeHalfEdge(edgeId);
        int edgeStartVertex = halfEdgeVertex(halfEdge);
        int edgeEndVertex = halfEdgeEndVertex(halfEdge);
        int twinHalfEdge = halfEdgeTwin(halfEdge);
        int leftFaceId = halfEdgeFace(halfEdge);
        int rightFaceId = halfEdgeFace(twinHalfEdge);
        return new VertexFaceIds(vertexId, edgeId, halfEdge, edgeStartVertex, edgeEndVertex, twinHalfEdge, leftFaceId,
                rightFaceId);
    }

    /** Bundled adjacency for one edge: both half-edges, both faces, both endpoints. */
    public static final class EdgeFaceIds {
        public final int edgeId;
        public final int edgeStartVertex;
        public final int edgeEndVertex;
        public final int halfEdge;
        public final int twin;
        public final int faceA;
        public final int faceB;

        /**
         * Captures one edge's full incidence in one struct.
         *
         * @param edgeId           edge id
         * @param edgeStartVertex  start vertex id of {@code halfEdge}
         * @param edgeEndVertex    end vertex id of {@code halfEdge}
         * @param halfEdge         canonical half-edge id
         * @param twin             twin half-edge id
         * @param faceA            face incident to {@code halfEdge}
         * @param faceB            face incident to {@code twin}, or {@code -1} at
         *                         boundary
         */
        public EdgeFaceIds(int edgeId, int edgeStartVertex, int edgeEndVertex, int halfEdge, int twin, int faceA,
                int faceB) {
            this.edgeId = edgeId;
            this.edgeStartVertex = edgeStartVertex;
            this.edgeEndVertex = edgeEndVertex;
            this.halfEdge = halfEdge;
            this.twin = twin;
            this.faceA = faceA;
            this.faceB = faceB;
        }
    }

    /** Bundled adjacency for one outgoing edge of a specific vertex. */
    public static final class VertexFaceIds {
        public final int vertexId;
        public final int edgeId;
        public final int halfEdge;
        public final int edgeStartVertex;
        public final int edgeEndVertex;
        public final int twin;
        public final int faceA;
        public final int faceB;

        /**
         * Captures one vertex-edge fan entry's full incidence in one struct.
         *
         * @param vertexId         vertex id at the fan center
         * @param edgeId           edge id
         * @param halfEdge         canonical half-edge id
         * @param edgeStartVertex  start vertex id of {@code halfEdge}
         * @param edgeEndVertex    end vertex id of {@code halfEdge}
         * @param twin             twin half-edge id
         * @param faceA            face incident to {@code halfEdge}
         * @param faceB            face incident to {@code twin}, or {@code -1} at
         *                         boundary
         */
        public VertexFaceIds(int vertexId, int edgeId, int halfEdge, int edgeStartVertex, int edgeEndVertex, int twin,
                int faceA, int faceB) {
            this.vertexId = vertexId;
            this.edgeId = edgeId;
            this.halfEdge = halfEdge;
            this.edgeStartVertex = edgeStartVertex;
            this.edgeEndVertex = edgeEndVertex;
            this.twin = twin;
            this.faceA = faceA;
            this.faceB = faceB;
        }
    }

}
