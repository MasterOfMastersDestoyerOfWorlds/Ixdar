package benchmark;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.platform.Platforms;

/**
 * Per-stage wall time and peak heap of the quad-layout pipeline on one full-resolution Trellis2
 * crawfish scan (CRAW-2). Pick the scan with {@code -Dbenchmark.glb} and the last stage to attempt
 * with {@code -Dbenchmark.stage}.
 */
public final class CrawfishQuadLayoutBenchmark {

    /** System property naming the scan to run. */
    public static final String GLB_PROPERTY = "benchmark.glb";

    /** System property naming the last pipeline stage to attempt. */
    public static final String STAGE_PROPERTY = "benchmark.stage";

    /** Scan the benchmark runs when {@link #GLB_PROPERTY} is unset. */
    public static final String DEFAULT_GLB = "/home/acw/crawfish/IMG_4109.glb";

    /**
     * Stage names in pipeline order. {@link #STAGE_PROPERTY} names the last one attempted; the
     * default stops at the last stage known to complete inside the CRAW-2 heap budget.
     */
    public static final List<String> STAGES = List.of("crossField", "seamless", "motorcycle",
            "quantization", "carve", "contract", "gridMap", "patchSurfaces");

    /** Corners of a triangle. */
    private static final int TRIANGLE_CORNERS = 3;

    /** Bytes in a mebibyte, for the reported heap figures. */
    private static final double BYTES_PER_MEBIBYTE = 1024.0 * 1024.0;

    /** Nanoseconds per second, for the reported stage times. */
    private static final double NANOS_PER_SECOND = 1.0e9;

    /** Mixing multiplier for the x component of a vertex-position key. */
    private static final long POSITION_KEY_X = 0x9E3779B97F4A7C15L;

    /** Mixing multiplier for the y component of a vertex-position key. */
    private static final long POSITION_KEY_Y = 0xC2B2AE3D27D4EB4FL;

    /** Mixing multiplier for the z component of a vertex-position key. */
    private static final long POSITION_KEY_Z = 0x165667B19E3779F9L;

    /** Shift separating the two vertex indices of a packed directed-edge key. */
    private static final int DIRECTED_EDGE_SHIFT = 32;

    /** Low-word mask of a packed directed-edge key. */
    private static final long DIRECTED_EDGE_MASK = 0xFFFFFFFFL;

    /** Wall time in nanoseconds of each stage that ran, parallel to {@link #stageNames}. */
    private final List<Long> stageNanos = new ArrayList<>();

    /** Peak heap in bytes observed after each stage, parallel to {@link #stageNames}. */
    private final List<Long> stagePeakHeap = new ArrayList<>();

    /** Names of the stages that ran, in order. */
    private final List<String> stageNames = new ArrayList<>();

