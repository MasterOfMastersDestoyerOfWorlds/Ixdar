package ixdar.geometry.mesh.quadlayout.boundary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.data.ArrayMesh;

/**
 * Cap-and-recut pre-pass for the QGP pipeline. Detects boundary loops on a
 * triangle mesh, fills each loop with a fan triangulation centred at the loop
 * centroid, and reports the metadata needed by {@link BoundaryUncapper} to
 * reverse the operation after quad extraction.
 *
 * <p>The free-boundary alternative (Lyon-Campen-Bommes-Kobbelt 2019) is left
 * as a v2 option if the cap-and-recut quality near holes proves insufficient.
 */
public final class BoundaryCapper {

    private static final int FLOATS_PER_VERTEX = 3;
    private static final int VERTS_PER_TRI = 3;

    private BoundaryCapper() {
    }

    /**
     * Result of capping. {@code closedMesh} is the input mesh with all
     * boundary loops fan-triangulated. {@code capFaceIds} are the indices of
     * the newly added cap triangles in {@code closedMesh}; {@code capVertexIds}
     * are the indices of the cap-centroid vertices (one per loop).
     * {@code originalLoops} preserves the input loops in CCW (outward) order
     * for downstream consumers.
     */
    public record CapResult(
            ArrayMesh closedMesh,
            int[] capFaceIds,
            int[] capVertexIds,
            List<int[]> originalLoops) {
    }

    public static CapResult cap(ArrayMesh mesh) {
        if (mesh.getVertsPerFace() != VERTS_PER_TRI) {
            throw new IllegalArgumentException("BoundaryCapper requires a triangle mesh");
        }

        float[] positions = mesh.copyPositions();
        int[] faceIndices = mesh.copyFaceIndices();
        int triCount = faceIndices.length / VERTS_PER_TRI;
        int vertexCount = positions.length / FLOATS_PER_VERTEX;

        List<int[]> rawLoops = extractBoundaryLoops(faceIndices, triCount);
        if (rawLoops.isEmpty()) {
            return new CapResult(mesh, new int[0], new int[0], List.of());
        }

        // Boundary half-edges already run in the direction dictated by the
        // surrounding face winding. Cap triangles use winding
        // (centroid, loop[i+1], loop[i]) so that each cap rim half-edge
        // (loop[i+1] -> loop[i]) is the reverse twin of the boundary
        // half-edge (loop[i] -> loop[i+1]) — i.e., the cap is automatically
        // wound consistently with the rest of the mesh and no flip is needed.
        // The free-boundary alternative (Lyon-Campen-Bommes-Kobbelt 2019) is
        // a v2 option if cap-and-recut quality near holes proves insufficient.
        List<int[]> orientedLoops = rawLoops;

        int newVertexCount = vertexCount + orientedLoops.size();
        float[] newPositions = Arrays.copyOf(positions, newVertexCount * FLOATS_PER_VERTEX);

        int totalCapTris = 0;
        for (int[] loop : orientedLoops) {
            totalCapTris += loop.length;
        }
        int[] newFaceIndices = Arrays.copyOf(faceIndices, (triCount + totalCapTris) * VERTS_PER_TRI);

        int[] capVertexIds = new int[orientedLoops.size()];
        int[] capFaceIds = new int[totalCapTris];
        int faceCursor = triCount;
        int capFaceCursor = 0;
        for (int loopIdx = 0; loopIdx < orientedLoops.size(); loopIdx++) {
            int[] loop = orientedLoops.get(loopIdx);
            int centroidVertex = vertexCount + loopIdx;
            capVertexIds[loopIdx] = centroidVertex;
            float cx = 0f;
            float cy = 0f;
            float cz = 0f;
            for (int v : loop) {
                int o = v * FLOATS_PER_VERTEX;
                cx += positions[o];
                cy += positions[o + 1];
                cz += positions[o + 2];
            }
            float inv = 1f / loop.length;
            int co = centroidVertex * FLOATS_PER_VERTEX;
            newPositions[co] = cx * inv;
            newPositions[co + 1] = cy * inv;
            newPositions[co + 2] = cz * inv;
            for (int i = 0; i < loop.length; i++) {
                int a = loop[i];
                int b = loop[(i + 1) % loop.length];
                int idx = faceCursor * VERTS_PER_TRI;
                newFaceIndices[idx] = centroidVertex;
                newFaceIndices[idx + 1] = b;
                newFaceIndices[idx + 2] = a;
                capFaceIds[capFaceCursor++] = faceCursor;
                faceCursor++;
            }
        }

        ArrayMesh closed = new ArrayMesh(newPositions, null, newFaceIndices, VERTS_PER_TRI);
        closed.computeNormals();
        return new CapResult(closed, capFaceIds, capVertexIds, orientedLoops);
    }

    private static List<int[]> extractBoundaryLoops(int[] faceIndices, int triCount) {
        // Count occurrences per directed edge (a,b); a half-edge whose reverse
        // (b,a) does not appear is a boundary half-edge.
        Map<Long, int[]> directed = new HashMap<>(triCount * 3);
        for (int t = 0; t < triCount; t++) {
            int base = t * VERTS_PER_TRI;
            int v0 = faceIndices[base];
            int v1 = faceIndices[base + 1];
            int v2 = faceIndices[base + 2];
            countDirected(directed, v0, v1);
            countDirected(directed, v1, v2);
            countDirected(directed, v2, v0);
        }

        Map<Integer, Integer> nextOnBoundary = new HashMap<>();
        for (Map.Entry<Long, int[]> e : directed.entrySet()) {
            long key = e.getKey();
            int[] count = e.getValue();
            int a = (int) (key >>> 32);
            int b = (int) (key & 0xFFFFFFFFL);
            long reverseKey = (((long) b) << 32) | (a & 0xFFFFFFFFL);
            int[] reverseCount = directed.get(reverseKey);
            int reverse = reverseCount == null ? 0 : reverseCount[0];
            if (count[0] - reverse > 0) {
                if (nextOnBoundary.containsKey(a)) {
                    // Non-manifold boundary; we still walk something — first
                    // edge wins. This keeps the algorithm robust on slightly
                    // malformed meshes.
                    continue;
                }
                nextOnBoundary.put(a, b);
            }
        }

        List<int[]> loops = new ArrayList<>();
        Map<Integer, Boolean> visited = new HashMap<>();
        for (int start : nextOnBoundary.keySet()) {
            if (Boolean.TRUE.equals(visited.get(start))) {
                continue;
            }
            List<Integer> loop = new ArrayList<>();
            int cur = start;
            int safety = 0;
            int max = nextOnBoundary.size() + 1;
            while (!Boolean.TRUE.equals(visited.get(cur)) && safety++ <= max) {
                visited.put(cur, Boolean.TRUE);
                loop.add(cur);
                Integer nxt = nextOnBoundary.get(cur);
                if (nxt == null) {
                    break;
                }
                cur = nxt;
                if (cur == start) {
                    break;
                }
            }
            if (loop.size() >= 3 && cur == start) {
                int[] arr = new int[loop.size()];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = loop.get(i);
                }
                loops.add(arr);
            }
        }
        return loops;
    }

    private static void countDirected(Map<Long, int[]> map, int a, int b) {
        long key = (((long) a) << 32) | (b & 0xFFFFFFFFL);
        int[] c = map.get(key);
        if (c == null) {
            map.put(key, new int[]{1});
        } else {
            c[0]++;
        }
    }

}
