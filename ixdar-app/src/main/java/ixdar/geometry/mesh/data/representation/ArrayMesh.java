package ixdar.geometry.mesh.data.representation;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.QuadMeshTopologyHelper;
import ixdar.graphics.render.model.HalfEdgeCompiledMeshData;

/**
 * Dense, uniform-face mesh backed by flat arrays. Implements
 * {@link MeshTopology} without HashMap-based construction; half-edge
 * connectivity is derived from face indices into twin/edge CSR data on the
 * first topology query and shared with every {@link #withPositions} copy.
 */
public final class ArrayMesh implements MeshTopology {
    public static final String IS_NOT_VALID = " is not valid";
    public static final int FLOATS_PER_GPU_VERTEX = 8;
    public static final float NORMAL_LENGTH_EPSILON = 1e-20f;

    private static final int FLOATS_PER_VERTEX = 3;

    private final float[] positions;
    private final float[] normals;
    private final float[] faceNormals;
    private final int[] faceIndices;
    private final int vertsPerFace;

    private final AtomicReference<Topology> topology;

    private float radiusCached = Float.NaN;
    private final Vector3f boundsMin = new Vector3f();
    private final Vector3f boundsMax = new Vector3f();
    private final Vector3f centerVec = new Vector3f();
    private boolean boundsDirty = true;

    /**
     * Wraps caller-owned arrays without copying. {@code normals} may be null and is
     * then allocated as zeros.
     *
     * @param positions    packed xyz triples; length must be divisible by 3
     * @param normals      packed xyz triples matching {@code positions}, or
     *                     {@code null} to allocate zeros
     * @param faceIndices  vertex indices grouped by {@code vertsPerFace}
     * @param vertsPerFace fixed face arity (e.g. 3 for triangles, 4 for quads)
     * @throws IllegalArgumentException if {@code positions} is not in xyz triples
     *                                  or {@code faceIndices.length} is not a
     *                                  multiple of {@code vertsPerFace}
     */
    public ArrayMesh(float[] positions, float[] normals, int[] faceIndices, int vertsPerFace) {
        this(positions, normals, faceIndices, vertsPerFace, new AtomicReference<>());
    }

    private ArrayMesh(float[] positions, float[] normals, int[] faceIndices, int vertsPerFace,
            AtomicReference<Topology> topology) {
        if (positions.length % FLOATS_PER_VERTEX != 0) {
            throw new IllegalArgumentException("positions must be XYZ triples");
        }
        if (faceIndices.length % vertsPerFace != 0) {
            throw new IllegalArgumentException("faceIndices must group by vertsPerFace");
        }
        this.positions = positions;
        this.normals = normals != null ? normals : new float[positions.length];
        this.faceNormals = new float[(faceIndices.length / vertsPerFace) * FLOATS_PER_VERTEX];
        this.faceIndices = faceIndices;
        this.vertsPerFace = vertsPerFace;
        this.topology = topology;
    }

    /**
     * A mesh over this mesh's face buffer with new positions. It shares this mesh's topology holder,
     * built or not, so the first edge query on either mesh builds it once for both.
     *
     * @param newPositions packed xyz triples, one per vertex of this mesh
     * @param newNormals   matching xyz normals, or {@code null} to allocate zeros
     * @return mesh with the same faces, vertex ids, edge ids and half-edge ids
     * @throws IllegalArgumentException if {@code newPositions} has a different
     *                                  vertex count
     */
    public ArrayMesh withPositions(float[] newPositions, float[] newNormals) {
        if (newPositions.length != positions.length) {
            throw new IllegalArgumentException("withPositions needs " + vertexCount() + " vertices, got "
                    + newPositions.length / FLOATS_PER_VERTEX);
        }
        return new ArrayMesh(newPositions, newNormals, faceIndices, vertsPerFace, topology);
    }

    /**
     * Builds a quad-only mesh from packed xyz positions and a flat quad index
     * buffer.
     *
     * @param positions   packed xyz triples
     * @param quadIndices vertex indices in groups of four
     * @return mesh with {@code vertsPerFace = 4} and zeroed normals
     */
    public static ArrayMesh fromQuads(float[] positions, int[] quadIndices) {
        return new ArrayMesh(positions, null, quadIndices, 4);
    }

