package benchmark;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * The largest connected manifold piece of a raw scan, for benchmarks that need a half-edge mesh
 * before the repair stage (CRAW-26) exists.
 *
 * <p>
 * A scan keeps faces that reuse a directed edge or give an edge three neighbours; those are
 * dropped greedily in face order, then only the biggest surviving component is kept.
 */
public final class ManifoldShell {

    /** Corners of a triangle, which is also the stride of a packed xyz position. */
    public static final int TRIANGLE_CORNERS = 3;

    /** Faces of the welded input, before anything was dropped. */
    public int inputFaceCount;

    /** Faces that survived the non-manifold drop. */
    public int manifoldFaceCount;

    /** Faces of the largest component, the ones the returned mesh carries. */
    public int keptFaceCount;

    /** Vertices of the largest component. */
    public int keptVertexCount;

    /**
     * Builds the largest manifold component of {@code welded} as a half-edge mesh.
     *
     * @param welded scan whose coincident vertices have already been merged
     * @return the manifold surface, with this object's counters filled in
     */
    public HalfEdgeMesh of(ArrayMesh welded) {
        float[] positions = welded.copyPositions();
        int[] faces = welded.copyFaceIndices();
        inputFaceCount = faces.length / TRIANGLE_CORNERS;
        Set<Long> usedDirectedEdges = new HashSet<>();
        Map<Long, Integer> undirectedEdgeUses = new HashMap<>();
        int[] kept = new int[faces.length];
        int keptCount = 0;
        for (int face = 0; face < inputFaceCount; face++) {
            int cornerA = faces[TRIANGLE_CORNERS * face];
            int cornerB = faces[TRIANGLE_CORNERS * face + 1];
            int cornerC = faces[TRIANGLE_CORNERS * face + 2];
            if (cornerA == cornerB || cornerB == cornerC || cornerA == cornerC
                    || !edgesAreFree(usedDirectedEdges, undirectedEdgeUses, cornerA, cornerB,
                            cornerC)) {
                continue;
            }
            claimEdge(usedDirectedEdges, undirectedEdgeUses, cornerA, cornerB);
            claimEdge(usedDirectedEdges, undirectedEdgeUses, cornerB, cornerC);
            claimEdge(usedDirectedEdges, undirectedEdgeUses, cornerC, cornerA);
            kept[keptCount] = cornerA;
            kept[keptCount + 1] = cornerB;
            kept[keptCount + 2] = cornerC;
            keptCount += TRIANGLE_CORNERS;
        }
        manifoldFaceCount = keptCount / TRIANGLE_CORNERS;

        int vertexCount = positions.length / TRIANGLE_CORNERS;
        int[] componentRoot = new int[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            componentRoot[vertex] = vertex;
        }
        for (int corner = 0; corner < keptCount; corner += TRIANGLE_CORNERS) {
            union(componentRoot, kept[corner], kept[corner + 1]);
            union(componentRoot, kept[corner + 1], kept[corner + 2]);
        }
        int[] componentFaces = new int[vertexCount];
        for (int corner = 0; corner < keptCount; corner += TRIANGLE_CORNERS) {
            componentFaces[root(componentRoot, kept[corner])]++;
        }
        int biggestRoot = 0;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            if (componentFaces[vertex] > componentFaces[biggestRoot]) {
                biggestRoot = vertex;
            }
        }

        int[] compacted = new int[vertexCount];
        Arrays.fill(compacted, -1);
        float[] outPositions = new float[positions.length];
        int[] outFaces = new int[keptCount];
        int outVertexCount = 0;
        int outFaceCorners = 0;
        for (int corner = 0; corner < keptCount; corner += TRIANGLE_CORNERS) {
            if (root(componentRoot, kept[corner]) != biggestRoot) {
                continue;
            }
            for (int offset = 0; offset < TRIANGLE_CORNERS; offset++) {
                int vertex = kept[corner + offset];
                if (compacted[vertex] < 0) {
                    compacted[vertex] = outVertexCount;
                    System.arraycopy(positions, TRIANGLE_CORNERS * vertex, outPositions,
                            TRIANGLE_CORNERS * outVertexCount, TRIANGLE_CORNERS);
                    outVertexCount++;
                }
                outFaces[outFaceCorners + offset] = compacted[vertex];
            }
            outFaceCorners += TRIANGLE_CORNERS;
        }
        keptFaceCount = outFaceCorners / TRIANGLE_CORNERS;
        keptVertexCount = outVertexCount;
        return HalfEdgeMeshEngine.buildFromIndexedMesh(
                Arrays.copyOf(outPositions, TRIANGLE_CORNERS * outVertexCount),
                Arrays.copyOf(outFaces, outFaceCorners));
    }

    private static boolean edgesAreFree(Set<Long> usedDirectedEdges,
            Map<Long, Integer> undirectedEdgeUses, int cornerA, int cornerB, int cornerC) {
        return edgeIsFree(usedDirectedEdges, undirectedEdgeUses, cornerA, cornerB)
                && edgeIsFree(usedDirectedEdges, undirectedEdgeUses, cornerB, cornerC)
                && edgeIsFree(usedDirectedEdges, undirectedEdgeUses, cornerC, cornerA);
    }

    private static boolean edgeIsFree(Set<Long> usedDirectedEdges,
            Map<Long, Integer> undirectedEdgeUses, int from, int to) {
        return !usedDirectedEdges.contains(directedKey(from, to))
                && undirectedEdgeUses.getOrDefault(undirectedKey(from, to), 0) < 2;
    }

    private static void claimEdge(Set<Long> usedDirectedEdges,
            Map<Long, Integer> undirectedEdgeUses, int from, int to) {
        usedDirectedEdges.add(directedKey(from, to));
        undirectedEdgeUses.merge(undirectedKey(from, to), 1, Integer::sum);
    }

    private static long directedKey(int from, int to) {
        return ((long) from << Integer.SIZE) | Integer.toUnsignedLong(to);
    }

    private static long undirectedKey(int from, int to) {
        return from < to ? directedKey(from, to) : directedKey(to, from);
    }

    private static int root(int[] componentRoot, int vertex) {
        int walk = vertex;
        while (componentRoot[walk] != walk) {
            componentRoot[walk] = componentRoot[componentRoot[walk]];
            walk = componentRoot[walk];
        }
        return walk;
    }

    private static void union(int[] componentRoot, int first, int second) {
        int firstRoot = root(componentRoot, first);
        int secondRoot = root(componentRoot, second);
        if (firstRoot != secondRoot) {
            componentRoot[firstRoot] = secondRoot;
        }
    }
}
