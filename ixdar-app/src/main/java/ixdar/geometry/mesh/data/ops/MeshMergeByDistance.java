package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;

import org.joml.Vector3f;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.InvalidMeshTopologyException;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.numeric.graph.UnionFind;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Welds vertices within {@code distance} (same cluster) into a {@link HalfEdgeMesh} over primitive
 * arrays, welded vertices numbered in order of their first member. An instance also records how
 * the weld moved elements, so per-vertex and per-face attributes can follow it.
 */
public final class MeshMergeByDistance {
    public static final float MIN_CELL_SIZE = 1e-8f;
    public static final int MIX_SHIFT = 33;
    public static final long MIX_CONST_1 = 0xff51afd7ed558ccdL;
    public static final long MIX_CONST_2 = 0xc4ceb9fe1a85ec53L;
    public static final int CELL_COORD_MASK = 0x1fffff;
    public static final int CELL_COORD_BITS = 21;
    public static final int Z_AXIS_SHIFT = 42;

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

    /** Packed xyz of the welded vertices, filled by {@link #weldArrays}. */
    public float[] weldedPositions = new float[0];

    /** Corner count of each surviving face, filled by {@link #weldArrays}. */
    public int[] weldedFaceSizes = new int[0];

    /** Surviving faces' corners end to end, as welded vertex indices, filled by {@link #weldArrays}. */
    public int[] weldedFaceCorners = new int[0];

    /**
     * Build a recording welder; {@link #weld} and {@link #weldArrays} fill its maps.
     */
    public MeshMergeByDistance() {
    }

    /**
     * Weld vertices that lie within {@code distance} of one another, recording on the output each
     * welded vertex's first member and each face's source, which the slot carry follows.
     *
     * @param mesh source mesh; treated read-only
     * @param distance maximum Euclidean distance for two vertices to merge; values &lt;= 0 keep every
     *     vertex but still fill the maps
     * @throws InvalidMeshTopologyException when the welded faces have no half-edge form; a raw scan
     *     is welded by repair_mesh instead
     * @return welded mesh
     */
    public HalfEdgeMesh weld(MeshTopology mesh, float distance) {
        weldArrays(mesh, distance);
        HalfEdgeMesh out = HalfEdgeMeshOps.bulkAllocateMixed(weldedPositions, weldedFaceSizes,
                weldedFaceCorners);
        out.computeNormals();
        int[] sourceVertex = new int[weldedPositions.length / 3];
        Arrays.fill(sourceVertex, MeshTopology.NONE);
        for (int activeVertex = weldedVertex.length - 1; activeVertex >= 0; activeVertex--) {
            sourceVertex[weldedVertex[activeVertex]] = mesh.vertexIdAt(activeVertex);
        }
        int[] sourceFaceId = new int[sourceFace.length];
        for (int face = 0; face < sourceFace.length; face++) {
            sourceFaceId[face] = mesh.faceIdAt(sourceFace[face]);
        }
        out.recordSources(sourceVertex, null, sourceFaceId);
        return out;
    }

    /**
     * Weld vertices that lie within {@code distance} of one another. Each cluster collapses to its
     * average position, and degenerate faces (those that visit the same merged vertex twice) are dropped.
     *
     * @param mesh source mesh; treated read-only
     * @param distance maximum Euclidean distance for two vertices to merge; values &lt;= 0 leave the mesh unchanged
     * @return welded mesh
     */
    public static HalfEdgeMesh merge(MeshTopology mesh, float distance) {
        return new MeshMergeByDistance().weld(mesh, distance);
    }

    /**
     * The weld without building a mesh: fills {@link #weldedVertex}, {@link #sourceFace} and the
     * welded arrays, which may describe a non-manifold surface, welded vertices numbered in order
     * of their first member.
     *
     * @param mesh source mesh; treated read-only
     * @param distance maximum Euclidean distance for two vertices to merge; values &lt;= 0 keep every
     *     vertex and face
     */
    public void weldArrays(MeshTopology mesh, float distance) {
        int n = mesh == null ? 0 : mesh.vertexCount();
        weldedVertex = new int[n];
        sourceFace = new int[n == 0 ? 0 : mesh.faceCount()];
        for (int face = 0; face < sourceFace.length; face++) {
            sourceFace[face] = face;
        }
        if (n == 0) {
            weldedPositions = new float[0];
            weldedFaceSizes = new int[0];
            weldedFaceCorners = new int[0];
            return;
        }
        int[] activeVertexOfId = new int[mesh.vertexIdBound()];
        float[] vx = new float[n];
        float[] vy = new float[n];
        float[] vz = new float[n];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < n; i++) {
            int vertexId = mesh.vertexIdAt(i);
            activeVertexOfId[vertexId] = i;
            mesh.vertexPosition(vertexId, tmp);
            vx[i] = tmp.x;
            vy[i] = tmp.y;
            vz[i] = tmp.z;
        }
        int nf = mesh.faceCount();
        int[] faceSizes = new int[nf];
        int totalCorners = 0;
        for (int fi = 0; fi < nf; fi++) {
            faceSizes[fi] = mesh.faceVertexCount(mesh.faceIdAt(fi));
            totalCorners += faceSizes[fi];
        }
        int[] faceIdx = new int[totalCorners];
        if (distance <= 0f) {
            weldedPositions = new float[n * 3];
            for (int index = 0; index < n; index++) {
                weldedVertex[index] = index;
                weldedPositions[index * 3] = vx[index];
                weldedPositions[index * 3 + 1] = vy[index];
                weldedPositions[index * 3 + 2] = vz[index];
            }
            int cursor = 0;
            for (int fi = 0; fi < nf; fi++) {
                int faceId = mesh.faceIdAt(fi);
                for (int corner = 0; corner < faceSizes[fi]; corner++) {
                    faceIdx[cursor++] = activeVertexOfId[mesh.faceVertexAt(faceId, corner)];
                }
            }
            weldedFaceSizes = faceSizes;
            weldedFaceCorners = faceIdx;
            return;
        }

