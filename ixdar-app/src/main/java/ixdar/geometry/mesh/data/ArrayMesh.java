package ixdar.geometry.mesh.data;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.MeshValue;
import ixdar.common.exceptions.InvalidMeshTopologyException;
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
     * TODO: document {@code ArrayMesh}.
     *
     * @param positions TODO: describe
     * @param normals TODO: describe
     * @param faceIndices TODO: describe
     * @param vertsPerFace TODO: describe
     * @throws IllegalArgumentException TODO: describe
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
     * TODO: document {@code fromQuads}.
     *
     * @param positions TODO: describe
     * @param quadIndices TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh fromQuads(float[] positions, int[] quadIndices) {
        return new ArrayMesh(positions, null, quadIndices, NUM_4);
    }

    /**
     * One linear quad-subdivision step on dense arrays (see
     * {@link ArrayMeshEngine#subdivideQuadsOnce}).
     *
     * @param positions TODO: describe
     * @param quadIndices TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh subdivideQuads(float[] positions, int[] quadIndices) {
        return ArrayMeshEngine.subdivideQuadsOnce(fromQuads(positions, quadIndices));
    }

    /**
     * TODO: document {@code subdivideQuadsOnce}.
     *
     * @param src TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh subdivideQuadsOnce(ArrayMesh src) {
        return ArrayMeshEngine.subdivideQuadsOnce(src);
    }

    /**
     * TODO: document {@code deleteVertices}.
     *
     * @param mesh TODO: describe
     * @param del TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh deleteVertices(ArrayMesh mesh, boolean[] del) {
        return ArrayMeshEngine.deleteVertices(mesh, del);
    }

    /**
     * TODO: document {@code deleteEdges}.
     *
     * @param mesh TODO: describe
     * @param delEdge TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh deleteEdges(ArrayMesh mesh, boolean[] delEdge) {
        return ArrayMeshEngine.deleteEdges(mesh, delEdge);
    }

    /**
     * TODO: document {@code mergeByDistance}.
     *
     * @param mesh TODO: describe
     * @param distance TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh mergeByDistance(ArrayMesh mesh, float distance) {
        return ArrayMeshEngine.mergeByDistance(mesh, distance);
    }

    /**
     * TODO: document {@code join}.
     *
     * @param a TODO: describe
     * @param b TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh join(ArrayMesh a, ArrayMesh b) {
        return ArrayMeshEngine.join(a, b);
    }

    /**
     * TODO: document {@code getVertsPerFace}.
     *
     * @return TODO: describe
     */
    public int getVertsPerFace() {
        return vertsPerFace;
    }

    /**
     * TODO: document {@code copyPositions}.
     *
     * @return TODO: describe
     */
    public float[] copyPositions() {
        return Arrays.copyOf(positions, positions.length);
    }

    /**
     * TODO: document {@code copyFaceIndices}.
     *
     * @return TODO: describe
     */
    public int[] copyFaceIndices() {
        return Arrays.copyOf(faceIndices, faceIndices.length);
    }

    /**
     * TODO: document {@code copyNormals}.
     *
     * @return TODO: describe
     */
    public float[] copyNormals() {
        return Arrays.copyOf(normals, normals.length);
    }

    /**
     * TODO: document {@code setVertexPosition}.
     *
     * @param vertexId TODO: describe
     * @param x TODO: describe
     * @param y TODO: describe
     * @param z TODO: describe
     */
    public void setVertexPosition(int vertexId, float x, float y, float z) {
        requireVertex(vertexId);
        int o = vertexId * FLOATS_PER_VERTEX;
        positions[o] = x;
        positions[o + 1] = y;
        positions[o + 2] = z;
        boundsDirty = true;
        radiusCached = Float.NaN;
    }

    /**
     * TODO: document {@code compileSurfaceData}.
     *
     * @return TODO: describe
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
     * TODO: document {@code getEdgeIndices}.
     *
     * @return TODO: describe
     */
    public int[] getEdgeIndices() {
        if (cachedEdgeIndices != null) {
            return cachedEdgeIndices;
        }
        ensureTopology();
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
     * TODO: document {@code toHalfEdgeMesh}.
     *
     * @return TODO: describe
     */
    public HalfEdgeMesh toHalfEdgeMesh() {
        HalfEdgeMesh m = HalfEdgeMesh.bulkAllocate(Arrays.copyOf(positions, positions.length),
                Arrays.copyOf(faceIndices, faceIndices.length), vertsPerFace);
        int n = Math.min(normals.length, m.vertexNormals.length);
        System.arraycopy(normals, 0, m.vertexNormals, 0, n);
        return m;
    }

    /**
     * TODO: document {@code computeNormals}.
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

    private void ensureTopology() {
        if (topologyReady) {
            return;
        }
        int fc = faceCount();
        int V = vertexCount();
        QuadMeshTopologyHelper topo = QuadMeshTopologyHelper.build(faceIndices, vertsPerFace, V, fc);
        halfEdgeTwin = topo.halfEdgeTwin;
        halfEdgeEdge = topo.halfEdgeEdge;
        edgeHalfEdge = topo.edgeHalfEdge;
        edgeCount = topo.edgeCount;
        vertexFaceOffsets = topo.vertexFaceOffsets;
        vertexFaces = topo.vertexFaces;
        vertexEdgeOffsets = topo.vertexEdgeOffsets;
        vertexEdges = topo.vertexEdges;

        int HE = topo.halfEdgeCount;
        if (HE == 0) {
            vertexOutgoingOffsets = new int[V + 1];
            vertexOutgoingHalfEdges = new int[0];
            topologyReady = true;
            return;
        }

        int[] vohCount = new int[V];
        for (int he = 0; he < HE; he++) {
            vohCount[faceIndices[he]]++;
        }
        vertexOutgoingOffsets = new int[V + 1];
        vertexOutgoingOffsets[0] = 0;
        for (int i = 0; i < V; i++) {
            vertexOutgoingOffsets[i + 1] = vertexOutgoingOffsets[i] + vohCount[i];
        }
        vertexOutgoingHalfEdges = new int[vertexOutgoingOffsets[V]];
        int[] vohWrite = Arrays.copyOf(vertexOutgoingOffsets, V + 1);
        for (int he = 0; he < HE; he++) {
            int v = faceIndices[he];
            vertexOutgoingHalfEdges[vohWrite[v]++] = he;
        }

        topologyReady = true;
    }

    /**
     * TODO: document {@code vertexCount}.
     *
     * @return TODO: describe
     */
    @Override
    public int vertexCount() {
        return positions.length / FLOATS_PER_VERTEX;
    }

    /**
     * TODO: document {@code edgeCount}.
     *
     * @return TODO: describe
     */
    @Override
    public int edgeCount() {
        ensureTopology();
        return edgeCount;
    }

    /**
     * TODO: document {@code faceCount}.
     *
     * @return TODO: describe
     */
    @Override
    public int faceCount() {
        return faceIndices.length / vertsPerFace;
    }

    /**
     * TODO: document {@code halfEdgeCount}.
     *
     * @return TODO: describe
     */
    @Override
    public int halfEdgeCount() {
        return faceIndices.length;
    }

    /**
     * TODO: document {@code vertexIdAt}.
     *
     * @param activeIndex TODO: describe
     * @return TODO: describe
     */
    @Override
    public int vertexIdAt(int activeIndex) {
        return activeIndex;
    }

    /**
     * TODO: document {@code edgeIdAt}.
     *
     * @param activeIndex TODO: describe
     * @return TODO: describe
     */
    @Override
    public int edgeIdAt(int activeIndex) {
        return activeIndex;
    }

    /**
     * TODO: document {@code faceIdAt}.
     *
     * @param activeIndex TODO: describe
     * @return TODO: describe
     */
    @Override
    public int faceIdAt(int activeIndex) {
        return activeIndex;
    }

    /**
     * TODO: document {@code halfEdgeIdAt}.
     *
     * @param activeIndex TODO: describe
     * @return TODO: describe
     */
    @Override
    public int halfEdgeIdAt(int activeIndex) {
        return activeIndex;
    }

    /**
     * TODO: document {@code hasVertex}.
     *
     * @param vertexId TODO: describe
     * @return TODO: describe
     */
    @Override
    public boolean hasVertex(int vertexId) {
        return vertexId >= 0 && vertexId < vertexCount();
    }

    /**
     * TODO: document {@code hasEdge}.
     *
     * @param edgeId TODO: describe
     * @return TODO: describe
     */
    @Override
    public boolean hasEdge(int edgeId) {
        ensureTopology();
        return edgeId >= 0 && edgeId < edgeCount;
    }

    /**
     * TODO: document {@code hasFace}.
     *
     * @param faceId TODO: describe
     * @return TODO: describe
     */
    @Override
    public boolean hasFace(int faceId) {
        return faceId >= 0 && faceId < faceCount();
    }

    /**
     * TODO: document {@code hasHalfEdge}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    @Override
    public boolean hasHalfEdge(int halfEdgeId) {
        return halfEdgeId >= 0 && halfEdgeId < halfEdgeCount();
    }

    /**
     * TODO: document {@code vertexPosition}.
     *
     * @param vertexId TODO: describe
     * @param dest TODO: describe
     * @return TODO: describe
     */
    @Override
    public Vector3f vertexPosition(int vertexId, Vector3f dest) {
        requireVertex(vertexId);
        int o = vertexId * FLOATS_PER_VERTEX;
        return dest.set(positions[o], positions[o + 1], positions[o + 2]);
    }

    /**
     * TODO: document {@code vertexNormal}.
     *
     * @param vertexId TODO: describe
     * @param dest TODO: describe
     * @return TODO: describe
     */
    @Override
    public Vector3f vertexNormal(int vertexId, Vector3f dest) {
        requireVertex(vertexId);
        int o = vertexId * FLOATS_PER_VERTEX;
        return dest.set(normals[o], normals[o + 1], normals[o + 2]);
    }

    /**
     * TODO: document {@code vertexOutgoingHalfEdge}.
     *
     * @param vertexId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int vertexOutgoingHalfEdge(int vertexId) {
        ensureTopology();
        requireVertex(vertexId);
        int s = vertexOutgoingOffsets[vertexId];
        if (s >= vertexOutgoingOffsets[vertexId + 1]) {
            return MeshTopology.NONE;
        }
        return vertexOutgoingHalfEdges[s];
    }

    /**
     * TODO: document {@code vertexOutgoingHalfEdgeCount}.
     *
     * @param vertexId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int vertexOutgoingHalfEdgeCount(int vertexId) {
        ensureTopology();
        requireVertex(vertexId);
        return vertexOutgoingOffsets[vertexId + 1] - vertexOutgoingOffsets[vertexId];
    }

    /**
     * TODO: document {@code vertexOutgoingHalfEdgeAt}.
     *
     * @param vertexId TODO: describe
     * @param adjacencyIndex TODO: describe
     * @throws IndexOutOfBoundsException TODO: describe
     * @return TODO: describe
     */
    @Override
    public int vertexOutgoingHalfEdgeAt(int vertexId, int adjacencyIndex) {
        ensureTopology();
        requireVertex(vertexId);
        int base = vertexOutgoingOffsets[vertexId];
        int n = vertexOutgoingOffsets[vertexId + 1] - base;
        if (adjacencyIndex < 0 || adjacencyIndex >= n) {
            throw new IndexOutOfBoundsException();
        }
        return vertexOutgoingHalfEdges[base + adjacencyIndex];
    }

    /**
     * TODO: document {@code vertexEdgeCount}.
     *
     * @param vertexId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int vertexEdgeCount(int vertexId) {
        ensureTopology();
        requireVertex(vertexId);
        return vertexEdgeOffsets[vertexId + 1] - vertexEdgeOffsets[vertexId];
    }

    /**
     * TODO: document {@code vertexEdgeAt}.
     *
     * @param vertexId TODO: describe
     * @param adjacencyIndex TODO: describe
     * @throws IndexOutOfBoundsException TODO: describe
     * @return TODO: describe
     */
    @Override
    public int vertexEdgeAt(int vertexId, int adjacencyIndex) {
        ensureTopology();
        requireVertex(vertexId);
        int base = vertexEdgeOffsets[vertexId];
        int n = vertexEdgeOffsets[vertexId + 1] - base;
        if (adjacencyIndex < 0 || adjacencyIndex >= n) {
            throw new IndexOutOfBoundsException();
        }
        return vertexEdges[base + adjacencyIndex];
    }

    /**
     * TODO: document {@code vertexFaceCount}.
     *
     * @param vertexId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int vertexFaceCount(int vertexId) {
        ensureTopology();
        requireVertex(vertexId);
        return vertexFaceOffsets[vertexId + 1] - vertexFaceOffsets[vertexId];
    }

    /**
     * TODO: document {@code vertexFaceAt}.
     *
     * @param vertexId TODO: describe
     * @param adjacencyIndex TODO: describe
     * @throws IndexOutOfBoundsException TODO: describe
     * @return TODO: describe
     */
    @Override
    public int vertexFaceAt(int vertexId, int adjacencyIndex) {
        ensureTopology();
        requireVertex(vertexId);
        int base = vertexFaceOffsets[vertexId];
        int n = vertexFaceOffsets[vertexId + 1] - base;
        if (adjacencyIndex < 0 || adjacencyIndex >= n) {
            throw new IndexOutOfBoundsException();
        }
        return vertexFaces[base + adjacencyIndex];
    }

    /**
     * TODO: document {@code isBoundaryVertex}.
     *
     * @param vertexId TODO: describe
     * @return TODO: describe
     */
    @Override
    public boolean isBoundaryVertex(int vertexId) {
        ensureTopology();
        requireVertex(vertexId);
        for (int i = 0; i < vertexEdgeCount(vertexId); i++) {
            if (isBoundaryEdge(vertexEdgeAt(vertexId, i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * TODO: document {@code edgeHalfEdge}.
     *
     * @param edgeId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int edgeHalfEdge(int edgeId) {
        ensureTopology();
        requireEdge(edgeId);
        return edgeHalfEdge[edgeId];
    }

    /**
     * TODO: document {@code isBoundaryEdge}.
     *
     * @param edgeId TODO: describe
     * @return TODO: describe
     */
    @Override
    public boolean isBoundaryEdge(int edgeId) {
        ensureTopology();
        requireEdge(edgeId);
        int he = edgeHalfEdge[edgeId];
        return halfEdgeTwin[he] == MeshTopology.NONE;
    }

    /**
     * TODO: document {@code faceHalfEdge}.
     *
     * @param faceId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int faceHalfEdge(int faceId) {
        requireFace(faceId);
        return faceId * vertsPerFace;
    }

    /**
     * TODO: document {@code faceHalfEdgeCount}.
     *
     * @param faceId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int faceHalfEdgeCount(int faceId) {
        requireFace(faceId);
        return vertsPerFace;
    }

    /**
     * TODO: document {@code faceHalfEdgeAt}.
     *
     * @param faceId TODO: describe
     * @param adjacencyIndex TODO: describe
     * @throws IndexOutOfBoundsException TODO: describe
     * @return TODO: describe
     */
    @Override
    public int faceHalfEdgeAt(int faceId, int adjacencyIndex) {
        requireFace(faceId);
        if (adjacencyIndex < 0 || adjacencyIndex >= vertsPerFace) {
            throw new IndexOutOfBoundsException();
        }
        return faceId * vertsPerFace + adjacencyIndex;
    }

    /**
     * TODO: document {@code faceVertexCount}.
     *
     * @param faceId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int faceVertexCount(int faceId) {
        requireFace(faceId);
        return vertsPerFace;
    }

    /**
     * TODO: document {@code faceVertexAt}.
     *
     * @param faceId TODO: describe
     * @param adjacencyIndex TODO: describe
     * @return TODO: describe
     */
    @Override
    public int faceVertexAt(int faceId, int adjacencyIndex) {
        requireFace(faceId);
        return faceIndices[faceId * vertsPerFace + adjacencyIndex];
    }

    /**
     * TODO: document {@code faceEdgeCount}.
     *
     * @param faceId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int faceEdgeCount(int faceId) {
        return faceVertexCount(faceId);
    }

    /**
     * TODO: document {@code faceEdgeAt}.
     *
     * @param faceId TODO: describe
     * @param adjacencyIndex TODO: describe
     * @return TODO: describe
     */
    @Override
    public int faceEdgeAt(int faceId, int adjacencyIndex) {
        ensureTopology();
        int he = faceHalfEdgeAt(faceId, adjacencyIndex);
        return halfEdgeEdge[he];
    }

    /**
     * TODO: document {@code faceNormal}.
     *
     * @param faceId TODO: describe
     * @param dest TODO: describe
     * @return TODO: describe
     */
    @Override
    public Vector3f faceNormal(int faceId, Vector3f dest) {
        requireFace(faceId);
        int o = faceId * FLOATS_PER_VERTEX;
        return dest.set(faceNormals[o], faceNormals[o + 1], faceNormals[o + 2]);
    }

    /**
     * TODO: document {@code halfEdgeVertex}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int halfEdgeVertex(int halfEdgeId) {
        requireHalfEdge(halfEdgeId);
        return faceIndices[halfEdgeId];
    }

    /**
     * TODO: document {@code halfEdgeEndVertex}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int halfEdgeEndVertex(int halfEdgeId) {
        requireHalfEdge(halfEdgeId);
        return halfEdgeVertex(halfEdgeNext(halfEdgeId));
    }

    /**
     * TODO: document {@code halfEdgeTwin}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int halfEdgeTwin(int halfEdgeId) {
        ensureTopology();
        requireHalfEdge(halfEdgeId);
        return halfEdgeTwin[halfEdgeId];
    }

    /**
     * TODO: document {@code halfEdgeNext}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int halfEdgeNext(int halfEdgeId) {
        requireHalfEdge(halfEdgeId);
        int vpf = vertsPerFace;
        int f = halfEdgeId / vpf;
        int k = halfEdgeId % vpf;
        return f * vpf + (k + 1) % vpf;
    }

    /**
     * TODO: document {@code halfEdgePrev}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int halfEdgePrev(int halfEdgeId) {
        requireHalfEdge(halfEdgeId);
        int vpf = vertsPerFace;
        int f = halfEdgeId / vpf;
        int k = halfEdgeId % vpf;
        return f * vpf + (k + vpf - 1) % vpf;
    }

    /**
     * TODO: document {@code halfEdgeFace}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int halfEdgeFace(int halfEdgeId) {
        requireHalfEdge(halfEdgeId);
        return halfEdgeId / vertsPerFace;
    }

    /**
     * TODO: document {@code halfEdgeEdge}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    @Override
    public int halfEdgeEdge(int halfEdgeId) {
        ensureTopology();
        requireHalfEdge(halfEdgeId);
        return halfEdgeEdge[halfEdgeId];
    }

    /**
     * TODO: document {@code isBoundaryHalfEdge}.
     *
     * @param halfEdgeId TODO: describe
     * @return TODO: describe
     */
    @Override
    public boolean isBoundaryHalfEdge(int halfEdgeId) {
        ensureTopology();
        requireHalfEdge(halfEdgeId);
        return halfEdgeTwin[halfEdgeId] == MeshTopology.NONE;
    }

    /**
     * TODO: document {@code boundsMin}.
     *
     * @param dest TODO: describe
     * @return TODO: describe
     */
    @Override
    public Vector3f boundsMin(Vector3f dest) {
        recomputeBoundsIfNeeded();
        return dest.set(boundsMin);
    }

    /**
     * TODO: document {@code boundsMax}.
     *
     * @param dest TODO: describe
     * @return TODO: describe
     */
    @Override
    public Vector3f boundsMax(Vector3f dest) {
        recomputeBoundsIfNeeded();
        return dest.set(boundsMax);
    }

    /**
     * TODO: document {@code center}.
     *
     * @param dest TODO: describe
     * @return TODO: describe
     */
    @Override
    public Vector3f center(Vector3f dest) {
        recomputeBoundsIfNeeded();
        return dest.set(centerVec);
    }

    /**
     * TODO: document {@code radius}.
     *
     * @return TODO: describe
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

    private void requireVertex(int vertexId) {
        if (!hasVertex(vertexId)) {
            throw new InvalidMeshTopologyException("Vertex " + vertexId + IS_NOT_VALID);
        }
    }

    private void requireFace(int faceId) {
        if (!hasFace(faceId)) {
            throw new InvalidMeshTopologyException("Face " + faceId + IS_NOT_VALID);
        }
    }

    private void requireHalfEdge(int halfEdgeId) {
        if (!hasHalfEdge(halfEdgeId)) {
            throw new InvalidMeshTopologyException("Half-edge " + halfEdgeId + IS_NOT_VALID);
        }
    }

    private void requireEdge(int edgeId) {
        if (!hasEdge(edgeId)) {
            throw new InvalidMeshTopologyException("Edge " + edgeId + IS_NOT_VALID);
        }
    }
}
