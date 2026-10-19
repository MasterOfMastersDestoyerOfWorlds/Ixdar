package ixdar.geometry.mesh.data.representation;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.joml.Vector3f;


/**
 * The indexed form of a mesh {@link HalfEdgeMesh} cannot hold: a raw scan with non-manifold or
 * degenerate edges, or repaired output with unoriented edges. Only the loaders and mesh repair
 * build one. Edge connectivity is derived from the face buffer on the first topology query.
 */
public final class ArrayMesh implements MeshTopology {
    public static final float NORMAL_LENGTH_EPSILON = 1e-20f;

    private static final int FLOATS_PER_VERTEX = 3;

    /** Packed xyz triples, vertex id {@code i} at {@code 3 * i}; ids are dense. */
    public final float[] positions;

    /** Corners of every face end to end, {@link #vertsPerFace} per face, as vertex ids. */
    public final int[] faceIndices;

    public final int vertsPerFace;

    private final float[] normals;
    private final float[] faceNormals;

    private final AtomicReference<Topology> topology = new AtomicReference<>();

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
     * Recomputes per-face and per-vertex normals in place. Face normal is the unit
     * cross product of the first two edges; vertex normals are the sum of the
     * incident unit face normals, then normalized.
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

    /** {@inheritDoc} Ids are dense, so the bound is the count. */
    @Override
    public int vertexIdBound() {
        return vertexCount();
    }

    /** {@inheritDoc} Ids are dense, so the bound is the count. */
    @Override
    public int edgeIdBound() {
        return edgeCount();
    }

    /** {@inheritDoc} Ids are dense, so the bound is the count. */
    @Override
    public int faceIdBound() {
        return faceCount();
    }

    /** {@inheritDoc} Ids are dense, so the bound is the count. */
    @Override
    public int halfEdgeIdBound() {
        return halfEdgeCount();
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

    /** {@inheritDoc} Bounds are computed lazily on first use. */
    @Override
    public Vector3f boundsMin(Vector3f dest) {
        recomputeBoundsIfNeeded();
        return dest.set(boundsMin);
    }

    /** {@inheritDoc} Bounds are computed lazily on first use. */
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
     * The twin, edge and adjacency arrays, built on the first call. Racing first calls both build;
     * one result wins.
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
     * Immutable connectivity of one uniform face buffer, which may be non-manifold: twins (a
     * half-edge pairs with the first opposite one found), edge ids in order of first appearance, and
     * the per-vertex face, edge and outgoing half-edge CSR lists.
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
            int halfEdgeCount = faceIndices.length;
            vertexOutgoingOffsets = new int[vertexCount + 1];
            for (int halfEdgeId = 0; halfEdgeId < halfEdgeCount; halfEdgeId++) {
                vertexOutgoingOffsets[faceIndices[halfEdgeId] + 1]++;
            }
            for (int vertexId = 0; vertexId < vertexCount; vertexId++) {
                vertexOutgoingOffsets[vertexId + 1] += vertexOutgoingOffsets[vertexId];
            }
            vertexOutgoingHalfEdges = new int[halfEdgeCount];
            int[] outgoingCursor = Arrays.copyOf(vertexOutgoingOffsets, vertexCount);
            for (int halfEdgeId = 0; halfEdgeId < halfEdgeCount; halfEdgeId++) {
                vertexOutgoingHalfEdges[outgoingCursor[faceIndices[halfEdgeId]]++] = halfEdgeId;
            }

            halfEdgeTwin = new int[halfEdgeCount];
            Arrays.fill(halfEdgeTwin, MeshTopology.NONE);
            for (int halfEdgeId = 0; halfEdgeId < halfEdgeCount; halfEdgeId++) {
                if (halfEdgeTwin[halfEdgeId] != MeshTopology.NONE) {
                    continue;
                }
                int start = faceIndices[halfEdgeId];
                int end = faceIndices[nextInFace(halfEdgeId, vertsPerFace)];
                for (int slot = vertexOutgoingOffsets[end]; slot < vertexOutgoingOffsets[end + 1]; slot++) {
                    int candidate = vertexOutgoingHalfEdges[slot];
                    if (faceIndices[nextInFace(candidate, vertsPerFace)] == start) {
                        halfEdgeTwin[halfEdgeId] = candidate;
                        halfEdgeTwin[candidate] = halfEdgeId;
                        break;
                    }
                }
            }

            int edges = 0;
            for (int halfEdgeId = 0; halfEdgeId < halfEdgeCount; halfEdgeId++) {
                int twin = halfEdgeTwin[halfEdgeId];
                if (twin == MeshTopology.NONE || halfEdgeId < twin) {
                    edges++;
                }
            }
            edgeCount = edges;
            halfEdgeEdge = new int[halfEdgeCount];
            edgeHalfEdge = new int[edgeCount];
            int edgeId = 0;
            for (int halfEdgeId = 0; halfEdgeId < halfEdgeCount; halfEdgeId++) {
                int twin = halfEdgeTwin[halfEdgeId];
                if (twin == MeshTopology.NONE || halfEdgeId < twin) {
                    halfEdgeEdge[halfEdgeId] = edgeId;
                    edgeHalfEdge[edgeId] = halfEdgeId;
                    if (twin != MeshTopology.NONE) {
                        halfEdgeEdge[twin] = edgeId;
                    }
                    edgeId++;
                }
            }

            vertexFaceOffsets = new int[vertexCount + 1];
            for (int corner = 0; corner < halfEdgeCount; corner++) {
                vertexFaceOffsets[faceIndices[corner] + 1]++;
            }
            for (int vertexId = 0; vertexId < vertexCount; vertexId++) {
                vertexFaceOffsets[vertexId + 1] += vertexFaceOffsets[vertexId];
            }
            vertexFaces = new int[halfEdgeCount];
            int[] faceCursor = Arrays.copyOf(vertexFaceOffsets, vertexCount);
            for (int corner = 0; corner < halfEdgeCount; corner++) {
                vertexFaces[faceCursor[faceIndices[corner]]++] = corner / vertsPerFace;
            }

            vertexEdgeOffsets = new int[vertexCount + 1];
            for (int edge = 0; edge < edgeCount; edge++) {
                int halfEdgeId = edgeHalfEdge[edge];
                vertexEdgeOffsets[faceIndices[halfEdgeId] + 1]++;
                vertexEdgeOffsets[faceIndices[nextInFace(halfEdgeId, vertsPerFace)] + 1]++;
            }
            for (int vertexId = 0; vertexId < vertexCount; vertexId++) {
                vertexEdgeOffsets[vertexId + 1] += vertexEdgeOffsets[vertexId];
            }
            vertexEdges = new int[vertexEdgeOffsets[vertexCount]];
            int[] edgeCursor = Arrays.copyOf(vertexEdgeOffsets, vertexCount);
            for (int edge = 0; edge < edgeCount; edge++) {
                int halfEdgeId = edgeHalfEdge[edge];
                vertexEdges[edgeCursor[faceIndices[halfEdgeId]]++] = edge;
                vertexEdges[edgeCursor[faceIndices[nextInFace(halfEdgeId, vertsPerFace)]]++] = edge;
            }
        }

        /**
         * The next corner around the face that owns {@code corner}.
         *
         * @param corner       index into the face buffer
         * @param vertsPerFace corners per face
         * @return the successor corner within the same face
         */
        private static int nextInFace(int corner, int vertsPerFace) {
            int base = corner - corner % vertsPerFace;
            return base + (corner - base + 1) % vertsPerFace;
        }
    }
}
