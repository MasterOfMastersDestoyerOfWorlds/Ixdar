package ixdar.geometry.mesh.data.ops;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.joml.Vector3f;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;
import ixdar.geometry.mesh.data.MeshTopology;

import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.geometry.mesh.data.MeshVertexOffset;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Welds vertices within {@code distance} (same cluster). {@link ArrayMesh} inputs use a dense path;
 * other topologies still emit {@link HalfEdgeMesh}. An instance also records how the weld moved
 * elements, so per-vertex and per-face attributes can follow it.
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

    /**
     * Output vertex each input vertex ended up in, indexed by the input's dense vertex index.
     * Filled by every {@link #weld} call; empty before the first.
     */
    public int[] weldedVertex = new int[0];

    /**
     * Input face each surviving output face came from, indexed by output face. Shorter than the
     * input's face count when the weld dropped degenerate faces.
     */
    public int[] sourceFace = new int[0];

    /**
     * Build a recording welder; {@link #weld} fills its maps.
     */
    public MeshMergeByDistance() {
    }

    /**
     * Weld vertices that lie within {@code distance} of one another, recording {@link #weldedVertex}
     * and {@link #sourceFace} so the caller can carry attributes across.
     *
     * @param mesh source mesh; treated read-only
     * @param distance maximum Euclidean distance for two vertices to merge; values &lt;= 0 keep every
     *     vertex but still fill the maps
     * @return welded mesh — an {@link ArrayMesh} when the input is one, otherwise a {@link HalfEdgeMesh}
     */
    public MeshTopology weld(MeshTopology mesh, float distance) {
        if (mesh == null || mesh.vertexCount() == 0) {
            weldedVertex = new int[0];
            sourceFace = new int[0];
            return mesh instanceof ArrayMesh ? ArrayMeshEngine.emptyQuads() : new HalfEdgeMesh();
        }
        weldedVertex = new int[mesh.vertexCount()];
        sourceFace = new int[mesh.faceCount()];
        for (int index = 0; index < weldedVertex.length; index++) {
            weldedVertex[index] = index;
        }
        for (int face = 0; face < sourceFace.length; face++) {
            sourceFace[face] = face;
        }
        return merge(mesh, distance, this);
    }

    /**
     * Weld vertices that lie within {@code distance} of one another. Each cluster collapses to its
     * average position, and degenerate faces (those that visit the same merged vertex twice) are dropped.
     *
     * @param mesh source mesh; treated read-only
     * @param distance maximum Euclidean distance for two vertices to merge; values &lt;= 0 leave the mesh unchanged
     * @return welded mesh — an {@link ArrayMesh} when the input is one, otherwise a {@link HalfEdgeMesh}
     */
    public static MeshTopology merge(MeshTopology mesh, float distance) {
        return merge(mesh, distance, null);
    }

    /**
     * The body of {@link #merge}, optionally recording the element maps into {@code record}.
     *
     * @param mesh source mesh; treated read-only
     * @param distance maximum Euclidean distance for two vertices to merge
     * @param record instance whose maps to fill, or null to weld without recording
     * @return welded mesh
     */
    private static MeshTopology merge(MeshTopology mesh, float distance, MeshMergeByDistance record) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return mesh instanceof ArrayMesh ? ArrayMeshEngine.emptyQuads() : new HalfEdgeMesh();
        }
        if (distance <= NUM_0) {
            return MeshVertexOffset.apply(mesh, new Vector3Value(NUM_0, NUM_0, NUM_0));
        }
        if (mesh instanceof ArrayMesh am) {
            return mergeToArrayMesh(am, distance, record);
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
            if (record != null) {
                record.weldedVertex[i] = outVidByRoot.get(r);
            }
        }

        int keptFaces = 0;
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
                if (record != null) {
                    record.sourceFace[keptFaces] = fi;
                }
                keptFaces++;
            }
        }
        if (record != null) {
            record.sourceFace = Arrays.copyOf(record.sourceFace, keptFaces);
        }
        out.computeNormals();
        return out;
    }

    /**
     * Same clustering as {@link #merge(MeshTopology, float)} but emits a dense {@link ArrayMesh} (uniform faces only).
     * Uses primitive arrays throughout — no HashMap/Integer boxing — so it stays fast under TeaVM.
     *
     * @param mesh source mesh; faces must all share a common vertex count
     * @param distance maximum Euclidean distance for two vertices to merge
     * @throws IllegalArgumentException if {@code mesh} contains faces of differing sizes
     * @return welded {@link ArrayMesh} with averaged positions and deduplicated faces
     */
    public static ArrayMesh mergeToArrayMesh(MeshTopology mesh, float distance) {
        return mergeToArrayMesh(mesh, distance, null);
    }

    /**
     * The body of {@link #mergeToArrayMesh}, optionally recording the element maps into
     * {@code record}.
     *
     * @param mesh source mesh; faces must all share a common vertex count
     * @param distance maximum Euclidean distance for two vertices to merge
     * @param record instance whose maps to fill, or null to weld without recording
     * @throws IllegalArgumentException if {@code mesh} contains faces of differing sizes
     * @return welded {@link ArrayMesh}
     */
    private static ArrayMesh mergeToArrayMesh(MeshTopology mesh, float distance,
            MeshMergeByDistance record) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return ArrayMeshEngine.emptyQuads();
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
            if (record != null) {
                record.weldedVertex[i] = o;
            }
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
                if (record != null) {
                    record.sourceFace[outF] = fi;
                }
                outF++;
            }
        }
        if (record != null) {
            record.sourceFace = Arrays.copyOf(record.sourceFace, outF);
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
