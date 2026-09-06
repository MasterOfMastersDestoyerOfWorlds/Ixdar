package benchmark;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;
import ixdar.geometry.mesh.data.paths.FlipGeodesics;
import ixdar.geometry.mesh.data.paths.GeodesicSeedPath;
import ixdar.geometry.mesh.data.paths.IntrinsicPathTracer;
import ixdar.geometry.mesh.data.paths.IntrinsicTriangulation;
import ixdar.geometry.mesh.data.paths.NearestVertex;
import ixdar.geometry.mesh.data.paths.TracedSurfacePath;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * Times FlipOut on a full-resolution crawfish scan — not part of the default test globs
 * (package {@code benchmark}); run explicitly:
 *
 * <pre>
 * mvn test -pl ixdar-app -P test -Dtest=FlipGeodesicsScanBenchmark \
 *     -Dbenchmark.geodesicMesh=/home/acw/crawfish/IMG_4109.glb
 * </pre>
 *
 * <p>
 * Each round rebuilds the intrinsic triangulation, because FlipOut consumes it by flipping. The
 * three stages are timed apart so the warm FlipOut cost — the number the ticket targets at under
 * 100 ms — is not hidden inside the one-off setup.
 */
public final class FlipGeodesicsScanBenchmark {

    /** Input-file entry point: the scan the loop is tightened on. */
    private static final String MESH_PROPERTY = "benchmark.geodesicMesh";

    /** Scan used when the property is absent. */
    private static final String DEFAULT_MESH = "/home/acw/crawfish/IMG_4109.glb";

    /**
     * Three surface points spread around one walking leg at {@code z ~= 0.4}, at three different
     * heights along it so the seed walk spirals instead of following one cross-section.
     */
    private static final float[][] LEG_WAYPOINTS = {
        { 0.158970f, -0.111934f, 0.400764f },
        { 0.107179f, -0.108244f, 0.428180f },
        { 0.127260f, -0.110376f, 0.366955f },
    };

    /**
     * Three points around the abdomen at {@code z ~= -0.3}, a girth loop an order of magnitude
     * longer than the leg loop, kept as the scaling data point.
     */
    private static final float[][] BODY_WAYPOINTS = {
        { 0.142617f, 0.015641f, -0.297141f },
        { -0.074982f, 0.134666f, -0.283574f },
        { -0.054713f, -0.066467f, -0.319990f },
    };

    /** Timed rounds; the first ones are discarded as warm-up. */
    private static final int ROUNDS = 6;

    /** Rounds discarded before the warm numbers are collected. */
    private static final int WARMUP_ROUNDS = 2;

    /** Nanoseconds in a millisecond. */
    private static final double NANOS_PER_MILLI = 1e6;

    /**
     * Weld radius applied before anything else. The scan's glTF indices split vertices at texture
     * seams, which leaves 19255 index-connected components on IMG_4109; welding coincident
     * positions restores one surface (7 components, 460459 of 468350 vertices in the largest).
     */
    private static final float WELD_DISTANCE = 1e-6f;

    /** Corners of a triangle, which is also the stride of a packed xyz position. */
    private static final int TRIANGLE_CORNERS = 3;

    @Test
    public void tightenCrawfishLegLoop() throws IOException {
        String meshPath = System.getProperty(MESH_PROPERTY, DEFAULT_MESH);
        long loadStart = System.nanoTime();
        ArrayMesh loaded = MeshLoader.load(meshPath);
        System.out.printf("[flip-geodesics] loader: %d vertices, %d faces%n",
                loaded.vertexCount(), loaded.faceCount());
        ArrayMesh welded = MeshMergeByDistance.mergeToArrayMesh(loaded, WELD_DISTANCE);
        System.out.printf("[flip-geodesics] weld: %d vertices, %d faces%n",
                welded.vertexCount(), welded.faceCount());
        HalfEdgeMesh mesh = largestManifoldComponent(welded);
        double loadMillis = (System.nanoTime() - loadStart) / NANOS_PER_MILLI;
        System.out.printf(
                "[flip-geodesics] mesh %s: %d vertices, %d faces, %d edges, load+weld %.1f ms%n",
                meshPath, mesh.vertexCount(), mesh.faceCount(), mesh.edgeCount(), loadMillis);

        timeLoop("leg", mesh, LEG_WAYPOINTS);
        timeLoop("body", mesh, BODY_WAYPOINTS);
    }

