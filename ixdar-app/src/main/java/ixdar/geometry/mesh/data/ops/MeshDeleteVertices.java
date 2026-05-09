package ixdar.geometry.mesh.data.ops;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.BoolField;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.MeshVertexOffset;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Deletes selected vertices (where selection is true), producing a new mesh
 * (preferring {@link ArrayMesh}).
 */
public final class MeshDeleteVertices {

    private MeshDeleteVertices() {
    }

    /**
     * Delete every vertex flagged in {@code selectionObj}, dropping faces that used any deleted vertex.
     *
     * @param mesh source mesh; treated read-only
     * @param selectionObj per-vertex {@link BoolField} whose length matches {@code mesh.vertexCount()},
     *                     or a {@link Boolean} ({@code true} = delete every vertex)
     * @throws IllegalArgumentException if {@code selectionObj} is a {@link BoolField} of mismatched length
     * @return new mesh — empty if everything was deleted, a copy of the input if nothing was selected,
     *         an {@link ArrayMesh} when the input is one, otherwise a {@link HalfEdgeMesh}
     */
    public static MeshTopology delete(MeshTopology mesh, Object selectionObj) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return mesh instanceof ArrayMesh ? ArrayMeshEngine.emptyQuads() : new HalfEdgeMesh();
        }
        int n = mesh.vertexCount();
        boolean deleteAll = false;
        boolean[] del = new boolean[n];
        if (selectionObj instanceof BoolField bf) {
            if (bf.length() != n) {
                throw new IllegalArgumentException("selection length " + bf.length() + " != vertex count " + n);
            }
            for (int i = 0; i < n; i++) {
                del[i] = bf.get(i);
            }
        } else if (selectionObj instanceof Boolean b) {
            deleteAll = b;
        } else {
            deleteAll = false;
        }

        if (deleteAll) {
            return mesh instanceof ArrayMesh ? ArrayMeshEngine.emptyQuads() : new HalfEdgeMesh();
        }

        boolean any = false;
        for (boolean b : del) {
            if (b) {
                any = true;
                break;
            }
        }
        if (!any) {
            return copyMesh(mesh);
        }

        if (mesh instanceof ArrayMesh am) {
            return ArrayMeshEngine.deleteVertices(am, del);
        }

        java.util.HashMap<Integer, Integer> oldToNew = new java.util.HashMap<>();
        HalfEdgeMesh out = new HalfEdgeMesh();
        Vector3f p = new Vector3f();
        for (int i = 0; i < n; i++) {
            if (del[i]) {
                continue;
            }
            int vid = mesh.vertexIdAt(i);
            mesh.vertexPosition(vid, p);
            int nid = out.addVertex(p);
            oldToNew.put(vid, nid);
        }

        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int fc = mesh.faceVertexCount(fid);
            int[] nv = new int[fc];
            boolean skip = false;
            for (int k = 0; k < fc; k++) {
                int ov = mesh.faceVertexAt(fid, k);
                int ai = activeIndex(mesh, ov);
                if (ai < 0 || del[ai]) {
                    skip = true;
                    break;
                }
                nv[k] = oldToNew.get(ov);
            }
            if (!skip) {
                out.addFace(nv);
            }
        }
        out.computeNormals();
        return out;
    }

    private static int activeIndex(MeshTopology mesh, int vertexId) {
        int n = mesh.vertexCount();
        for (int i = 0; i < n; i++) {
            if (mesh.vertexIdAt(i) == vertexId) {
                return i;
            }
        }
        return -1;
    }

    private static MeshTopology copyMesh(MeshTopology mesh) {
        return MeshVertexOffset.apply(mesh, new ixdar.annotations.meshnode.Vector3Value(0f, 0f, 0f));
    }
}
