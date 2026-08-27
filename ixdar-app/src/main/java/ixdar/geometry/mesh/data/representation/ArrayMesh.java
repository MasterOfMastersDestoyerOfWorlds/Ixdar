package ixdar.geometry.mesh.data.representation;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.nodes.api.MeshValue;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.graphics.render.model.HalfEdgeCompiledMeshData;

/**
 * Dense, uniform-face mesh backed by flat arrays. Implements
 * {@link MeshTopology} without HashMap-based construction; half-edge
 * connectivity is derived from face indices with lazy twin/edge CSR data.
 */
public final class ArrayMesh implements MeshTopology, MeshValue {
    public static final String IS_NOT_VALID = " is not valid";
    public static final int NUM_4 = 4;
    public static final int NUM_8 = 8;
    public static final int NUM_5 = 5;
    public static final int NUM_6 = 6;
    public static final float NUM_0 = 0f;
    public static final int NUM_7 = 7;
    public static final float NUM_1e_20 = 1e-20f;
    public static final float NUM_1 = 1f;
    public static final float NUM_0_5 = 0.5f;

    private static final int FLOATS_PER_VERTEX = 3;

    private final float[] positions;
    private final float[] normals;
    private final float[] faceNormals;
    private final int[] faceIndices;
    private final int vertsPerFace;

    private boolean topologyReady;
    private int[] halfEdgeTwin;
    private int[] halfEdgeEdge;
    private int[] edgeHalfEdge;
    private int edgeCount;

    private int[] vertexFaceOffsets;
    private int[] vertexFaces;
    private int[] vertexEdgeOffsets;
    private int[] vertexEdges;
    private int[] vertexOutgoingOffsets;
    private int[] vertexOutgoingHalfEdges;

    private int[] cachedEdgeIndices;

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
        return new ArrayMesh(positions, null, quadIndices, NUM_4);
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
        float[] vertices = new float[vCount * NUM_8];
        for (int i = 0; i < vCount; i++) {
            int o = i * FLOATS_PER_VERTEX;
            int t = i * NUM_8;
            vertices[t] = positions[o];
            vertices[t + 1] = positions[o + 1];
            vertices[t + 2] = positions[o + 2];
            vertices[t + FLOATS_PER_VERTEX] = normals[o];
            vertices[t + NUM_4] = normals[o + 1];
            vertices[t + NUM_5] = normals[o + 2];
            vertices[t + NUM_6] = NUM_0;
            vertices[t + NUM_7] = NUM_0;
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
     * Lazy, cached flat (v0,v1) edge index buffer with one pair per unique edge.
     *
     * @return array of length {@code 2 * edgeCount()} holding endpoint vertex ids
     */
    public int[] getEdgeIndices() {
        if (cachedEdgeIndices != null) {
            return cachedEdgeIndices;
        }

        int e = edgeCount;
        int[] out = new int[e * 2];
        for (int ei = 0; ei < e; ei++) {
            int he = edgeHalfEdge[ei];
            int v0 = halfEdgeVertex(he);
            int v1 = halfEdgeEndVertex(he);
            out[ei * 2] = v0;
            out[ei * 2 + 1] = v1;
        }
        cachedEdgeIndices = out;
        return out;
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
        Arrays.fill(normals, NUM_0);
        Arrays.fill(faceNormals, NUM_0);
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
            if (len > NUM_1e_20) {
                fn.mul(1.0f / len);
            } else {
                fn.set(NUM_0, NUM_1, NUM_0);
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
            float il = 1.0f / Math.max(NUM_1e_20, (float) Math.sqrt(nx * nx + ny * ny + nz * nz));
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
        return edgeCount;
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

        return edgeId >= 0 && edgeId < edgeCount;
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

        int s = vertexOutgoingOffsets[vertexId];
        if (s >= vertexOutgoingOffsets[vertexId + 1]) {
            return MeshTopology.NONE;
        }
        return vertexOutgoingHalfEdges[s];
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexOutgoingHalfEdgeCount(int vertexId) {

        return vertexOutgoingOffsets[vertexId + 1] - vertexOutgoingOffsets[vertexId];
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexOutgoingHalfEdgeAt(int vertexId, int adjacencyIndex) {

        int base = vertexOutgoingOffsets[vertexId];
        int n = vertexOutgoingOffsets[vertexId + 1] - base;
        if (adjacencyIndex < 0 || adjacencyIndex >= n) {
            throw new IndexOutOfBoundsException();
        }
        return vertexOutgoingHalfEdges[base + adjacencyIndex];
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexEdgeCount(int vertexId) {

        return vertexEdgeOffsets[vertexId + 1] - vertexEdgeOffsets[vertexId];
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexEdgeAt(int vertexId, int adjacencyIndex) {

        int base = vertexEdgeOffsets[vertexId];
        int n = vertexEdgeOffsets[vertexId + 1] - base;
        if (adjacencyIndex < 0 || adjacencyIndex >= n) {
            throw new IndexOutOfBoundsException();
        }
        return vertexEdges[base + adjacencyIndex];
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexFaceCount(int vertexId) {

        return vertexFaceOffsets[vertexId + 1] - vertexFaceOffsets[vertexId];
    }

    /** {@inheritDoc}. */
    @Override
    public int vertexFaceAt(int vertexId, int adjacencyIndex) {

        int base = vertexFaceOffsets[vertexId];
        int n = vertexFaceOffsets[vertexId + 1] - base;
        if (adjacencyIndex < 0 || adjacencyIndex >= n) {
            throw new IndexOutOfBoundsException();
        }
        return vertexFaces[base + adjacencyIndex];
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

        return edgeHalfEdge[edgeId];
    }

    /** {@inheritDoc}. */
    @Override
    public int edgeHalfEdgeAtActiveIndex(int activeIndex) {
        return edgeHalfEdge[edgeIdAt(activeIndex)];
    }

    /** {@inheritDoc}. */
    @Override
    public boolean isBoundaryEdge(int edgeId) {

        int he = edgeHalfEdge[edgeId];
        return halfEdgeTwin[he] == MeshTopology.NONE;
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

        int he = faceHalfEdgeAt(faceId, adjacencyIndex);
        return halfEdgeEdge[he];
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

        return halfEdgeTwin[halfEdgeId];
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

        return halfEdgeEdge[halfEdgeId];
    }

    /** {@inheritDoc}. */
    @Override
    public boolean isBoundaryHalfEdge(int halfEdgeId) {

        return halfEdgeTwin[halfEdgeId] == MeshTopology.NONE;
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
            radiusCached = NUM_0;
            return NUM_0;
        }
        float maxD = NUM_0;
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
        centerVec.set((minX + maxX) * NUM_0_5, (minY + maxY) * NUM_0_5, (minZ + maxZ) * NUM_0_5);
        boundsDirty = false;
    }
}
