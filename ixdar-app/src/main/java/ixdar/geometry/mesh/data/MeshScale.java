package ixdar.geometry.mesh.data;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.Vec3Field;
import ixdar.annotations.meshnode.Vector3Value;

/**
 * Applies per-vertex or uniform scale to mesh positions.
 */
public final class MeshScale {

    private MeshScale() {
    }

    public static HalfEdgeMesh apply(MeshTopology mesh, Object scaleObj) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return new HalfEdgeMesh();
        }
        int n = mesh.vertexCount();
        Vector3f tmp = new Vector3f();
        Vec3Field field = null;
        Vector3Value uniform = new Vector3Value(1f, 1f, 1f);
        if (scaleObj instanceof Vec3Field vf) {
            if (vf.length() != n) {
                throw new IllegalArgumentException("scale field length " + vf.length() + " != vertex count " + n);
            }
            field = vf;
        } else if (scaleObj instanceof Vector3Value vv) {
            uniform = vv;
        }

        HalfEdgeMesh out = new HalfEdgeMesh();
        java.util.HashMap<Integer, Integer> idMap = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            mesh.vertexPosition(vid, tmp);
            if (field != null) {
                tmp.mul(field.getX(i), field.getY(i), field.getZ(i));
            } else {
                tmp.mul(uniform.x(), uniform.y(), uniform.z());
            }
            int nid = out.addVertex(tmp);
            idMap.put(vid, nid);
        }

        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int fc = mesh.faceVertexCount(fid);
            int[] nv = new int[fc];
            for (int k = 0; k < fc; k++) {
                int ov = mesh.faceVertexAt(fid, k);
                nv[k] = idMap.get(ov);
            }
            out.addFace(nv);
        }
        out.computeNormals();
        return out;
    }
}
