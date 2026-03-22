package ixdar.geometry.mesh.data;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Appends meshes into a single {@link HalfEdgeMesh}.
 */
public final class MeshAppend {

    private MeshAppend() {
    }

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

    public static HalfEdgeMesh join(MeshTopology a, MeshTopology b) {
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
