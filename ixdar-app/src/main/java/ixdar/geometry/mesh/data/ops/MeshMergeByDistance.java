package ixdar.geometry.mesh.data.ops;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.MeshVertexOffset;

/**
 * Welds vertices within {@code distance} (same cluster). {@link ArrayMesh} inputs use a dense path;
 * other topologies still emit {@link HalfEdgeMesh}.
 */
public final class MeshMergeByDistance {
    public static final int NUM_4 = 4;
    public static final float NUM_0 = 0f;
    public static final float NUM_1e_8 = 1e-8f;
    public static final float NUM_1 = 1f;
    public static final int NUM_3 = 3;
    public static final int NUM_33 = 33;
    public static final long NUM_0xff51afd7ed558ccd = 0xff51afd7ed558ccdL;
    public static final long NUM_0xc4ceb9fe1a85ec53 = 0xc4ceb9fe1a85ec53L;
    public static final int NUM_0x1ffff = 0x1fffff;
    public static final int NUM_21 = 21;
    public static final int NUM_42 = 42;

    private MeshMergeByDistance() {
    }

    /**
     * TODO: document {@code merge}.
     *
     * @param mesh TODO: describe
     * @param distance TODO: describe
     * @return TODO: describe
     */
    public static MeshTopology merge(MeshTopology mesh, float distance) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return mesh instanceof ArrayMesh ? new ArrayMesh(new float[0], null, new int[0], NUM_4) : new HalfEdgeMesh();
        }
        if (distance <= NUM_0) {
            return MeshVertexOffset.apply(mesh, new ixdar.annotations.meshnode.Vector3Value(NUM_0, NUM_0, NUM_0));
        }
        if (mesh instanceof ArrayMesh am) {
            return mergeToArrayMesh(am, distance);
        }

        int n = mesh.vertexCount();
        Vector3f[] pos = new Vector3f[n];
        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            pos[i] = mesh.vertexPosition(vid, new Vector3f());
        }

        float cell = Math.max(distance, NUM_1e_8);
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
            countByRoot.merge(r, 1, (a, b) -> a + b);
        }

        HashMap<Integer, Integer> outVidByRoot = new HashMap<>();
        HalfEdgeMesh out = new HalfEdgeMesh();
        Vector3f tmp = new Vector3f();
        for (int r : sumByRoot.keySet()) {
            int cnt = countByRoot.get(r);
            tmp.set(sumByRoot.get(r)).mul(NUM_1 / cnt);
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

    /**
     * Same clustering as {@link #merge(MeshTopology, float)} but emits a dense {@link ArrayMesh} (uniform faces only).
     * Uses primitive arrays throughout — no HashMap/Integer boxing — so it stays fast under TeaVM.
     *
     * @param mesh TODO: describe
     * @param distance TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh mergeToArrayMesh(MeshTopology mesh, float distance) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return new ArrayMesh(new float[0], null, new int[0], NUM_4);
        }
        int n = mesh.vertexCount();

        // Flat position array (no Vector3f[] allocation)
        float[] vx = new float[n];
        float[] vy = new float[n];
        float[] vz = new float[n];
        // Map from sparse vertex id → dense index [0,n). ArrayMesh already has this identity,
        // but HalfEdgeMesh may have gaps.
        int maxSparseId = -1;
        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            if (vid > maxSparseId) {
                maxSparseId = vid;
            }
        }
        int[] sparseToDense = new int[maxSparseId + 1];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < n; i++) {
            int vid = mesh.vertexIdAt(i);
            mesh.vertexPosition(vid, tmp);
            vx[i] = tmp.x;
            vy[i] = tmp.y;
            vz[i] = tmp.z;
            sparseToDense[vid] = i;
        }

        float cell = Math.max(distance, NUM_1e_8);

        // Spatial grid: sort vertex indices by cell-key via counting sort on 32-bit packed cell coords.
        // Then for each vertex, iterate the 27 neighbor cells; bucket membership is an int[] range.
        int[] cellGx = new int[n];
        int[] cellGy = new int[n];
        int[] cellGz = new int[n];
        for (int i = 0; i < n; i++) {
            cellGx[i] = (int) Math.floor(vx[i] / cell);
            cellGy[i] = (int) Math.floor(vy[i] / cell);
            cellGz[i] = (int) Math.floor(vz[i] / cell);
        }

        // Open-addressing Long→int-head map, with int[] chain of vertex indices in bucket.
        // Power-of-two sized (load factor ~0.5).
        int gridCap = 1;
        while (gridCap < n * 2) {
            gridCap <<= 1;
        }
        int gridMask = gridCap - 1;
        long[] gridKeys = new long[gridCap];
        int[] gridHead = new int[gridCap];
        Arrays.fill(gridKeys, Long.MIN_VALUE);
        Arrays.fill(gridHead, -1);
        int[] nextInBucket = new int[n];

        for (int i = 0; i < n; i++) {
            long pk = pack(cellGx[i], cellGy[i], cellGz[i]);
            int slot = (int) (mix(pk) & gridMask);
            while (gridKeys[slot] != Long.MIN_VALUE && gridKeys[slot] != pk) {
                slot = (slot + 1) & gridMask;
            }
            if (gridKeys[slot] == Long.MIN_VALUE) {
                gridKeys[slot] = pk;
            }
            nextInBucket[i] = gridHead[slot];
            gridHead[slot] = i;
        }

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }

        float distSq = distance * distance;
        for (int i = 0; i < n; i++) {
            int gx = cellGx[i];
            int gy = cellGy[i];
            int gz = cellGz[i];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long nk = pack(gx + dx, gy + dy, gz + dz);
                        int slot = (int) (mix(nk) & gridMask);
                        while (gridKeys[slot] != Long.MIN_VALUE && gridKeys[slot] != nk) {
                            slot = (slot + 1) & gridMask;
                        }
                        if (gridKeys[slot] != nk) {
                            continue;
                        }
                        int j = gridHead[slot];
                        while (j >= 0) {
                            if (j > i) {
                                float ex = vx[i] - vx[j];
                                float ey = vy[i] - vy[j];
                                float ez = vz[i] - vz[j];
                                if (ex * ex + ey * ey + ez * ez < distSq) {
                                    union(parent, i, j);
                                }
                            }
                            j = nextInBucket[j];
                        }
                    }
                }
            }
        }

        int[] rootOf = new int[n];
        for (int i = 0; i < n; i++) {
            rootOf[i] = find(parent, i);
        }

        // Assign dense output IDs per root. rootToOut[root] = new index, or -1 if unassigned.
        int[] rootToOut = new int[n];
        Arrays.fill(rootToOut, -1);
        float[] sumX = new float[n];
        float[] sumY = new float[n];
        float[] sumZ = new float[n];
        int[] count = new int[n];
        int outV = 0;
        for (int i = 0; i < n; i++) {
            int r = rootOf[i];
            if (rootToOut[r] < 0) {
                rootToOut[r] = outV++;
            }
            int o = rootToOut[r];
            sumX[o] += vx[i];
            sumY[o] += vy[i];
            sumZ[o] += vz[i];
            count[o]++;
        }

        float[] positions = new float[outV * NUM_3];
        for (int o = 0; o < outV; o++) {
            float inv = NUM_1 / count[o];
            positions[o * NUM_3] = sumX[o] * inv;
            positions[o * NUM_3 + 1] = sumY[o] * inv;
            positions[o * NUM_3 + 2] = sumZ[o] * inv;
        }

        int nf = mesh.faceCount();
        if (nf == 0) {
            return new ArrayMesh(positions, null, new int[0], NUM_4);
        }
        int vpf = mesh.faceVertexCount(mesh.faceIdAt(0));
        for (int fi = 0; fi < nf; fi++) {
            if (mesh.faceVertexCount(mesh.faceIdAt(fi)) != vpf) {
                throw new IllegalArgumentException("mergeToArrayMesh requires uniform face size");
            }
        }
        int[] faceIdx = new int[nf * vpf];
        int outF = 0;
        int[] nv = new int[vpf];
        for (int fi = 0; fi < nf; fi++) {
            int fid = mesh.faceIdAt(fi);
            for (int k = 0; k < vpf; k++) {
                int ov = mesh.faceVertexAt(fid, k);
                int dense = sparseToDense[ov];
                nv[k] = rootToOut[rootOf[dense]];
            }
            boolean dup = false;
            for (int a = 0; a < vpf && !dup; a++) {
                for (int b = a + 1; b < vpf; b++) {
                    if (nv[a] == nv[b]) {
                        dup = true;
                        break;
                    }
                }
            }
            if (!dup) {
                System.arraycopy(nv, 0, faceIdx, outF * vpf, vpf);
                outF++;
            }
        }
        ArrayMesh out = new ArrayMesh(positions, null, Arrays.copyOf(faceIdx, outF * vpf), vpf);
        out.computeNormals();
        return out;
    }

    private static long mix(long x) {
        x ^= (x >>> NUM_33);
        x *= NUM_0xff51afd7ed558ccd;
        x ^= (x >>> NUM_33);
        x *= NUM_0xc4ceb9fe1a85ec53;
        x ^= (x >>> NUM_33);
        return x;
    }

    private static long key(Vector3f p, float cell) {
        int gx = (int) Math.floor(p.x / cell);
        int gy = (int) Math.floor(p.y / cell);
        int gz = (int) Math.floor(p.z / cell);
        return pack(gx, gy, gz);
    }

    private static long pack(int gx, int gy, int gz) {
        return ((long) gx & NUM_0x1ffff) | (((long) gy & NUM_0x1ffff) << NUM_21) | (((long) gz & NUM_0x1ffff) << NUM_42);
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
