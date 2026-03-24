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

    public static ArrayMesh fromQuads(float[] positions, int[] quadIndices) {
        return new ArrayMesh(positions, null, quadIndices, 4);
    }

    /**
     * One linear quad-subdivision step on dense arrays (see
     * {@link ArrayMeshEngine#subdivideQuadsOnce}).
     */
    public static ArrayMesh subdivideQuads(float[] positions, int[] quadIndices) {
        return ArrayMeshEngine.subdivideQuadsOnce(fromQuads(positions, quadIndices));
    }

    public static ArrayMesh subdivideQuadsOnce(ArrayMesh src) {
        return ArrayMeshEngine.subdivideQuadsOnce(src);
    }

    public static ArrayMesh deleteVertices(ArrayMesh mesh, boolean[] del) {
        return ArrayMeshEngine.deleteVertices(mesh, del);
    }

    public static ArrayMesh deleteEdges(ArrayMesh mesh, boolean[] delEdge) {
        return ArrayMeshEngine.deleteEdges(mesh, delEdge);
    }

    public static ArrayMesh mergeByDistance(ArrayMesh mesh, float distance) {
        return ArrayMeshEngine.mergeByDistance(mesh, distance);
    }

    public static ArrayMesh join(ArrayMesh a, ArrayMesh b) {
        return ArrayMeshEngine.join(a, b);
    }

    public int getVertsPerFace() {
        return vertsPerFace;
    }

    public float[] copyPositions() {
        return Arrays.copyOf(positions, positions.length);
    }

    public int[] copyFaceIndices() {
        return Arrays.copyOf(faceIndices, faceIndices.length);
    }

    public float[] copyNormals() {
        return Arrays.copyOf(normals, normals.length);
    }

    public void setVertexPosition(int vertexId, float x, float y, float z) {
        requireVertex(vertexId);
        int o = vertexId * FLOATS_PER_VERTEX;
        positions[o] = x;
        positions[o + 1] = y;
        positions[o + 2] = z;
        boundsDirty = true;
        radiusCached = Float.NaN;
    }

    public HalfEdgeCompiledMeshData compileSurfaceData() {
        int vCount = vertexCount();
        int fCount = faceCount();
        float[] vertices = new float[vCount * 8];
        for (int i = 0; i < vCount; i++) {
            int o = i * FLOATS_PER_VERTEX;
            int t = i * 8;
            vertices[t] = positions[o];
            vertices[t + 1] = positions[o + 1];
            vertices[t + 2] = positions[o + 2];
            vertices[t + 3] = normals[o];
            vertices[t + 4] = normals[o + 1];
            vertices[t + 5] = normals[o + 2];
            vertices[t + 6] = 0f;
            vertices[t + 7] = 0f;
        }
        int triangleCount = 0;
        for (int fi = 0; fi < fCount; fi++) {
            triangleCount += Math.max(0, faceVertexCount(fi) - 2);
        }
        int[] indices = new int[triangleCount * 3];
        int cursor = 0;
        for (int fi = 0; fi < fCount; fi++) {
            int n = faceVertexCount(fi);
            if (n < 3) {
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
            int v1 = halfEdgeVertex(halfEdgeTwin(he));
            out[ei * 2] = v0;
            out[ei * 2 + 1] = v1;
        }
        cachedEdgeIndices = out;
        return out;
    }

    public HalfEdgeMesh toHalfEdgeMesh() {
        HalfEdgeMesh m = HalfEdgeMesh.bulkAllocate(Arrays.copyOf(positions, positions.length),
                Arrays.copyOf(faceIndices, faceIndices.length), vertsPerFace);
        int n = Math.min(normals.length, m.vertexNormals.length);
        System.arraycopy(normals, 0, m.vertexNormals, 0, n);
        return m;
    }

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
            if (len > 1e-20f) {
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
            float il = 1.0f / Math.max(1e-20f, (float) Math.sqrt(nx * nx + ny * ny + nz * nz));
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

    @Override
    public int vertexCount() {
        return positions.length / FLOATS_PER_VERTEX;
    }

    @Override
    public int edgeCount() {
        ensureTopology();
        return edgeCount;
    }

    @Override
    public int faceCount() {
        return faceIndices.length / vertsPerFace;
    }

    @Override
    public int halfEdgeCount() {
        return faceIndices.length;
    }

    @Override
    public int vertexIdAt(int activeIndex) {
        return activeIndex;
    }

    @Override
    public int edgeIdAt(int activeIndex) {
        return activeIndex;
    }

    @Override
    public int faceIdAt(int activeIndex) {
        return activeIndex;
    }

    @Override
    public int halfEdgeIdAt(int activeIndex) {
        return activeIndex;
    }

    @Override
    public boolean hasVertex(int vertexId) {
        return vertexId >= 0 && vertexId < vertexCount();
    }

    @Override
    public boolean hasEdge(int edgeId) {
        ensureTopology();
        return edgeId >= 0 && edgeId < edgeCount;
    }

    @Override
    public boolean hasFace(int faceId) {
        return faceId >= 0 && faceId < faceCount();
    }

    @Override
    public boolean hasHalfEdge(int halfEdgeId) {
        return halfEdgeId >= 0 && halfEdgeId < halfEdgeCount();
    }

    @Override
    public Vector3f vertexPosition(int vertexId, Vector3f dest) {
        requireVertex(vertexId);
        int o = vertexId * FLOATS_PER_VERTEX;
        return dest.set(positions[o], positions[o + 1], positions[o + 2]);
    }

    @Override
    public Vector3f vertexNormal(int vertexId, Vector3f dest) {
        requireVertex(vertexId);
        int o = vertexId * FLOATS_PER_VERTEX;
        return dest.set(normals[o], normals[o + 1], normals[o + 2]);
    }

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

    @Override
    public int vertexOutgoingHalfEdgeCount(int vertexId) {
        ensureTopology();
        requireVertex(vertexId);
        return vertexOutgoingOffsets[vertexId + 1] - vertexOutgoingOffsets[vertexId];
    }

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

    @Override
    public int vertexEdgeCount(int vertexId) {
        ensureTopology();
        requireVertex(vertexId);
        return vertexEdgeOffsets[vertexId + 1] - vertexEdgeOffsets[vertexId];
    }

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

    @Override
    public int vertexFaceCount(int vertexId) {
        ensureTopology();
        requireVertex(vertexId);
        return vertexFaceOffsets[vertexId + 1] - vertexFaceOffsets[vertexId];
    }

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

    @Override
    public int edgeHalfEdge(int edgeId) {
        ensureTopology();
        requireEdge(edgeId);
        return edgeHalfEdge[edgeId];
    }

    @Override
    public boolean isBoundaryEdge(int edgeId) {
        ensureTopology();
        requireEdge(edgeId);
        int he = edgeHalfEdge[edgeId];
        return halfEdgeTwin[he] == MeshTopology.NONE;
    }

    @Override
    public int faceHalfEdge(int faceId) {
        requireFace(faceId);
        return faceId * vertsPerFace;
    }

    @Override
    public int faceHalfEdgeCount(int faceId) {
        requireFace(faceId);
        return vertsPerFace;
    }

    @Override
    public int faceHalfEdgeAt(int faceId, int adjacencyIndex) {
        requireFace(faceId);
        if (adjacencyIndex < 0 || adjacencyIndex >= vertsPerFace) {
            throw new IndexOutOfBoundsException();
        }
        return faceId * vertsPerFace + adjacencyIndex;
    }

    @Override
    public int faceVertexCount(int faceId) {
        requireFace(faceId);
        return vertsPerFace;
    }

    @Override
    public int faceVertexAt(int faceId, int adjacencyIndex) {
        requireFace(faceId);
        return faceIndices[faceId * vertsPerFace + adjacencyIndex];
    }

    @Override
    public int faceEdgeCount(int faceId) {
        return faceVertexCount(faceId);
    }

    @Override
    public int faceEdgeAt(int faceId, int adjacencyIndex) {
        ensureTopology();
        int he = faceHalfEdgeAt(faceId, adjacencyIndex);
        return halfEdgeEdge[he];
    }

    @Override
    public Vector3f faceNormal(int faceId, Vector3f dest) {
        requireFace(faceId);
        int o = faceId * FLOATS_PER_VERTEX;
        return dest.set(faceNormals[o], faceNormals[o + 1], faceNormals[o + 2]);
    }

    @Override
    public int halfEdgeVertex(int halfEdgeId) {
        requireHalfEdge(halfEdgeId);
        return faceIndices[halfEdgeId];
    }

    @Override
    public int halfEdgeEndVertex(int halfEdgeId) {
        requireHalfEdge(halfEdgeId);
        return halfEdgeVertex(halfEdgeNext(halfEdgeId));
    }

    @Override
    public int halfEdgeTwin(int halfEdgeId) {
        ensureTopology();
        requireHalfEdge(halfEdgeId);
        return halfEdgeTwin[halfEdgeId];
    }

    @Override
    public int halfEdgeNext(int halfEdgeId) {
        requireHalfEdge(halfEdgeId);
        int vpf = vertsPerFace;
        int f = halfEdgeId / vpf;
        int k = halfEdgeId % vpf;
        return f * vpf + (k + 1) % vpf;
    }

    @Override
    public int halfEdgePrev(int halfEdgeId) {
        requireHalfEdge(halfEdgeId);
        int vpf = vertsPerFace;
        int f = halfEdgeId / vpf;
        int k = halfEdgeId % vpf;
        return f * vpf + (k + vpf - 1) % vpf;
    }

    @Override
    public int halfEdgeFace(int halfEdgeId) {
        requireHalfEdge(halfEdgeId);
        return halfEdgeId / vertsPerFace;
    }

    @Override
    public int halfEdgeEdge(int halfEdgeId) {
        ensureTopology();
        requireHalfEdge(halfEdgeId);
        return halfEdgeEdge[halfEdgeId];
    }

    @Override
    public boolean isBoundaryHalfEdge(int halfEdgeId) {
        ensureTopology();
        requireHalfEdge(halfEdgeId);
        return halfEdgeTwin[halfEdgeId] == MeshTopology.NONE;
    }

    @Override
    public Vector3f boundsMin(Vector3f dest) {
        recomputeBoundsIfNeeded();
        return dest.set(boundsMin);
    }

    @Override
    public Vector3f boundsMax(Vector3f dest) {
        recomputeBoundsIfNeeded();
        return dest.set(boundsMax);
    }

    @Override
    public Vector3f center(Vector3f dest) {
        recomputeBoundsIfNeeded();
        return dest.set(centerVec);
    }

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

    private void requireVertex(int vertexId) {
        if (!hasVertex(vertexId)) {
            throw new InvalidMeshTopologyException("Vertex " + vertexId + " is not valid");
        }
    }

    private void requireFace(int faceId) {
        if (!hasFace(faceId)) {
            throw new InvalidMeshTopologyException("Face " + faceId + " is not valid");
        }
    }

    private void requireHalfEdge(int halfEdgeId) {
        if (!hasHalfEdge(halfEdgeId)) {
            throw new InvalidMeshTopologyException("Half-edge " + halfEdgeId + " is not valid");
        }
    }

    private void requireEdge(int edgeId) {
        if (!hasEdge(edgeId)) {
            throw new InvalidMeshTopologyException("Edge " + edgeId + " is not valid");
        }
    }
}
