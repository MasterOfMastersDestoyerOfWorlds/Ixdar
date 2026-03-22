package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.joml.Vector3f;

/**
 * Welds vertices within {@code distance} (same cluster), producing a new {@link HalfEdgeMesh}.
 */
public final class MeshMergeByDistance {

    private MeshMergeByDistance() {
    }

    public static HalfEdgeMesh merge(MeshTopology mesh, float distance) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return new HalfEdgeMesh();
        }
        if (distance <= 0f) {
            return MeshVertexOffset.apply(mesh, new ixdar.annotations.meshnode.Vector3Value(0f, 0f, 0f));
        }

        int n = mesh.vertexCount();
        Vector3f[] pos = new Vector3f[n];
        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            pos[i] = mesh.vertexPosition(vid, new Vector3f());
        }

        float cell = Math.max(distance, 1e-8f);
        HashMap<Long, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < n; i++) {
            long key = key(pos[i], cell);
            grid.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }

        for (int i = 0; i < n; i++) {
            int gx = (int) Math.floor(pos[i].x / cell);
            int gy = (int) Math.floor(pos[i].y / cell);
            int gz = (int) Math.floor(pos[i].z / cell);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long nk = pack(gx + dx, gy + dy, gz + dz);
                        List<Integer> bucket = grid.get(nk);
                        if (bucket == null) {
                            continue;
                        }
                        for (int j : bucket) {
                            if (j <= i) {
                                continue;
                            }
                            if (pos[i].distance(pos[j]) < distance) {
                                union(parent, i, j);
                            }
                        }
                    }
                }
            }
        }

        int[] rootOf = new int[n];
        for (int i = 0; i < n; i++) {
            rootOf[i] = find(parent, i);
        }

        HashMap<Integer, Vector3f> sumByRoot = new HashMap<>();
        HashMap<Integer, Integer> countByRoot = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int r = rootOf[i];
            sumByRoot.computeIfAbsent(r, k -> new Vector3f()).add(pos[i]);
            countByRoot.merge(r, 1, Integer::sum);
        }

        HashMap<Integer, Integer> outVidByRoot = new HashMap<>();
        HalfEdgeMesh out = new HalfEdgeMesh();
        Vector3f tmp = new Vector3f();
        for (int r : sumByRoot.keySet()) {
            int cnt = countByRoot.get(r);
            tmp.set(sumByRoot.get(r)).mul(1f / cnt);
            int oid = out.addVertex(tmp);
            outVidByRoot.put(r, oid);
        }

        HashMap<Integer, Integer> meshVidToOutVid = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            int r = rootOf[i];
            meshVidToOutVid.put(vid, outVidByRoot.get(r));
        }

        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int fc = mesh.faceVertexCount(fid);
            int[] nv = new int[fc];
            for (int k = 0; k < fc; k++) {
                int ov = mesh.faceVertexAt(fid, k);
                nv[k] = meshVidToOutVid.get(ov);
            }
            boolean dup = false;
            for (int a = 0; a < fc; a++) {
                for (int b = a + 1; b < fc; b++) {
                    if (nv[a] == nv[b]) {
                        dup = true;
                        break;
                    }
                }
            }
            if (!dup) {
                out.addFace(nv);
            }
        }
        out.computeNormals();
        return out;
    }

    private static long key(Vector3f p, float cell) {
        int gx = (int) Math.floor(p.x / cell);
        int gy = (int) Math.floor(p.y / cell);
        int gz = (int) Math.floor(p.z / cell);
        return pack(gx, gy, gz);
    }

    private static long pack(int gx, int gy, int gz) {
        return ((long) gx & 0x1fffff) | (((long) gy & 0x1fffff) << 21) | (((long) gz & 0x1fffff) << 42);
    }

    private static int find(int[] parent, int i) {
        if (parent[i] != i) {
            parent[i] = find(parent, parent[i]);
        }
        return parent[i];
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }
}
