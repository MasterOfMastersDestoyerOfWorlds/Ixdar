package ixdar.geometry.mesh.data;

import java.util.HashMap;
import java.util.HashSet;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.BoolField;

/**
 * Deletes selected edges (and their incident faces), producing a new {@link HalfEdgeMesh}.
 * Isolated vertices left after face removal are also deleted.
 */
public final class MeshDeleteEdges {

    private MeshDeleteEdges() {
    }

    public static HalfEdgeMesh delete(MeshTopology mesh, Object selectionObj) {
        if (mesh == null || mesh.edgeCount() == 0) {
            return new HalfEdgeMesh();
        }
        int ne = mesh.edgeCount();
        boolean deleteAll = false;
        boolean[] delEdge = new boolean[ne];
        if (selectionObj instanceof BoolField bf) {
            int len = Math.min(bf.length(), ne);
            for (int i = 0; i < len; i++) {
                delEdge[i] = bf.get(i);
            }
        } else if (selectionObj instanceof Boolean b) {
            deleteAll = b;
        }

        if (deleteAll) {
            return new HalfEdgeMesh();
        }

        boolean any = false;
        for (boolean b : delEdge) {
            if (b) { any = true; break; }
        }
        if (!any) {
            return MeshVertexOffset.apply(mesh, new ixdar.annotations.meshnode.Vector3Value(0f, 0f, 0f));
        }

        HashSet<Integer> deadEdgeIds = new HashSet<>();
        for (int i = 0; i < ne; i++) {
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

        HalfEdgeMesh out = new HalfEdgeMesh();
        HashMap<Integer, Integer> vertMap = new HashMap<>();
        Vector3f p = new Vector3f();
        for (int vi = 0; vi < mesh.vertexCount(); vi++) {
            int vid = mesh.vertexIdAt(vi);
            if (!referencedVerts.contains(vid)) {
                continue;
            }
            mesh.vertexPosition(vid, p);
            int nid = out.addVertex(p);
            vertMap.put(vid, nid);
        }

        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            if (deadFaceIds.contains(fid)) {
                continue;
            }
            int fvc = mesh.faceVertexCount(fid);
            int[] nv = new int[fvc];
            for (int k = 0; k < fvc; k++) {
                nv[k] = vertMap.get(mesh.faceVertexAt(fid, k));
            }
            out.addFace(nv);
        }
        out.computeNormals();
        return out;
    }
}