    /**
     * Times one seed loop through {@code ROUNDS} rounds and prints the warm-best stage split.
     *
     * @param name      label printed with every line of this loop's timings
     * @param mesh      manifold scan surface the loop is tightened on
     * @param waypoints surface points the seed walk passes through
     */
    private static void timeLoop(String name, HalfEdgeMesh mesh, float[][] waypoints) {
        int[] waypointVertexIds = new int[waypoints.length];
        for (int index = 0; index < waypointVertexIds.length; index++) {
            float[] point = waypoints[index];
            waypointVertexIds[index] = NearestVertex.find(mesh, point[0], point[1], point[2]);
        }

        double bestSetupMillis = Double.POSITIVE_INFINITY;
        double bestSeedMillis = Double.POSITIVE_INFINITY;
        double bestFlipMillis = Double.POSITIVE_INFINITY;
        double bestTraceMillis = Double.POSITIVE_INFINITY;
        int seedEdges = 0;
        int tightEdges = 0;
        int tracePoints = 0;
        double seedLength = 0.0;
        double tightLength = 0.0;
        long flips = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long setupStart = System.nanoTime();
            IntrinsicTriangulation intrinsic = IntrinsicTriangulation.over(mesh);
            long setupEnd = System.nanoTime();
            IntrinsicPathTracer tracer = IntrinsicPathTracer.snapshotOf(intrinsic);
            long seedStart = System.nanoTime();
            int[] seed = GeodesicSeedPath.throughVertices(intrinsic, waypointVertexIds, true);
            long seedEnd = System.nanoTime();
            FlipGeodesics flipper = new FlipGeodesics();
            double seedTotal = intrinsic.chainLength(seed, seed.length);
            long flipStart = System.nanoTime();
            int[] tightened = flipper.shorten(intrinsic, seed, true,
                    FlipGeodesics.UNBOUNDED_ITERATIONS);
            long flipEnd = System.nanoTime();
            TracedSurfacePath traced = tracer.trace(intrinsic, tightened, true);
            long traceEnd = System.nanoTime();

            System.out.printf(
                    "[flip-geodesics] %s round %d: setup %.1f ms, seed %.1f ms, flipout %.2f ms, "
                            + "trace %.2f ms (%d -> %d edges, %.5f -> %.5f, %d flips)%n",
                    name, round, (setupEnd - setupStart) / NANOS_PER_MILLI,
                    (seedEnd - seedStart) / NANOS_PER_MILLI,
                    (flipEnd - flipStart) / NANOS_PER_MILLI,
                    (traceEnd - flipEnd) / NANOS_PER_MILLI,
                    seed.length, tightened.length, seedTotal, flipper.pathLength(),
                    flipper.flipCount);
            if (round < WARMUP_ROUNDS) {
                continue;
            }
            bestSetupMillis = Math.min(bestSetupMillis, (setupEnd - setupStart) / NANOS_PER_MILLI);
            bestSeedMillis = Math.min(bestSeedMillis, (seedEnd - seedStart) / NANOS_PER_MILLI);
            bestFlipMillis = Math.min(bestFlipMillis, (flipEnd - flipStart) / NANOS_PER_MILLI);
            bestTraceMillis = Math.min(bestTraceMillis, (traceEnd - flipEnd) / NANOS_PER_MILLI);
            seedEdges = seed.length;
            tightEdges = tightened.length;
            tracePoints = traced.pointCount;
            seedLength = seedTotal;
            tightLength = flipper.pathLength();
            flips = flipper.flipCount;
        }

        System.out.printf(
                "[flip-geodesics] %s warm best: intrinsic setup %.1f ms, seed walk %.1f ms, "
                        + "FlipOut %.2f ms, trace %.2f ms%n",
                name, bestSetupMillis, bestSeedMillis, bestFlipMillis, bestTraceMillis);
        System.out.printf(
                "[flip-geodesics] %s loop: %d seed edges (%.5f) -> %d geodesic edges (%.5f), "
                        + "%d flips, %d traced points%n",
                name, seedEdges, seedLength, tightEdges, tightLength, flips, tracePoints);
    }

    /**
     * The largest connected manifold piece of a welded scan.
     *
     * <p>
     * A raw scan keeps a handful of faces that reuse a directed edge or give an edge three
     * neighbours; FlipOut needs a manifold surface, so those are dropped greedily in face order
     * (190 of 936035 on IMG_4109) and only the biggest surviving component is kept.
     *
     * @param welded scan whose coincident vertices have already been merged
     * @return the manifold surface as a half-edge mesh
     */
    private static HalfEdgeMesh largestManifoldComponent(ArrayMesh welded) {
        float[] positions = welded.copyPositions();
        int[] faces = welded.copyFaceIndices();
        int faceCount = faces.length / TRIANGLE_CORNERS;
        Set<Long> usedDirectedEdges = new HashSet<>();
        Map<Long, Integer> undirectedEdgeUses = new HashMap<>();
        int[] kept = new int[faces.length];
        int keptCount = 0;
        for (int face = 0; face < faceCount; face++) {
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
        System.out.printf("[flip-geodesics] cleanup: %d faces -> %d manifold -> %d in the largest "
                + "component (%d vertices)%n", faceCount, keptCount / TRIANGLE_CORNERS,
                outFaceCorners / TRIANGLE_CORNERS, outVertexCount);
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
