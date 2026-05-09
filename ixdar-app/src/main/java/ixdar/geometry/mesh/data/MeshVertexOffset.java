package ixdar.geometry.mesh.data;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.Vector3Field;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Applies per-vertex offsets to a mesh, producing a new mesh (dense
 * {@link ArrayMesh} when the input is {@link ArrayMesh}, otherwise
 * {@link HalfEdgeMesh}).
 */
public final class MeshVertexOffset {
    public static final float NUM_0 = 0f;
    public static final int NUM_3 = 3;

    private MeshVertexOffset() {
    }

    /**
     * Add a per-vertex or uniform offset to every vertex of {@code mesh},
     * returning a new mesh of the same kind with normals recomputed.
     *
     * @param mesh source mesh; null or empty meshes pass through as an empty mesh of the appropriate kind
     * @param offsetObj a {@link Vector3Field} (length must equal vertex count), a {@link Vector3Value} for a uniform offset, or anything else for a zero offset
     * @throws IllegalArgumentException if {@code offsetObj} is a {@link Vector3Field} whose length does not match {@code mesh.vertexCount()}
     * @return new offset mesh ({@link ArrayMesh} when input is dense, otherwise {@link HalfEdgeMesh})
     */
    public static MeshTopology apply(MeshTopology mesh, Object offsetObj) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return mesh instanceof ArrayMesh ? ArrayMeshEngine.emptyQuads() : new HalfEdgeMesh();
        }
        int n = mesh.vertexCount();
        Vector3f tmp = new Vector3f();
        Vector3Field field = null;
        Vector3Value uniform = null;
        if (offsetObj instanceof Vector3Field vf) {
            if (vf.length() != n) {
                throw new IllegalArgumentException("offset field length " + vf.length() + " != vertex count " + n);
            }
            field = vf;
        } else if (offsetObj instanceof Vector3Value vv) {
            uniform = vv;
        } else {
            uniform = new Vector3Value(NUM_0, NUM_0, NUM_0);
        }

        if (mesh instanceof ArrayMesh am) {
            float[] pos = am.copyPositions();
            for (int i = 0; i < n; i++) {
                int o = i * NUM_3;
                if (field != null) {
                    pos[o] += field.getX(i);
                    pos[o + 1] += field.getY(i);
                    pos[o + 2] += field.getZ(i);
                } else {
                    pos[o] += uniform.x();
                    pos[o + 1] += uniform.y();
                    pos[o + 2] += uniform.z();
                }
            }
            ArrayMesh out = new ArrayMesh(pos, null, am.copyFaceIndices(), am.getVertsPerFace());
            out.computeNormals();
            return out;
        }

        HalfEdgeMesh out = new HalfEdgeMesh();
        java.util.HashMap<Integer, Integer> idMap = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            mesh.vertexPosition(vid, tmp);
            if (field != null) {
                tmp.add(field.getX(i), field.getY(i), field.getZ(i));
            } else {
                tmp.add(uniform.x(), uniform.y(), uniform.z());
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
