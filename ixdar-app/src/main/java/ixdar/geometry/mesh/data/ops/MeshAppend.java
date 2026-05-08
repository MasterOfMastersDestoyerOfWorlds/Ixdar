package ixdar.geometry.mesh.data.ops;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.ArrayMeshEngine;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Appends meshes into a single {@link HalfEdgeMesh}, or joins two
 * {@link ArrayMesh} meshes without half-edge allocation.
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
        java.util.HashMap<Integer, Integer> idMap = new java.util.HashMap<>();
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
     * Concatenate two meshes. Two {@link ArrayMesh} inputs sharing
     * verts-per-face go through the dense engine; everything else falls back to
     * a {@link HalfEdgeMesh} append with normals recomputed.
     *
     * @param a first mesh
     * @param b second mesh
     * @return combined mesh (an {@link ArrayMesh} on the dense fast path, otherwise a {@link HalfEdgeMesh})
     */
    public static MeshTopology join(MeshTopology a, MeshTopology b) {
        if (a instanceof ArrayMesh aa && b instanceof ArrayMesh ab && aa.getVertsPerFace() == ab.getVertsPerFace()) {
            return ArrayMeshEngine.join(aa, ab);
        }
        HalfEdgeMesh out = new HalfEdgeMesh();
        Matrix4f id = new Matrix4f();
        append(out, a, id);
        append(out, b, id);
        if (out.vertexCount() > 0) {
            out.computeNormals();
        }
        return out;
    }
}