        float cell = Math.max(distance, MIN_CELL_SIZE);

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
            long pk = packCell(cellGx[i], cellGy[i], cellGz[i]);
            int slot = (int) (mixKey(pk) & gridMask);
            while (gridKeys[slot] != Long.MIN_VALUE && gridKeys[slot] != pk) {
                slot = (slot + 1) & gridMask;
            }
            if (gridKeys[slot] == Long.MIN_VALUE) {
                gridKeys[slot] = pk;
            }
            nextInBucket[i] = gridHead[slot];
            gridHead[slot] = i;
        }

        int[] parent = UnionFind.singletons(n);

        float distSq = distance * distance;
        for (int i = 0; i < n; i++) {
            int gx = cellGx[i];
            int gy = cellGy[i];
            int gz = cellGz[i];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long nk = packCell(gx + dx, gy + dy, gz + dz);
                        int slot = (int) (mixKey(nk) & gridMask);
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
                                    UnionFind.union(parent, i, j);
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
            rootOf[i] = UnionFind.find(parent, i);
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
            weldedVertex[i] = o;
        }

        float[] positions = new float[outV * 3];
        for (int o = 0; o < outV; o++) {
            float inv = 1f / count[o];
            positions[o * 3] = sumX[o] * inv;
            positions[o * 3 + 1] = sumY[o] * inv;
            positions[o * 3 + 2] = sumZ[o] * inv;
        }

        int outF = 0;
        int outCorners = 0;
        for (int fi = 0; fi < nf; fi++) {
            int faceId = mesh.faceIdAt(fi);
            int size = faceSizes[fi];
            for (int k = 0; k < size; k++) {
                faceIdx[outCorners + k] = rootToOut[rootOf[activeVertexOfId[mesh.faceVertexAt(faceId, k)]]];
            }
            boolean dup = false;
            for (int a = 0; a < size && !dup; a++) {
                for (int b = a + 1; b < size; b++) {
                    if (faceIdx[outCorners + a] == faceIdx[outCorners + b]) {
                        dup = true;
                        break;
                    }
                }
            }
            if (!dup) {
                faceSizes[outF] = size;
                sourceFace[outF] = fi;
                outF++;
                outCorners += size;
            }
        }
        sourceFace = Arrays.copyOf(sourceFace, outF);
        weldedPositions = positions;
        weldedFaceSizes = Arrays.copyOf(faceSizes, outF);
        weldedFaceCorners = Arrays.copyOf(faceIdx, outCorners);
    }

    /**
     * Avalanche mixer that spreads a packed cell key over the whole word, so neighbouring cells
     * do not land in neighbouring hash slots.
     *
     * @param key packed cell key from {@link #packCell}
     * @return well-distributed hash of the key
     */
    public static long mixKey(long key) {
        long x = key;
        x ^= (x >>> MIX_SHIFT);
        x *= MIX_CONST_1;
        x ^= (x >>> MIX_SHIFT);
        x *= MIX_CONST_2;
        x ^= (x >>> MIX_SHIFT);
        return x;
    }

    /**
     * Packs one grid cell's integer coordinates into a single key, 21 bits per axis.
     *
     * @param gridX cell index along x
     * @param gridY cell index along y
     * @param gridZ cell index along z
     * @return the three coordinates packed into one long
     */
    public static long packCell(int gridX, int gridY, int gridZ) {
        return ((long) gridX & CELL_COORD_MASK)
                | (((long) gridY & CELL_COORD_MASK) << CELL_COORD_BITS)
                | (((long) gridZ & CELL_COORD_MASK) << Z_AXIS_SHIFT);
    }
}
