package ixdar.geometry.mesh.data.ops;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import ixdar.geometry.mesh.data.representation.MeshTopology;

import java.util.HashMap;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * Appends meshes into a single {@link HalfEdgeMesh}.
 */
public final class MeshAppend {

    private MeshAppend() {
    }

    /**
     * Append every vertex and face of {@code src} into {@code out}, transforming
     * vertex positions by {@code transform}. {@code src} ids are remapped to the
     * new ids assigned in {@code out}.
     *
     * @param out destination mesh (mutated in place and returned)
     * @param src source mesh; null or empty inputs are passed through
     * @param transform homogeneous transform applied to each source vertex position
     * @return {@code out}
     */
    public static HalfEdgeMesh append(HalfEdgeMesh out, MeshTopology src, Matrix4f transform) {
        if (src == null || src.vertexCount() == 0) {
            return out;
        }
        Vector3f p = new Vector3f();
        Vector4f ph = new Vector4f();
        int n = src.vertexCount();
        HashMap<Integer, Integer> idMap = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int vid = src.vertexIdAt(i);
            src.vertexPosition(vid, p);
            ph.set(p.x, p.y, p.z, 1f);
            transform.transform(ph);
            int nid = out.addVertex(ph.x, ph.y, ph.z);
            idMap.put(vid, nid);
        }
        for (int fi = 0; fi < src.faceCount(); fi++) {
            int fid = src.faceIdAt(fi);
            int fc = src.faceVertexCount(fid);
            int[] nv = new int[fc];
            for (int k = 0; k < fc; k++) {
                int ov = src.faceVertexAt(fid, k);
                nv[k] = idMap.get(ov);
            }
            out.addFace(nv);
        }
        return out;
    }

    /**
     * Concatenate two meshes without welding: {@code a}'s vertices and faces, then {@code b}'s,
     * each in active order, with normals recomputed.
     *
     * @param a first mesh, or null
     * @param b second mesh, or null
     * @return combined mesh
     */
    public static MeshTopology join(MeshTopology a, MeshTopology b) {
        MeshTopology first = a == null ? new HalfEdgeMesh() : a;
        MeshTopology second = b == null ? new HalfEdgeMesh() : b;
        MeshTopology[] parts = { first, second };
        int corners = 0;
        for (MeshTopology part : parts) {
            for (int activeFace = 0; activeFace < part.faceCount(); activeFace++) {
                corners += part.faceVertexCount(part.faceIdAt(activeFace));
            }
        }
        float[] positions = new float[(first.vertexCount() + second.vertexCount()) * 3];
        int[] faceSizes = new int[first.faceCount() + second.faceCount()];
        int[] faceCorners = new int[corners];
        Vector3f position = new Vector3f();
        int outVertex = 0;
        int outFace = 0;
        int outCorner = 0;
        for (MeshTopology part : parts) {
            int[] outVertexOfId = new int[part.vertexIdBound()];
            for (int activeVertex = 0; activeVertex < part.vertexCount(); activeVertex++) {
                int vertexId = part.vertexIdAt(activeVertex);
                part.vertexPosition(vertexId, position);
                positions[outVertex * 3] = position.x;
                positions[outVertex * 3 + 1] = position.y;
                positions[outVertex * 3 + 2] = position.z;
                outVertexOfId[vertexId] = outVertex++;
            }
            for (int activeFace = 0; activeFace < part.faceCount(); activeFace++) {
                int faceId = part.faceIdAt(activeFace);
                int size = part.faceVertexCount(faceId);
                for (int corner = 0; corner < size; corner++) {
                    faceCorners[outCorner++] = outVertexOfId[part.faceVertexAt(faceId, corner)];
                }
                faceSizes[outFace++] = size;
            }
        }
        HalfEdgeMesh out = HalfEdgeMeshEngine.bulkAllocateMixed(positions, faceSizes, faceCorners);
        if (out.vertexCount() > 0) {
            out.computeNormals();
        }
        return out;
    }
}