    /**
     * Runs the pipeline over one scan, logging one row per stage with its wall time and the peak
     * heap reached by its end, then the table again so an out-of-memory stage still leaves every
     * earlier measurement in the log.
     *
     * @throws IOException when the scan cannot be read
     */
    @Test
    public void quadLayoutOnOneScan() throws IOException {
        String glbPath = System.getProperty(GLB_PROPERTY, DEFAULT_GLB);
        String lastStage = System.getProperty(STAGE_PROPERTY, STAGES.get(0));
        assertTrue(STAGES.contains(lastStage), lastStage + " is not one of " + STAGES);
        resetPeakHeap();

        long loadStart = System.nanoTime();
        GeometryBundle bundle = MeshLoader.loadBundle(glbPath);
        ArrayMesh scan = assertInstanceOf(ArrayMesh.class, bundle.mesh(), "the scan is a triangle mesh");
        record("load", loadStart);

        long repairStart = System.nanoTime();
        ArrayMesh manifold = repairToOneManifoldShell(scan);
        record("repair", repairStart);

        long halfEdgeStart = System.nanoTime();
        HalfEdgeMesh mesh = manifold.toHalfEdgeMesh();
        record("toHalfEdge", halfEdgeStart);
        Platforms.log("[benchmark] %s scan V=%d F=%d, repaired V=%d E=%d F=%d, max heap %.0fMiB%n",
                glbPath, scan.vertexCount(), scan.faceCount(), mesh.vertexCount(), mesh.edgeCount(),
                mesh.faceCount(), Runtime.getRuntime().maxMemory() / BYTES_PER_MEBIBYTE);

        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, QuadLayoutEngine.DEFAULT_ALPHA_RADIANS);
        try {
            runStages(engine, STAGES.indexOf(lastStage));
        } finally {
            reportTable();
        }
        assertTrue(engine.crossField.singularityCount() > 0, "the scan's cross field has cone points");
    }

    /**
     * Runs the pipeline stages up to and including {@code lastStageIndex}, timing each one on its
     * own.
     *
     * @param engine         engine over the repaired scan
     * @param lastStageIndex index into {@link #STAGES} of the last stage to attempt
     */
    private void runStages(QuadLayoutEngine engine, int lastStageIndex) {
        for (int stageIndex = 0; stageIndex <= lastStageIndex; stageIndex++) {
            String stage = STAGES.get(stageIndex);
            long start = System.nanoTime();
            switch (stage) {
                case "crossField" -> engine.buildCrossField();
                case "seamless" -> engine.buildSeamless();
                case "motorcycle" -> engine.buildMotorcycleGraph();
                case "quantization" -> engine.buildQuantization();
                case "carve" -> engine.buildTMesh();
                case "contract" -> engine.buildContractedTMesh();
                case "gridMap" -> engine.buildGlobalGridMap();
                default -> engine.buildPatchSurfaces();
            }
            record(stage, start);
        }
    }

    /**
     * Appends one stage's wall time and the heap peak reached by its end, and logs the row
     * immediately so a stage that dies still leaves its predecessors on record.
     *
     * @param stage      stage name
     * @param startNanos {@link System#nanoTime()} taken when the stage began
     */
    private void record(String stage, long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        long peak = peakHeapBytes();
        stageNames.add(stage);
        stageNanos.add(elapsed);
        stagePeakHeap.add(peak);
        Platforms.log("[benchmark] %-14s %8.2fs  peak heap %7.0fMiB%n", stage,
                elapsed / NANOS_PER_SECOND, peak / BYTES_PER_MEBIBYTE);
    }

    /** Logs every recorded stage again as one block, the form the ticket records. */
    private void reportTable() {
        Platforms.log("[benchmark] stage table (wall time, peak heap):");
        for (int stageIndex = 0; stageIndex < stageNames.size(); stageIndex++) {
            Platforms.log("[benchmark]   %-14s %8.2fs  %7.0fMiB%n", stageNames.get(stageIndex),
                    stageNanos.get(stageIndex) / NANOS_PER_SECOND,
                    stagePeakHeap.get(stageIndex) / BYTES_PER_MEBIBYTE);
        }
    }

    /**
     * The one manifold shell underlying a Trellis2 scan: coincident vertices welded, faces that
     * would give an edge a third incidence or a second same-direction use dropped, then only the
     * largest edge-connected component kept.
     *
     * <p>The scans arrive unwelded (the glTF splits vertices along the texture atlas's seams),
     * carry a handful of non-manifold faces and come apart into more than a dozen shells, so the
     * half-edge build rejects them as they stand. This is scaffolding for the measurement, not the
     * fix: see CRAW-2's unknowns for the repair node the pipeline actually needs.
     *
     * @param scan the scan as loaded
     * @return a triangle mesh the half-edge build accepts
     */
    private static ArrayMesh repairToOneManifoldShell(ArrayMesh scan) {
        int scanVertexCount = scan.vertexCount();
        Map<Long, Integer> canonicalOfPosition = new HashMap<>(scanVertexCount * 2);
        int[] weldedOf = new int[scanVertexCount];
        float[] weldedPositions = new float[scanVertexCount * TRIANGLE_CORNERS];
        Vector3f position = new Vector3f();
        int weldedCount = 0;
        for (int vertex = 0; vertex < scanVertexCount; vertex++) {
            scan.vertexPosition(vertex, position);
            long key = Float.floatToIntBits(position.x) * POSITION_KEY_X
                    ^ Float.floatToIntBits(position.y) * POSITION_KEY_Y
                    ^ Float.floatToIntBits(position.z) * POSITION_KEY_Z;
            Integer existing = canonicalOfPosition.putIfAbsent(key, weldedCount);
            if (existing == null) {
                weldedPositions[weldedCount * TRIANGLE_CORNERS] = position.x;
                weldedPositions[weldedCount * TRIANGLE_CORNERS + 1] = position.y;
                weldedPositions[weldedCount * TRIANGLE_CORNERS + 2] = position.z;
                weldedOf[vertex] = weldedCount;
                weldedCount++;
            } else {
                weldedOf[vertex] = existing;
            }
        }

        Set<Long> usedDirectedEdges = new HashSet<>(scan.faceCount() * 4);
        int[] keptCorners = new int[scan.faceCount() * TRIANGLE_CORNERS];
        int keptFaceCount = 0;
        int droppedFaceCount = 0;
        for (int face = 0; face < scan.faceCount(); face++) {
            int cornerA = weldedOf[scan.faceVertexAt(face, 0)];
            int cornerB = weldedOf[scan.faceVertexAt(face, 1)];
            int cornerC = weldedOf[scan.faceVertexAt(face, 2)];
            boolean usable = cornerA != cornerB && cornerB != cornerC && cornerA != cornerC
                    && !usedDirectedEdges.contains(directedEdgeKey(cornerA, cornerB))
                    && !usedDirectedEdges.contains(directedEdgeKey(cornerB, cornerC))
                    && !usedDirectedEdges.contains(directedEdgeKey(cornerC, cornerA));
            if (!usable) {
                droppedFaceCount++;
                continue;
            }
            usedDirectedEdges.add(directedEdgeKey(cornerA, cornerB));
            usedDirectedEdges.add(directedEdgeKey(cornerB, cornerC));
            usedDirectedEdges.add(directedEdgeKey(cornerC, cornerA));
            keptCorners[keptFaceCount * TRIANGLE_CORNERS] = cornerA;
            keptCorners[keptFaceCount * TRIANGLE_CORNERS + 1] = cornerB;
            keptCorners[keptFaceCount * TRIANGLE_CORNERS + 2] = cornerC;
            keptFaceCount++;
        }

        int[] componentOf = new int[keptFaceCount];
        for (int face = 0; face < keptFaceCount; face++) {
            componentOf[face] = face;
        }
        Map<Long, Integer> faceAcrossEdge = new HashMap<>(keptFaceCount * 4);
        for (int face = 0; face < keptFaceCount; face++) {
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int from = keptCorners[face * TRIANGLE_CORNERS + corner];
                int to = keptCorners[face * TRIANGLE_CORNERS + (corner + 1) % TRIANGLE_CORNERS];
                long key = directedEdgeKey(Math.min(from, to), Math.max(from, to));
                Integer other = faceAcrossEdge.putIfAbsent(key, face);
                if (other != null) {
                    union(componentOf, other, face);
                }
            }
        }
        int[] facesInComponent = new int[keptFaceCount];
        for (int face = 0; face < keptFaceCount; face++) {
            facesInComponent[find(componentOf, face)]++;
        }
        int largestComponent = 0;
        int componentCount = 0;
        for (int face = 0; face < keptFaceCount; face++) {
            if (facesInComponent[face] > 0) {
                componentCount++;
            }
            if (facesInComponent[face] > facesInComponent[largestComponent]) {
                largestComponent = face;
            }
        }
        int shellFaceCount = 0;
        for (int face = 0; face < keptFaceCount; face++) {
            if (find(componentOf, face) != largestComponent) {
                continue;
            }
            System.arraycopy(keptCorners, face * TRIANGLE_CORNERS, keptCorners,
                    shellFaceCount * TRIANGLE_CORNERS, TRIANGLE_CORNERS);
            shellFaceCount++;
        }
        Platforms.log("[benchmark] repair kept the largest of %d edge-connected shells:"
                + " %d of %d faces%n", componentCount, shellFaceCount, keptFaceCount);
        keptFaceCount = shellFaceCount;

        int[] compactOfWelded = new int[weldedCount];
        Arrays.fill(compactOfWelded, -1);
        int liveVertexCount = 0;
        for (int corner = 0; corner < keptFaceCount * TRIANGLE_CORNERS; corner++) {
            if (compactOfWelded[keptCorners[corner]] < 0) {
                compactOfWelded[keptCorners[corner]] = liveVertexCount;
                liveVertexCount++;
            }
        }
        float[] positions = new float[liveVertexCount * TRIANGLE_CORNERS];
        for (int welded = 0; welded < weldedCount; welded++) {
            int compact = compactOfWelded[welded];
            if (compact < 0) {
                continue;
            }
            positions[compact * TRIANGLE_CORNERS] = weldedPositions[welded * TRIANGLE_CORNERS];
            positions[compact * TRIANGLE_CORNERS + 1] = weldedPositions[welded * TRIANGLE_CORNERS + 1];
            positions[compact * TRIANGLE_CORNERS + 2] = weldedPositions[welded * TRIANGLE_CORNERS + 2];
        }
        int[] faceIndices = new int[keptFaceCount * TRIANGLE_CORNERS];
        for (int corner = 0; corner < faceIndices.length; corner++) {
            faceIndices[corner] = compactOfWelded[keptCorners[corner]];
        }
        Platforms.log("[benchmark] repair welded %d->%d vertices (%d live), dropped %d of %d faces%n",
                scanVertexCount, weldedCount, liveVertexCount, droppedFaceCount, scan.faceCount());

        ArrayMesh repaired = new ArrayMesh(positions, new float[positions.length], faceIndices,
                TRIANGLE_CORNERS);
        repaired.computeNormals();
        return repaired;
    }

    /**
     * Union-find root of a face, with path halving.
     *
     * @param componentOf parent array, updated in place
     * @param face        face index
     * @return the face's component root
     */
    private static int find(int[] componentOf, int face) {
        int root = face;
        while (componentOf[root] != root) {
            componentOf[root] = componentOf[componentOf[root]];
            root = componentOf[root];
        }
        return root;
    }

    /**
     * Merges the components of two faces.
     *
     * @param componentOf parent array, updated in place
     * @param first       one face
     * @param second      the other face
     */
    private static void union(int[] componentOf, int first, int second) {
        int firstRoot = find(componentOf, first);
        int secondRoot = find(componentOf, second);
        if (firstRoot != secondRoot) {
            componentOf[secondRoot] = firstRoot;
        }
    }

    /**
     * Packs one directed edge of a face into a long, so the two uses of an undirected edge get
     * distinct keys and a repeated direction is a duplicate.
     *
     * @param from start vertex of the directed edge
     * @param to   end vertex of the directed edge
     * @return the packed key
     */
    private static long directedEdgeKey(int from, int to) {
        return ((long) from << DIRECTED_EDGE_SHIFT) | (to & DIRECTED_EDGE_MASK);
    }

    /** Clears the peak-usage watermark of every heap pool so the run's own peak is measured. */
    private static void resetPeakHeap() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    /**
     * Sum of the peak usage of every heap pool since {@link #resetPeakHeap}; an upper bound, since
     * the pools need not peak simultaneously.
     *
     * @return peak heap in bytes
     */
    private static long peakHeapBytes() {
        long peak = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        return peak;
    }
}
