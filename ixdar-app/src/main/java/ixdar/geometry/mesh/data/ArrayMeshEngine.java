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

    private static final int FP = 3;

    private ArrayMeshEngine() {
    }

    public static ArrayMesh emptyQuads() {
        return new ArrayMesh(new float[0], null, new int[0], 4);
    }

    /**
     * Packs a mesh with uniform face size into an {@link ArrayMesh}. Vertex
     * iteration order becomes contiguous indices {@code 0..vertexCount-1}.
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
        HashMap<Integer, Integer> idToIndex = new HashMap<>(n * 4 / 3 + 1);
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

    public static boolean isUniformQuads(MeshTopology mesh) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return true;
        }
        int nf = mesh.faceCount();
        if (nf == 0) {
            return true;
        }
        for (int fi = 0; fi < nf; fi++) {
            if (mesh.faceVertexCount(mesh.faceIdAt(fi)) != 4) {
                return false;
            }
        }
        return true;
    }

    /**
     * One level of linear quad subdivision (edge midpoints + face centroids),
     * matching {@link ixdar.geometry.mesh.nodes.modifier.SubdivideMeshNode} for
     * uniform quad meshes.
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
        int outF = srcF * 4;
        float[] positions = new float[outV * FP];
        int[] faceIndices = new int[outF * 4];
        Vector3f p = new Vector3f();
        Vector3f q = new Vector3f();

        HashMap<Long, Integer> edgeMidMap = new HashMap<>(srcE * 4 / 3 + 1);
        for (int ei = 0; ei < srcE; ei++) {
            int eid = src.edgeIdAt(ei);
            int he = src.edgeHalfEdge(eid);
            int va = src.halfEdgeVertex(he);
            int vb = src.halfEdgeEndVertex(he);
            src.vertexPosition(va, p);
            src.vertexPosition(vb, q);
            p.add(q).mul(0.5f);
            int midIdx = srcV + ei;
            int o = midIdx * FP;
            positions[o] = p.x;
            positions[o + 1] = p.y;
            positions[o + 2] = p.z;
            edgeMidMap.put(edgeKey(va, vb), midIdx);
        }

        for (int fi = 0; fi < srcF; fi++) {
            int fid = src.faceIdAt(fi);
            p.set(0f, 0f, 0f);
            int[] faceVerts = new int[4];
            for (int k = 0; k < 4; k++) {
                faceVerts[k] = src.faceVertexAt(fid, k);
                src.vertexPosition(faceVerts[k], q);
                p.add(q);
            }
            p.mul(0.25f);
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
            int[] faceVerts = new int[4];
            for (int k = 0; k < 4; k++) {
                faceVerts[k] = src.faceVertexAt(fid, k);
            }
            int centroid = srcV + srcE + fi;
            for (int k = 0; k < 4; k++) {
                int va = faceVerts[k];
                int vb = faceVerts[(k + 1) % 4];
                int vc = faceVerts[(k + 3) % 4];
                int nva = va;
                Integer midAB = edgeMidMap.get(edgeKey(va, vb));
                Integer midCA = edgeMidMap.get(edgeKey(vc, va));
                if (midAB == null || midCA == null) {
                    throw new IllegalStateException("missing edge midpoint");
                }
                int base = (fi * 4 + k) * 4;
                faceIndices[base] = nva;
                faceIndices[base + 1] = midAB;
                faceIndices[base + 2] = centroid;
                faceIndices[base + 3] = midCA;
            }
        }
        ArrayMesh out = new ArrayMesh(positions, null, faceIndices, 4);
        out.computeNormals();
        return out;
    }

    /**
     * Gives an open quad sheet volumetric thickness by duplicating vertices along
     * <strong>inward</strong> vertex normals (topology must be uniform quads).
     * Adds reversed-orientation bottom faces and a side quad per boundary edge.
     */
    public static ArrayMesh solidifyUniformQuads(ArrayMesh mesh, float thickness) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return emptyQuads();
        }
        float t = Math.abs(thickness);
        if (t == 0f) {
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
        int[] faceIndices = new int[quadCount * 4];
        System.arraycopy(srcFaces, 0, faceIndices, 0, faceCount * 4);
        int w = faceCount * 4;
        for (int fi = 0; fi < faceCount; fi++) {
            int b = fi * 4;
            int v0 = srcFaces[b];
            int v1 = srcFaces[b + 1];
            int v2 = srcFaces[b + 2];
            int v3 = srcFaces[b + 3];
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
        ArrayMesh out = new ArrayMesh(positions, null, faceIndices, 4);
        out.computeNormals();
        return out;
    }

    public static MeshTopology solidifyUniformMeshTopology(MeshTopology mesh, float thickness) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return emptyQuads();
        }
        ArrayMesh am = mesh instanceof ArrayMesh m ? m : fromUniformMeshTopology(mesh);
        return solidifyUniformQuads(am, thickness);
    }

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

    public static ArrayMesh mergeByDistance(ArrayMesh mesh, float distance) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return emptyQuads();
        }
        if (distance <= 0f) {
            return new ArrayMesh(mesh.copyPositions(), mesh.copyNormals(), mesh.copyFaceIndices(),
                    mesh.getVertsPerFace());
        }
        return MeshMergeByDistance.mergeToArrayMesh(mesh, distance);
    }

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

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }
}
