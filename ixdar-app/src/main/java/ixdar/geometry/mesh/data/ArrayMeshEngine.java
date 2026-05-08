package ixdar.geometry.mesh.data;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;

/**
 * Topology-changing operations that produce {@link ArrayMesh} from flat arrays
 * without building a {@link HalfEdgeMesh} intermediate.
 */
public final class ArrayMeshEngine {
    public static final int NUM_4 = 4;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_25 = 0.25f;
    public static final float NUM_1 = 1f;
    public static final float NUM_1e_9 = 1e-9f;
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;

    private static final int FP = 3;

    private ArrayMeshEngine() {
    }

    /**
     * TODO: document {@code emptyQuads}.
     *
     * @return TODO: describe
     */
    public static ArrayMesh emptyQuads() {
        return new ArrayMesh(new float[0], null, new int[0], NUM_4);
    }

    /**
     * Packs a mesh with uniform face size into an {@link ArrayMesh}. Vertex
     * iteration order becomes contiguous indices {@code 0..vertexCount-1}.
     *
     * @param mesh TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh fromUniformMeshTopology(MeshTopology mesh) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return emptyQuads();
        }
        if (mesh instanceof ArrayMesh am) {
            return am;
        }
        int vpf = mesh.faceVertexCount(mesh.faceIdAt(0));
        int nf = mesh.faceCount();
        for (int fi = 0; fi < nf; fi++) {
            if (mesh.faceVertexCount(mesh.faceIdAt(fi)) != vpf) {
                throw new IllegalArgumentException("faces must have uniform vertex count");
            }
        }
        int n = mesh.vertexCount();
        HashMap<Integer, Integer> idToIndex = new HashMap<>(n * NUM_4 / FP + 1);
        for (int i = 0; i < n; i++) {
            idToIndex.put(mesh.vertexIdAt(i), i);
        }
        float[] pos = new float[n * FP];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            mesh.vertexPosition(vid, tmp);
            int o = i * FP;
            pos[o] = tmp.x;
            pos[o + 1] = tmp.y;
            pos[o + 2] = tmp.z;
        }
        int[] faceIdx = new int[nf * vpf];
        for (int fi = 0; fi < nf; fi++) {
            int fid = mesh.faceIdAt(fi);
            int base = fi * vpf;
            for (int k = 0; k < vpf; k++) {
                int ov = mesh.faceVertexAt(fid, k);
                faceIdx[base + k] = idToIndex.get(ov);
            }
        }
        return new ArrayMesh(pos, null, faceIdx, vpf);
    }

    /**
     * TODO: document {@code isUniformQuads}.
     *
     * @param mesh TODO: describe
     * @return TODO: describe
     */
    public static boolean isUniformQuads(MeshTopology mesh) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return true;
        }
        int nf = mesh.faceCount();
        if (nf == 0) {
            return true;
        }
        for (int fi = 0; fi < nf; fi++) {
            if (mesh.faceVertexCount(mesh.faceIdAt(fi)) != NUM_4) {
                return false;
            }
        }
        return true;
    }

    /**
     * One level of linear quad subdivision (edge midpoints + face centroids),
     * matching {@link ixdar.geometry.mesh.nodes.modifier.SubdivideMeshNode} for
     * uniform quad meshes.
     *
     * @param src TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @throws IllegalStateException TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh subdivideQuadsOnce(MeshTopology src) {
        if (src == null || src.vertexCount() == 0) {
            return emptyQuads();
        }
        if (!isUniformQuads(src)) {
            throw new IllegalArgumentException("subdivideQuadsOnce requires all faces to be quads");
        }
        int srcV = src.vertexCount();
        int srcE = src.edgeCount();
        int srcF = src.faceCount();
        int outV = srcV + srcE + srcF;
        int outF = srcF * NUM_4;
        float[] positions = new float[outV * FP];
        int[] faceIndices = new int[outF * NUM_4];
        Vector3f p = new Vector3f();
        Vector3f q = new Vector3f();

        HashMap<Long, Integer> edgeMidMap = new HashMap<>(srcE * NUM_4 / FP + 1);
        for (int ei = 0; ei < srcE; ei++) {
            int eid = src.edgeIdAt(ei);
            int he = src.edgeHalfEdge(eid);
            int va = src.halfEdgeVertex(he);
            int vb = src.halfEdgeEndVertex(he);
            src.vertexPosition(va, p);
            src.vertexPosition(vb, q);
            p.add(q).mul(NUM_0_5);
            int midIdx = srcV + ei;
            int o = midIdx * FP;
            positions[o] = p.x;
            positions[o + 1] = p.y;
            positions[o + 2] = p.z;
            edgeMidMap.put(edgeKey(va, vb), midIdx);
        }

        for (int fi = 0; fi < srcF; fi++) {
            int fid = src.faceIdAt(fi);
            p.set(NUM_0, NUM_0, NUM_0);
            int[] faceVerts = new int[NUM_4];
            for (int k = 0; k < NUM_4; k++) {
                faceVerts[k] = src.faceVertexAt(fid, k);
                src.vertexPosition(faceVerts[k], q);
                p.add(q);
            }
            p.mul(NUM_0_25);
            int centIdx = srcV + srcE + fi;
            int co = centIdx * FP;
            positions[co] = p.x;
            positions[co + 1] = p.y;
            positions[co + 2] = p.z;
        }

        for (int vi = 0; vi < srcV; vi++) {
            int vid = src.vertexIdAt(vi);
            src.vertexPosition(vid, p);
            int o = vi * FP;
            positions[o] = p.x;
            positions[o + 1] = p.y;
            positions[o + 2] = p.z;
        }

        for (int fi = 0; fi < srcF; fi++) {
            int fid = src.faceIdAt(fi);
            int[] faceVerts = new int[NUM_4];
            for (int k = 0; k < NUM_4; k++) {
                faceVerts[k] = src.faceVertexAt(fid, k);
            }
            int centroid = srcV + srcE + fi;
            for (int k = 0; k < NUM_4; k++) {
                int va = faceVerts[k];
                int vb = faceVerts[(k + 1) % NUM_4];
                int vc = faceVerts[(k + FP) % NUM_4];
                int nva = va;
                Integer midAB = edgeMidMap.get(edgeKey(va, vb));
                Integer midCA = edgeMidMap.get(edgeKey(vc, va));
                if (midAB == null || midCA == null) {
                    throw new IllegalStateException("missing edge midpoint");
                }
                int base = (fi * NUM_4 + k) * NUM_4;
                faceIndices[base] = nva;
                faceIndices[base + 1] = midAB;
                faceIndices[base + 2] = centroid;
                faceIndices[base + FP] = midCA;
            }
        }
        ArrayMesh out = new ArrayMesh(positions, null, faceIndices, NUM_4);
        out.computeNormals();
        return out;
    }

    /**
     * Gives an open quad sheet volumetric thickness by duplicating vertices along
     * <strong>inward</strong> vertex normals (topology must be uniform quads).
     * Adds reversed-orientation bottom faces and a side quad per boundary edge.
     *
     * @param mesh TODO: describe
     * @param thickness TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @throws IllegalStateException TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh solidifyUniformQuads(ArrayMesh mesh, float thickness) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return emptyQuads();
        }
        float t = Math.abs(thickness);
        if (t == NUM_0) {
            return new ArrayMesh(mesh.copyPositions(), mesh.copyNormals(), mesh.copyFaceIndices(), mesh.getVertsPerFace());
        }
        if (!isUniformQuads(mesh)) {
            throw new IllegalArgumentException("solidifyUniformQuads requires uniform quads");
        }
        mesh.computeNormals();
        int n = mesh.vertexCount();
        int faceCount = mesh.faceCount();
        float[] top = mesh.copyPositions();
        float[] nrm = mesh.copyNormals();
        float[] positions = new float[n * 2 * FP];
        System.arraycopy(top, 0, positions, 0, n * FP);
        int off = n * FP;
        for (int i = 0; i < n; i++) {
            int b = i * FP;
            positions[off + b] = top[b] - nrm[b] * t;
            positions[off + b + 1] = top[b + 1] - nrm[b + 1] * t;
            positions[off + b + 2] = top[b + 2] - nrm[b + 2] * t;
        }

        mesh.edgeCount();
        int boundaryEdges = 0;
        int ec = mesh.edgeCount();
        for (int ei = 0; ei < ec; ei++) {
            if (mesh.isBoundaryEdge(ei)) {
                boundaryEdges++;
            }
        }

        int[] srcFaces = mesh.copyFaceIndices();
        int quadCount = faceCount * 2 + boundaryEdges;
        int[] faceIndices = new int[quadCount * NUM_4];
        System.arraycopy(srcFaces, 0, faceIndices, 0, faceCount * NUM_4);
        int w = faceCount * NUM_4;
        for (int fi = 0; fi < faceCount; fi++) {
            int b = fi * NUM_4;
            int v0 = srcFaces[b];
            int v1 = srcFaces[b + 1];
            int v2 = srcFaces[b + 2];
            int v3 = srcFaces[b + FP];
            faceIndices[w++] = v0 + n;
            faceIndices[w++] = v3 + n;
            faceIndices[w++] = v2 + n;
            faceIndices[w++] = v1 + n;
        }
        for (int ei = 0; ei < ec; ei++) {
            if (!mesh.isBoundaryEdge(ei)) {
                continue;
            }
            int he = mesh.edgeHalfEdge(ei);
            int a = mesh.halfEdgeVertex(he);
            int b = mesh.halfEdgeEndVertex(he);
            faceIndices[w++] = a;
            faceIndices[w++] = a + n;
            faceIndices[w++] = b + n;
            faceIndices[w++] = b;
        }
        if (w != faceIndices.length) {
            throw new IllegalStateException("solidifyUniformQuads index fill mismatch: w=" + w + " expected=" + faceIndices.length);
        }
        ArrayMesh out = new ArrayMesh(positions, null, faceIndices, NUM_4);
        out.computeNormals();
        return out;
    }

    /**
     * TODO: document {@code solidifyUniformMeshTopology}.
     *
     * @param mesh TODO: describe
     * @param thickness TODO: describe
     * @return TODO: describe
     */
    public static MeshTopology solidifyUniformMeshTopology(MeshTopology mesh, float thickness) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return emptyQuads();
        }
        ArrayMesh am = mesh instanceof ArrayMesh m ? m : fromUniformMeshTopology(mesh);
        return solidifyUniformQuads(am, thickness);
    }

    /**
     * TODO: document {@code deleteVertices}.
     *
     * @param mesh TODO: describe
     * @param del TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh deleteVertices(ArrayMesh mesh, boolean[] del) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return emptyQuads();
        }
        int n = mesh.vertexCount();
        if (del == null || del.length != n) {
            throw new IllegalArgumentException("selection length must match vertex count");
        }
        boolean any = false;
        for (boolean b : del) {
            if (b) {
                any = true;
                break;
            }
        }
        if (!any) {
            return new ArrayMesh(mesh.copyPositions(), mesh.copyNormals(), mesh.copyFaceIndices(),
                    mesh.getVertsPerFace());
        }
        int[] oldToNew = new int[n];
        Arrays.fill(oldToNew, -1);
        int nv = 0;
        Vector3f p = new Vector3f();
        for (int i = 0; i < n; i++) {
            if (del[i]) {
                continue;
            }
            oldToNew[i] = nv++;
        }
        if (nv == 0) {
            return emptyQuads();
        }
        float[] pos = new float[nv * FP];
        for (int i = 0; i < n; i++) {
            int ni = oldToNew[i];
            if (ni < 0) {
                continue;
            }
            mesh.vertexPosition(i, p);
            int o = ni * FP;
            pos[o] = p.x;
            pos[o + 1] = p.y;
            pos[o + 2] = p.z;
        }
        int vpf = mesh.getVertsPerFace();
        int fc = mesh.faceCount();
        int[] newFaces = new int[fc * vpf];
        int outF = 0;
        for (int fi = 0; fi < fc; fi++) {
            boolean skip = false;
            for (int k = 0; k < vpf; k++) {
                int ov = mesh.faceVertexAt(fi, k);
                if (ov < 0 || ov >= n || del[ov]) {
                    skip = true;
                    break;
                }
            }
            if (skip) {
                continue;
            }
            for (int k = 0; k < vpf; k++) {
                int ov = mesh.faceVertexAt(fi, k);
                newFaces[outF * vpf + k] = oldToNew[ov];
            }
            outF++;
        }
        if (outF == 0) {
            return emptyQuads();
        }
        ArrayMesh out = new ArrayMesh(pos, null, Arrays.copyOf(newFaces, outF * vpf), vpf);
        out.computeNormals();
        return out;
    }

    /**
     * TODO: document {@code deleteEdges}.
     *
     * @param mesh TODO: describe
     * @param delEdge TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh deleteEdges(ArrayMesh mesh, boolean[] delEdge) {
        if (mesh == null || mesh.edgeCount() == 0) {
            return emptyQuads();
        }
        int ne = mesh.edgeCount();
        if (delEdge == null) {
            return new ArrayMesh(mesh.copyPositions(), mesh.copyNormals(), mesh.copyFaceIndices(),
                    mesh.getVertsPerFace());
        }
        boolean any = false;
        int len = Math.min(delEdge.length, ne);
        for (int i = 0; i < len; i++) {
            if (delEdge[i]) {
                any = true;
                break;
            }
        }
        if (!any) {
            return new ArrayMesh(mesh.copyPositions(), mesh.copyNormals(), mesh.copyFaceIndices(),
                    mesh.getVertsPerFace());
        }
        HashSet<Integer> deadEdgeIds = new HashSet<>();
        for (int i = 0; i < len; i++) {
            if (delEdge[i]) {
                deadEdgeIds.add(mesh.edgeIdAt(i));
            }
        }
        HashSet<Integer> deadFaceIds = new HashSet<>();
        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int fec = mesh.faceEdgeCount(fid);
            for (int k = 0; k < fec; k++) {
                int eid = mesh.faceEdgeAt(fid, k);
                if (deadEdgeIds.contains(eid)) {
                    deadFaceIds.add(fid);
                    break;
                }
            }
        }
        HashSet<Integer> referencedVerts = new HashSet<>();
        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            if (deadFaceIds.contains(fid)) {
                continue;
            }
            int fvc = mesh.faceVertexCount(fid);
            for (int k = 0; k < fvc; k++) {
                referencedVerts.add(mesh.faceVertexAt(fid, k));
            }
        }
        int n = mesh.vertexCount();
        HashMap<Integer, Integer> vertMap = new HashMap<>();
        Vector3f p = new Vector3f();
        for (int vi = 0; vi < n; vi++) {
            int vid = mesh.vertexIdAt(vi);
            if (!referencedVerts.contains(vid)) {
                continue;
            }
            mesh.vertexPosition(vid, p);
            int nid = vertMap.size();
            vertMap.put(vid, nid);
        }
        int nv = vertMap.size();
        float[] pos = new float[nv * FP];
        for (var e : vertMap.entrySet()) {
            mesh.vertexPosition(e.getKey(), p);
            int o = e.getValue() * FP;
            pos[o] = p.x;
            pos[o + 1] = p.y;
            pos[o + 2] = p.z;
        }
        int vpf = mesh.getVertsPerFace();
        int fc = mesh.faceCount();
        int[] tmpFaces = new int[fc * vpf];
        int outF = 0;
        for (int fi = 0; fi < fc; fi++) {
            int fid = mesh.faceIdAt(fi);
            if (deadFaceIds.contains(fid)) {
                continue;
            }
            int fvc = mesh.faceVertexCount(fid);
            for (int k = 0; k < fvc; k++) {
                tmpFaces[outF * vpf + k] = vertMap.get(mesh.faceVertexAt(fid, k));
            }
            outF++;
        }
        ArrayMesh out = new ArrayMesh(pos, null, Arrays.copyOf(tmpFaces, outF * vpf), vpf);
        out.computeNormals();
        return out;
    }

    /**
     * TODO: document {@code mergeByDistance}.
     *
     * @param mesh TODO: describe
     * @param distance TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh mergeByDistance(ArrayMesh mesh, float distance) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return emptyQuads();
        }
        if (distance <= NUM_0) {
            return new ArrayMesh(mesh.copyPositions(), mesh.copyNormals(), mesh.copyFaceIndices(),
                    mesh.getVertsPerFace());
        }
        return MeshMergeByDistance.mergeToArrayMesh(mesh, distance);
    }

    /**
     * TODO: document {@code join}.
     *
     * @param a TODO: describe
     * @param b TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh join(ArrayMesh a, ArrayMesh b) {
        if (a == null || a.vertexCount() == 0) {
            return b == null ? emptyQuads()
                    : new ArrayMesh(b.copyPositions(), b.copyNormals(), b.copyFaceIndices(), b.getVertsPerFace());
        }
        if (b == null || b.vertexCount() == 0) {
            return new ArrayMesh(a.copyPositions(), a.copyNormals(), a.copyFaceIndices(), a.getVertsPerFace());
        }
        int vpf = a.getVertsPerFace();
        if (b.getVertsPerFace() != vpf) {
            throw new IllegalArgumentException("vertsPerFace must match for join");
        }
        int va = a.vertexCount();
        int vb = b.vertexCount();
        float[] pos = new float[(va + vb) * FP];
        System.arraycopy(a.copyPositions(), 0, pos, 0, va * FP);
        System.arraycopy(b.copyPositions(), 0, pos, va * FP, vb * FP);
        int fa = a.faceCount();
        int fb = b.faceCount();
        int[] fi = new int[(fa + fb) * vpf];
        System.arraycopy(a.copyFaceIndices(), 0, fi, 0, fa * vpf);
        int[] bfi = b.copyFaceIndices();
        for (int i = 0; i < fb * vpf; i++) {
            fi[fa * vpf + i] = bfi[i] + va;
        }
        ArrayMesh out = new ArrayMesh(pos, null, fi, vpf);
        out.computeNormals();
        return out;
    }

    /**
     * Splits quad faces along one world-space axis by inserting {@code cuts} new
     * edge loops. Edges aligned with the axis (dot product &gt; 0.5) are split;
     * faces with two split opposite edges become {@code cuts+1} strip quads.
     * Faces with no aligned edges pass through unchanged.
     *
     * @param axisIndex 0=X, 1=Y, 2=Z
     * @param src TODO: describe
     * @param cuts TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh loopCutAxis(ArrayMesh src, int cuts, int axisIndex) {
        if (src == null || src.vertexCount() == 0) return emptyQuads();
        if (cuts <= 0) {
            return new ArrayMesh(src.copyPositions(), src.copyNormals(), src.copyFaceIndices(), src.getVertsPerFace());
        }
        if (!isUniformQuads(src)) {
            throw new IllegalArgumentException("loop_cut requires uniform quads");
        }

        int srcV = src.vertexCount();
        int srcF = src.faceCount();
        int srcE = src.edgeCount(); // triggers topology build

        float ax = axisIndex == 0 ? NUM_1 : NUM_0;
        float ay = axisIndex == 1 ? NUM_1 : NUM_0;
        float az = axisIndex == 2 ? NUM_1 : NUM_0;

        float[] srcPos = src.copyPositions();
        Vector3f pa = new Vector3f(), pb = new Vector3f(), dir = new Vector3f();

        // Classify edges: mark aligned ones for splitting, allocate midpoint vertex indices
        HashMap<Long, int[]> splitMap = new HashMap<>();
        int newVertCount = 0;
        for (int ei = 0; ei < srcE; ei++) {
            int eid = src.edgeIdAt(ei);
            int he = src.edgeHalfEdge(eid);
            int va = src.halfEdgeVertex(he);
            int vb = src.halfEdgeEndVertex(he);

            src.vertexPosition(va, pa);
            src.vertexPosition(vb, pb);
            dir.set(pb).sub(pa);
            float len = dir.length();
            if (len < NUM_1e_9) continue;
            dir.div(len);

            float alignment = Math.abs(dir.x * ax + dir.y * ay + dir.z * az);
            if (alignment > NUM_0_5) {
                long key = edgeKey(va, vb);
                if (!splitMap.containsKey(key)) {
                    int[] mids = new int[cuts];
                    for (int c = 0; c < cuts; c++) {
                        mids[c] = srcV + newVertCount++;
                    }
                    splitMap.put(key, mids);
                }
            }
        }

        if (splitMap.isEmpty()) {
            return new ArrayMesh(src.copyPositions(), src.copyNormals(), src.copyFaceIndices(), src.getVertsPerFace());
        }

        // Build output positions: original + midpoints
        int outV = srcV + newVertCount;
        float[] positions = new float[outV * FP];
        System.arraycopy(srcPos, 0, positions, 0, srcV * FP);

        for (var entry : splitMap.entrySet()) {
            long key = entry.getKey();
            int[] mids = entry.getValue();
            int lo = (int) (key >>> NUM_32);
            int hi = (int) (key & NUM_0xffffffff);
            float x0 = srcPos[lo * FP], y0 = srcPos[lo * FP + 1], z0 = srcPos[lo * FP + 2];
            float x1 = srcPos[hi * FP], y1 = srcPos[hi * FP + 1], z1 = srcPos[hi * FP + 2];
            for (int c = 0; c < cuts; c++) {
                float t = (float) (c + 1) / (cuts + 1);
                int o = mids[c] * FP;
                positions[o] = x0 + t * (x1 - x0);
                positions[o + 1] = y0 + t * (y1 - y0);
                positions[o + 2] = z0 + t * (z1 - z0);
            }
        }

        // Count output faces
        int[] srcFaces = src.copyFaceIndices();
        int outF = 0;
        for (int fi = 0; fi < srcF; fi++) {
            int b = fi * NUM_4;
            int v0 = srcFaces[b], v1 = srcFaces[b + 1], v2 = srcFaces[b + 2], v3 = srcFaces[b + FP];
            boolean e0 = splitMap.containsKey(edgeKey(v0, v1));
            boolean e1 = splitMap.containsKey(edgeKey(v1, v2));
            boolean e2 = splitMap.containsKey(edgeKey(v2, v3));
            boolean e3 = splitMap.containsKey(edgeKey(v3, v0));
            outF += ((e0 && e2) || (e1 && e3)) ? cuts + 1 : 1;
        }

        // Build output faces
        int[] faceIndices = new int[outF * NUM_4];
        int w = 0;
        for (int fi = 0; fi < srcF; fi++) {
            int b = fi * NUM_4;
            int v0 = srcFaces[b], v1 = srcFaces[b + 1], v2 = srcFaces[b + 2], v3 = srcFaces[b + FP];
            boolean e0 = splitMap.containsKey(edgeKey(v0, v1));
            boolean e1 = splitMap.containsKey(edgeKey(v1, v2));
            boolean e2 = splitMap.containsKey(edgeKey(v2, v3));
            boolean e3 = splitMap.containsKey(edgeKey(v3, v0));

            if (e0 && e2) {
                // Split pair A: edges v0→v1 and v2→v3
                int[] botMids = directedMids(splitMap, v0, v1);
                int[] topMids = directedMids(splitMap, v3, v2); // reversed to match bot direction
                for (int k = 0; k <= cuts; k++) {
                    faceIndices[w++] = k == 0 ? v0 : botMids[k - 1];
                    faceIndices[w++] = k == cuts ? v1 : botMids[k];
                    faceIndices[w++] = k == cuts ? v2 : topMids[k];
                    faceIndices[w++] = k == 0 ? v3 : topMids[k - 1];
                }
            } else if (e1 && e3) {
                // Split pair B: edges v1→v2 and v3→v0
                int[] rightMids = directedMids(splitMap, v1, v2);
                int[] leftMids = directedMids(splitMap, v0, v3);
                for (int k = 0; k <= cuts; k++) {
                    faceIndices[w++] = k == 0 ? v0 : leftMids[k - 1];
                    faceIndices[w++] = k == 0 ? v1 : rightMids[k - 1];
                    faceIndices[w++] = k == cuts ? v2 : rightMids[k];
                    faceIndices[w++] = k == cuts ? v3 : leftMids[k];
                }
            } else {
                // Pass through unchanged
                faceIndices[w++] = v0;
                faceIndices[w++] = v1;
                faceIndices[w++] = v2;
                faceIndices[w++] = v3;
            }
        }

        ArrayMesh out = new ArrayMesh(positions, null, faceIndices, NUM_4);
        out.computeNormals();
        return out;
    }

    /**
     * Returns midpoints of undirected edge (va,vb) in the direction from va toward vb.
     *
     * @param splitMap TODO: describe
     * @param va TODO: describe
     * @param vb TODO: describe
     * @return TODO: describe
     */
    private static int[] directedMids(HashMap<Long, int[]> splitMap, int va, int vb) {
        int[] mids = splitMap.get(edgeKey(va, vb));
        if (mids == null) return null;
        if (va <= vb) return mids;
        // Reverse: splitMap stores lo→hi, but we need hi→lo
        int[] rev = new int[mids.length];
        for (int i = 0; i < mids.length; i++) rev[i] = mids[mids.length - 1 - i];
        return rev;
    }

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << NUM_32) | (hi & NUM_0xffffffff);
    }
}