    /**
     * One linear quad-subdivision step on dense arrays (see
     * {@link ArrayMeshEngine#subdivideQuadsOnce}).
     *
     * @param positions   packed xyz triples
     * @param quadIndices flat quad index buffer
     * @return subdivided {@link ArrayMesh}
     */
    public static ArrayMesh subdivideQuads(float[] positions, int[] quadIndices) {
        return ArrayMeshEngine.subdivideQuadsOnce(fromQuads(positions, quadIndices));
    }

    /**
     * Delegates to {@link ArrayMeshEngine#subdivideQuadsOnce(MeshTopology)}.
     *
     * @param src uniform-quad source mesh
     * @return subdivided {@link ArrayMesh}
     */
    public static ArrayMesh subdivideQuadsOnce(ArrayMesh src) {
        return ArrayMeshEngine.subdivideQuadsOnce(src);
    }

    /**
     * Delegates to {@link ArrayMeshEngine#deleteVertices(ArrayMesh, boolean[])}.
     *
     * @param mesh source mesh
     * @param del  per-vertex deletion mask
     * @return mesh with selected vertices and incident faces removed
     */
    public static ArrayMesh deleteVertices(ArrayMesh mesh, boolean[] del) {
        return ArrayMeshEngine.deleteVertices(mesh, del);
    }

    /**
     * Delegates to {@link ArrayMeshEngine#deleteEdges(ArrayMesh, boolean[])}.
     *
     * @param mesh    source mesh
     * @param delEdge per-edge deletion mask
     * @return mesh with selected edges (and their faces) removed
     */
    public static ArrayMesh deleteEdges(ArrayMesh mesh, boolean[] delEdge) {
        return ArrayMeshEngine.deleteEdges(mesh, delEdge);
    }

    /**
     * Delegates to {@link ArrayMeshEngine#mergeByDistance(ArrayMesh, float)}.
     *
     * @param mesh     source mesh
     * @param distance weld threshold
     * @return mesh with near-coincident vertices welded
     */
    public static ArrayMesh mergeByDistance(ArrayMesh mesh, float distance) {
        return ArrayMeshEngine.mergeByDistance(mesh, distance);
    }

    /**
     * Delegates to {@link ArrayMeshEngine#join(ArrayMesh, ArrayMesh)}.
     *
     * @param a first mesh
     * @param b second mesh
     * @return concatenation of {@code a} and {@code b} (no welding)
     */
    public static ArrayMesh join(ArrayMesh a, ArrayMesh b) {
        return ArrayMeshEngine.join(a, b);
    }

    /**
     * Fixed face arity (e.g. 4 for quad meshes).
     *
     * @return number of vertices per face
     */
    public int getVertsPerFace() {
        return vertsPerFace;
    }

    /**
     * Defensive copy of the packed xyz position array.
     *
     * @return new array independent of internal storage
     */
    public float[] copyPositions() {
        return Arrays.copyOf(positions, positions.length);
    }

    /**
     * Defensive copy of the flat face-index buffer (groups of
     * {@link #getVertsPerFace()}).
     *
     * @return new array independent of internal storage
     */
    public int[] copyFaceIndices() {
        return Arrays.copyOf(faceIndices, faceIndices.length);
    }

    /**
     * Defensive copy of the packed xyz vertex-normal array.
     *
     * @return new array independent of internal storage
     */
    public float[] copyNormals() {
        return Arrays.copyOf(normals, normals.length);
    }

    /**
     * Writes the position of a vertex in place; invalidates cached bounds and
     * radius.
     *
     * @param vertexId vertex index
     * @param x        new x coordinate
     * @param y        new y coordinate
     * @param z        new z coordinate
     */
    public void setVertexPosition(int vertexId, float x, float y, float z) {
        int o = vertexId * FLOATS_PER_VERTEX;
        positions[o] = x;
        positions[o + 1] = y;
        positions[o + 2] = z;
        boundsDirty = true;
        radiusCached = Float.NaN;
    }

    /**
     * Builds GPU-ready interleaved vertex data and a triangulated index buffer for
     * rendering. Each output vertex is 8 floats (xyz, normal xyz, two zero-padded
     * slots); faces are fan-triangulated.
     *
     * @return compiled mesh data including bounds and bounding sphere
     */
    public HalfEdgeCompiledMeshData compileSurfaceData() {
        int vCount = vertexCount();
        int fCount = faceCount();
        float[] vertices = new float[vCount * FLOATS_PER_GPU_VERTEX];
        for (int i = 0; i < vCount; i++) {
            int o = i * FLOATS_PER_VERTEX;
            int t = i * FLOATS_PER_GPU_VERTEX;
            vertices[t] = positions[o];
            vertices[t + 1] = positions[o + 1];
            vertices[t + 2] = positions[o + 2];
            vertices[t + FLOATS_PER_VERTEX] = normals[o];
            vertices[t + 4] = normals[o + 1];
            vertices[t + 5] = normals[o + 2];
            vertices[t + 6] = 0f;
            vertices[t + 7] = 0f;
        }
        int triangleCount = 0;
        for (int fi = 0; fi < fCount; fi++) {
            triangleCount += Math.max(0, faceVertexCount(fi) - 2);
        }
        int[] indices = new int[triangleCount * FLOATS_PER_VERTEX];
        int cursor = 0;
        for (int fi = 0; fi < fCount; fi++) {
            int n = faceVertexCount(fi);
            if (n < FLOATS_PER_VERTEX) {
                continue;
            }
            int anchor = faceVertexAt(fi, 0);
            for (int j = 1; j < n - 1; j++) {
                indices[cursor++] = anchor;
                indices[cursor++] = faceVertexAt(fi, j);
                indices[cursor++] = faceVertexAt(fi, j + 1);
            }
        }
        Vector3f min = boundsMin(new Vector3f());
        Vector3f max = boundsMax(new Vector3f());
        Vector3f cen = center(new Vector3f());
        float rad = radius();
        return new HalfEdgeCompiledMeshData(vertices, indices, vCount, fCount, min, max, cen, rad);
    }

    /**
     * Materializes this dense mesh as a fully connected {@link HalfEdgeMesh},
     * copying positions, faces, and vertex normals.
     *
     * @return new half-edge mesh with the same geometry
     */
    public HalfEdgeMesh toHalfEdgeMesh() {
        HalfEdgeMesh m = HalfEdgeMesh.bulkAllocate(Arrays.copyOf(positions, positions.length),
                Arrays.copyOf(faceIndices, faceIndices.length), vertsPerFace);
        // Fill face normals from geometry first; the source vertex normals then
        // overwrite the derived ones so loader-supplied shading is preserved.
        m.computeNormals();
        int n = Math.min(normals.length, m.vertexNormals.length);
        System.arraycopy(normals, 0, m.vertexNormals, 0, n);
        return m;
    }

    /**
     * Recomputes per-face and per-vertex normals in place. Face normal is the unit
     * cross product of the first two edges; vertex normals are the area-weighted
     * (via accumulated face normals) sum of incident face normals, then normalized.
     */
    public void computeNormals() {
        int v = vertexCount();
        int f = faceCount();
        Arrays.fill(normals, 0f);
        Arrays.fill(faceNormals, 0f);
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f e1 = new Vector3f();
        Vector3f e2 = new Vector3f();
        Vector3f fn = new Vector3f();
        for (int fi = 0; fi < f; fi++) {
            vertexPosition(faceVertexAt(fi, 0), p0);
            vertexPosition(faceVertexAt(fi, 1), p1);
            vertexPosition(faceVertexAt(fi, 2), p2);
            e1.set(p1).sub(p0);
            e2.set(p2).sub(p0);
            e1.cross(e2, fn);
            float len = fn.length();
            if (len > NORMAL_LENGTH_EPSILON) {
                fn.mul(1.0f / len);
            } else {
                fn.set(0f, 1f, 0f);
            }
            int fo = fi * FLOATS_PER_VERTEX;
            faceNormals[fo] = fn.x;
            faceNormals[fo + 1] = fn.y;
            faceNormals[fo + 2] = fn.z;
            int n = faceVertexCount(fi);
            for (int j = 0; j < n; j++) {
                int vid = faceVertexAt(fi, j);
                int vo = vid * FLOATS_PER_VERTEX;
                normals[vo] += fn.x;
                normals[vo + 1] += fn.y;
                normals[vo + 2] += fn.z;
            }
        }
        for (int i = 0; i < v; i++) {
            int o = i * FLOATS_PER_VERTEX;
            float nx = normals[o];
            float ny = normals[o + 1];
            float nz = normals[o + 2];
            float il = 1.0f / Math.max(NORMAL_LENGTH_EPSILON, (float) Math.sqrt(nx * nx + ny * ny + nz * nz));
            normals[o] = nx * il;
            normals[o + 1] = ny * il;
            normals[o + 2] = nz * il;
        }
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexCount() {
        return positions.length / FLOATS_PER_VERTEX;
    }

    /** {@inheritDoc}. */
    @Override
    public int edgeCount() {
        return topology().edgeCount;
    }

    /** {@inheritDoc}. */
    @Override
    public int faceCount() {
        return faceIndices.length / vertsPerFace;
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgeCount() {
        return faceIndices.length;
    }

    /** {@inheritDoc} Identity mapping: vertex ids are dense indices. */
    @Override
    public int vertexIdAt(int activeIndex) {
        return activeIndex;
    }

    /** {@inheritDoc} Identity mapping: edge ids are dense indices. */
    @Override
    public int edgeIdAt(int activeIndex) {
        return activeIndex;
    }

    /** {@inheritDoc} Identity mapping: face ids are dense indices. */
    @Override
    public int faceIdAt(int activeIndex) {
        return activeIndex;
    }

    /** {@inheritDoc} Identity mapping: half-edge ids are dense indices. */
    @Override
    public int halfEdgeIdAt(int activeIndex) {
        return activeIndex;
    }

    /** {@inheritDoc}. */
    @Override
    public boolean hasVertex(int vertexId) {
        return vertexId >= 0 && vertexId < vertexCount();
    }

    /** {@inheritDoc}. */
    @Override
    public boolean hasEdge(int edgeId) {
        return edgeId >= 0 && edgeId < topology().edgeCount;
    }

    /** {@inheritDoc}. */
    @Override
    public boolean hasFace(int faceId) {
        return faceId >= 0 && faceId < faceCount();
    }

    /** {@inheritDoc}. */
    @Override
    public boolean hasHalfEdge(int halfEdgeId) {
        return halfEdgeId >= 0 && halfEdgeId < halfEdgeCount();
    }

    /** {@inheritDoc}. */
    @Override
    public Vector3f vertexPosition(int vertexId, Vector3f dest) {
        int o = vertexId * FLOATS_PER_VERTEX;
        return dest.set(positions[o], positions[o + 1], positions[o + 2]);
    }

    /** {@inheritDoc}. */
    @Override
    public Vector3f vertexNormal(int vertexId, Vector3f dest) {
        int o = vertexId * FLOATS_PER_VERTEX;
        return dest.set(normals[o], normals[o + 1], normals[o + 2]);
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexOutgoingHalfEdge(int vertexId) {
        Topology built = topology();
        int s = built.vertexOutgoingOffsets[vertexId];
        if (s >= built.vertexOutgoingOffsets[vertexId + 1]) {
            return MeshTopology.NONE;
        }
        return built.vertexOutgoingHalfEdges[s];
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexOutgoingHalfEdgeCount(int vertexId) {
        int[] offsets = topology().vertexOutgoingOffsets;
        return offsets[vertexId + 1] - offsets[vertexId];
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexOutgoingHalfEdgeAt(int vertexId, int adjacencyIndex) {
        Topology built = topology();
        int base = built.vertexOutgoingOffsets[vertexId];
        int n = built.vertexOutgoingOffsets[vertexId + 1] - base;
        if (adjacencyIndex < 0 || adjacencyIndex >= n) {
            throw new IndexOutOfBoundsException();
        }
        return built.vertexOutgoingHalfEdges[base + adjacencyIndex];
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexEdgeCount(int vertexId) {
        int[] offsets = topology().vertexEdgeOffsets;
        return offsets[vertexId + 1] - offsets[vertexId];
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexEdgeAt(int vertexId, int adjacencyIndex) {
        Topology built = topology();
        int base = built.vertexEdgeOffsets[vertexId];
        int n = built.vertexEdgeOffsets[vertexId + 1] - base;
        if (adjacencyIndex < 0 || adjacencyIndex >= n) {
            throw new IndexOutOfBoundsException();
        }
        return built.vertexEdges[base + adjacencyIndex];
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexFaceCount(int vertexId) {
        int[] offsets = topology().vertexFaceOffsets;
        return offsets[vertexId + 1] - offsets[vertexId];
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexFaceAt(int vertexId, int adjacencyIndex) {
        Topology built = topology();
        int base = built.vertexFaceOffsets[vertexId];
        int n = built.vertexFaceOffsets[vertexId + 1] - base;
        if (adjacencyIndex < 0 || adjacencyIndex >= n) {
            throw new IndexOutOfBoundsException();
        }
        return built.vertexFaces[base + adjacencyIndex];
    }

    /** {@inheritDoc}. */
    @Override
    public boolean isBoundaryVertex(int vertexId) {
        for (int i = 0; i < vertexEdgeCount(vertexId); i++) {
            if (isBoundaryEdge(vertexEdgeAt(vertexId, i))) {
                return true;
            }
        }
        return false;
    }

    /** {@inheritDoc}. */
    @Override
    public int edgeHalfEdge(int edgeId) {
        return topology().edgeHalfEdge[edgeId];
    }

    /** {@inheritDoc}. */
    @Override
    public int edgeHalfEdgeAtActiveIndex(int activeIndex) {
        return topology().edgeHalfEdge[edgeIdAt(activeIndex)];
    }

    /** {@inheritDoc}. */
    @Override
    public boolean isBoundaryEdge(int edgeId) {
        Topology built = topology();
        return built.halfEdgeTwin[built.edgeHalfEdge[edgeId]] == MeshTopology.NONE;
    }

    /** {@inheritDoc}. */
    @Override
    public float edgeLength(int edgeId) {
        int halfEdge = topology().edgeHalfEdge[edgeId];
        int tail = halfEdgeVertex(halfEdge) * FLOATS_PER_VERTEX;
        int head = halfEdgeEndVertex(halfEdge) * FLOATS_PER_VERTEX;
        float dx = positions[head] - positions[tail];
        float dy = positions[head + 1] - positions[tail + 1];
        float dz = positions[head + 2] - positions[tail + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** {@inheritDoc}. */
    @Override
    public Vector3f edgeMidpoint(int edgeId, Vector3f dest) {
        int halfEdge = topology().edgeHalfEdge[edgeId];
        int tail = halfEdgeVertex(halfEdge) * FLOATS_PER_VERTEX;
        int head = halfEdgeEndVertex(halfEdge) * FLOATS_PER_VERTEX;
        return dest.set(positions[tail] + positions[head], positions[tail + 1] + positions[head + 1],
                positions[tail + 2] + positions[head + 2]).mul(0.5f);
    }

    /** {@inheritDoc}. */
    @Override
    public int faceHalfEdge(int faceId) {
        return faceId * vertsPerFace;
    }

    /** {@inheritDoc}. */
    @Override
    public int faceHalfEdgeCount(int faceId) {
        return vertsPerFace;
    }

    /** {@inheritDoc}. */
    @Override
    public int faceHalfEdgeAt(int faceId, int adjacencyIndex) {
        if (adjacencyIndex < 0 || adjacencyIndex >= vertsPerFace) {
            throw new IndexOutOfBoundsException();
        }
        return faceId * vertsPerFace + adjacencyIndex;
    }

    /** {@inheritDoc}. */
    @Override
    public int faceVertexCount(int faceId) {
        return vertsPerFace;
    }

    /** {@inheritDoc}. */
    @Override
    public int faceVertexAt(int faceId, int adjacencyIndex) {
        return faceIndices[faceId * vertsPerFace + adjacencyIndex];
    }

    /** {@inheritDoc}. */
    @Override
    public int faceEdgeCount(int faceId) {
        return faceVertexCount(faceId);
    }

    /** {@inheritDoc}. */
    @Override
    public int faceEdgeAt(int faceId, int adjacencyIndex) {
        return topology().halfEdgeEdge[faceHalfEdgeAt(faceId, adjacencyIndex)];
    }

    /**
     * {@inheritDoc} Returns the most recently computed face normal (see
     * {@link #computeNormals()}).
     */
    @Override
    public Vector3f faceNormal(int faceId, Vector3f dest) {
        int o = faceId * FLOATS_PER_VERTEX;
        return dest.set(faceNormals[o], faceNormals[o + 1], faceNormals[o + 2]);
    }

    /**
     * {@inheritDoc} Returns the most recently computed face normal (see
     * {@link #computeNormals()}).
     */
    @Override
    public Vector3f faceNormalAtActiveIndex(int activeIndex, Vector3f dest) {

        int o = activeIndex * FLOATS_PER_VERTEX;
        return dest.set(faceNormals[o], faceNormals[o + 1], faceNormals[o + 2]);
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgeVertex(int halfEdgeId) {
        return faceIndices[halfEdgeId];
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgeEndVertex(int halfEdgeId) {
        return halfEdgeVertex(halfEdgeNext(halfEdgeId));
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgeTwin(int halfEdgeId) {
        return topology().halfEdgeTwin[halfEdgeId];
    }

    /**
     * {@inheritDoc} Cycles within the owning face (face id =
     * {@code halfEdgeId / vertsPerFace}).
     */
    @Override
    public int halfEdgeNext(int halfEdgeId) {
        int vpf = vertsPerFace;
        int f = halfEdgeId / vpf;
        int k = halfEdgeId % vpf;
        return f * vpf + (k + 1) % vpf;
    }

    /** {@inheritDoc} Cycles within the owning face. */
    @Override
    public int halfEdgePrev(int halfEdgeId) {
        int vpf = vertsPerFace;
        int f = halfEdgeId / vpf;
        int k = halfEdgeId % vpf;
        return f * vpf + (k + vpf - 1) % vpf;
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgeFace(int halfEdgeId) {
        return halfEdgeId / vertsPerFace;
    }

    /** {@inheritDoc}. */
    @Override
    public int halfEdgeEdge(int halfEdgeId) {
        return topology().halfEdgeEdge[halfEdgeId];
    }

    /** {@inheritDoc}. */
    @Override
    public boolean isBoundaryHalfEdge(int halfEdgeId) {
        return topology().halfEdgeTwin[halfEdgeId] == MeshTopology.NONE;
    }

    /** {@inheritDoc} Bounds are recomputed lazily after vertex edits. */
    @Override
    public Vector3f boundsMin(Vector3f dest) {
        recomputeBoundsIfNeeded();
        return dest.set(boundsMin);
    }

    /** {@inheritDoc} Bounds are recomputed lazily after vertex edits. */
    @Override
    public Vector3f boundsMax(Vector3f dest) {
        recomputeBoundsIfNeeded();
        return dest.set(boundsMax);
    }

    /** {@inheritDoc} Returns the center of the axis-aligned bounding box. */
    @Override
    public Vector3f center(Vector3f dest) {
        recomputeBoundsIfNeeded();
        return dest.set(centerVec);
    }

    /**
     * {@inheritDoc} Bounding-sphere radius about {@link #center(Vector3f)}; cached
     * after first call.
     */
    @Override
    public float radius() {
        recomputeBoundsIfNeeded();
        if (!Float.isNaN(radiusCached)) {
            return radiusCached;
        }
        int v = vertexCount();
        if (v == 0) {
            radiusCached = 0f;
            return 0f;
        }
        float maxD = 0f;
        Vector3f p = new Vector3f();
        for (int i = 0; i < v; i++) {
            vertexPosition(i, p);
            float d = p.distanceSquared(centerVec);
            if (d > maxD) {
                maxD = d;
            }
        }
        radiusCached = (float) Math.sqrt(maxD);
        return radiusCached;
    }

    private void recomputeBoundsIfNeeded() {
        if (!boundsDirty) {
            return;
        }
        int v = vertexCount();
        if (v == 0) {
            boundsMin.zero();
            boundsMax.zero();
            centerVec.zero();
            boundsDirty = false;
            return;
        }
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < v; i++) {
            int o = i * FLOATS_PER_VERTEX;
            float x = positions[o];
            float y = positions[o + 1];
            float z = positions[o + 2];
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        boundsMin.set(minX, minY, minZ);
        boundsMax.set(maxX, maxY, maxZ);
        centerVec.set((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f);
        boundsDirty = false;
    }

    /**
     * The twin, edge and adjacency arrays, built on the first call and stored in the holder this mesh
     * shares with its {@link #withPositions} relatives. Racing first calls both build; one result wins.
     *
     * @return the built topology of this mesh's face buffer
     */
    private Topology topology() {
        Topology built = topology.get();
        if (built != null) {
            return built;
        }
        Topology candidate = new Topology(faceIndices, vertsPerFace, vertexCount());
        Topology winner = topology.compareAndExchange(null, candidate);
        return winner != null ? winner : candidate;
    }

    /**
     * Immutable half-edge connectivity of one face buffer: twins, edge ids and the per-vertex face,
     * edge and outgoing half-edge CSR lists.
     */
    private static final class Topology {
        public final int[] halfEdgeTwin;
        public final int[] halfEdgeEdge;
        public final int[] edgeHalfEdge;
        public final int edgeCount;
        public final int[] vertexFaceOffsets;
        public final int[] vertexFaces;
        public final int[] vertexEdgeOffsets;
        public final int[] vertexEdges;
        public final int[] vertexOutgoingOffsets;
        public final int[] vertexOutgoingHalfEdges;

        private Topology(int[] faceIndices, int vertsPerFace, int vertexCount) {
            QuadMeshTopologyHelper helper = QuadMeshTopologyHelper.build(faceIndices, vertsPerFace, vertexCount,
                    faceIndices.length / vertsPerFace);
            halfEdgeTwin = helper.halfEdgeTwin;
            halfEdgeEdge = helper.halfEdgeEdge;
            edgeHalfEdge = helper.edgeHalfEdge;
            edgeCount = helper.edgeCount;
            vertexFaceOffsets = helper.vertexFaceOffsets;
            vertexFaces = helper.vertexFaces;
            vertexEdgeOffsets = helper.vertexEdgeOffsets;
            vertexEdges = helper.vertexEdges;
            int halfEdgeCount = helper.halfEdgeCount;
            int[] outgoingCount = new int[vertexCount];
            for (int halfEdgeId = 0; halfEdgeId < halfEdgeCount; halfEdgeId++) {
                outgoingCount[faceIndices[halfEdgeId]]++;
            }
            vertexOutgoingOffsets = new int[vertexCount + 1];
            for (int vertexId = 0; vertexId < vertexCount; vertexId++) {
                vertexOutgoingOffsets[vertexId + 1] = vertexOutgoingOffsets[vertexId] + outgoingCount[vertexId];
            }
            vertexOutgoingHalfEdges = new int[vertexOutgoingOffsets[vertexCount]];
            int[] outgoingCursor = Arrays.copyOf(vertexOutgoingOffsets, vertexCount);
            for (int halfEdgeId = 0; halfEdgeId < halfEdgeCount; halfEdgeId++) {
                vertexOutgoingHalfEdges[outgoingCursor[faceIndices[halfEdgeId]]++] = halfEdgeId;
            }
        }
    }
}
