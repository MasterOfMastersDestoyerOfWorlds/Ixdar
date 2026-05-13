package unit.mesh.quadlayout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import ixdar.geometry.mesh.data.load.CrossFieldLoader;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;

/**
 * For every {@code *_in_cf*.ndf} reference under {@code test/resources/quadlayout/figure_*}
 * that has a matching {@code *_in_tri.off}, build the {@link CrossField} on the
 * OFF and assert it matches the NDF reference.
 *
 * <p>Each pair runs as its own {@link DynamicTest} on a dedicated single-thread
 * executor with a 10-second timeout, so one slow or hung pair does not block
 * the others. Surefire is free to schedule the dynamic tests across its own
 * threads concurrently.
 */
class CrossFieldNdfReferenceTest {

    private static final Path RESOURCES_ROOT = Path.of("test", "resources", "quadlayout");
    private static final long TIMEOUT_SECONDS = 10L;
    private static final int OFF_HEADER_PROBE_BYTES = 32;
    /** Maximum allowed MEAN displacement per matched singularity, as a fraction of bounding-box diagonal. */
    private static final float SINGULARITY_PLACEMENT_TOLERANCE_FRACTION = 0.05f;
    private static final float ALPHA = (float) Math.toRadians(15.0);
    private static final float HALF_PI = (float) (Math.PI / 2.0);
    private static final float THETA_TOLERANCE = (float) Math.toRadians(1.0);
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    @TestFactory
    Stream<DynamicTest> compareGeneratedCrossFieldsAgainstNdfReferences() throws IOException {
        List<Pair> pairs = discoverPairs();
        return pairs.stream().map(CrossFieldNdfReferenceTest::buildDynamicTest);
    }

    private static DynamicTest buildDynamicTest(Pair pair) {
        String displayName = pair.figureDir.getFileName() + "/" + pair.modelName
                + (pair.enhanced ? " (enhanced)" : "");
        return DynamicTest.dynamicTest(displayName, () -> runOnIsolatedThreadWithTimeout(pair));
    }

    private static void runOnIsolatedThreadWithTimeout(Pair pair) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ndf-test-" + THREAD_SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        try {
            Future<Void> future = executor.submit(() -> {
                comparePair(pair);
                return null;
            });
            try {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                future.cancel(true);
                fail("Pipeline exceeded " + TIMEOUT_SECONDS + "s timeout for " + pair);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof AssertionError ae) {
                    throw ae;
                }
                if (cause instanceof Exception e) {
                    throw e;
                }
                throw new AssertionError(cause);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void comparePair(Pair pair) throws IOException {
        assumeFalse(pair.enhanced,
                "Enhanced cross fields in BCEAK13 figure_8 are manually post-edited; the pipeline cannot reproduce them.");
        assumeFalse(isBinaryOff(pair.offPath),
                "Binary OFF format is not supported by the text-based OffMeshParser.");
        ArrayMesh arrayMesh = MeshLoader.load(pair.offPath.toString());
        HalfEdgeMesh halfEdgeMesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());

        CrossField reference = CrossFieldLoader.load(pair.ndfPath.toString(), halfEdgeMesh);
        CrossField generated = new CrossField(halfEdgeMesh).build();

        assertCrossFieldsEquivalent(reference, generated, halfEdgeMesh, pair);
    }

    private static void assertCrossFieldsEquivalent(CrossField expected, CrossField actual,
            HalfEdgeMesh mesh, Pair pair) {
        long expectedPositive = expected.singularities.stream().filter(s -> s.index4() > 0).count();
        long expectedNegative = expected.singularities.stream().filter(s -> s.index4() < 0).count();
        long actualPositive = actual.singularities.stream().filter(s -> s.index4() > 0).count();
        long actualNegative = actual.singularities.stream().filter(s -> s.index4() < 0).count();
        assertEquals(expected.singularities.size(), actual.singularities.size(),
                () -> String.format(
                        "singularity count mismatch for %s: expected %d (+%d/-%d), got %d (+%d/-%d)",
                        pair, expected.singularities.size(), expectedPositive, expectedNegative,
                        actual.singularities.size(), actualPositive, actualNegative));

        float bboxDiag = computeBoundingBoxDiagonal(mesh);
        float maxAllowedMeanDistance = SINGULARITY_PLACEMENT_TOLERANCE_FRACTION * bboxDiag;
        SingularityPairing pairing = greedyPairingBySign(expected.singularities, actual.singularities, mesh);
        if (pairing.meanDistance > maxAllowedMeanDistance) {
            fail(String.format(
                    "singularity placement mismatch for %s: mean=%.4f (%.2f%%) > %.2f%% of bbox diag %.4f; (max=%.2f%%, median=%.2f%%)%n%s",
                    pair,
                    pairing.meanDistance, 100.0 * pairing.meanDistance / bboxDiag,
                    100.0 * SINGULARITY_PLACEMENT_TOLERANCE_FRACTION, bboxDiag,
                    100.0 * pairing.maxDistance / bboxDiag,
                    100.0 * pairing.medianDistance / bboxDiag,
                    pairing.outlierReport));
        }
    }

    private static float computeBoundingBoxDiagonal(HalfEdgeMesh mesh) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        org.joml.Vector3f p = new org.joml.Vector3f();
        for (int vAi = 0; vAi < mesh.vertexCount(); vAi++) {
            mesh.vertexPosition(mesh.vertexIdAt(vAi), p);
            if (p.x < minX) minX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.z < minZ) minZ = p.z;
            if (p.x > maxX) maxX = p.x;
            if (p.y > maxY) maxY = p.y;
            if (p.z > maxZ) maxZ = p.z;
        }
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Greedy nearest-neighbour pairing of reference and actual singularities, partitioned
     * by sign so a positive-index reference singularity can only match a positive-index
     * actual one. Returns the largest pairing distance and the pair that produced it.
     */
    private static SingularityPairing greedyPairingBySign(
            java.util.List<ixdar.geometry.mesh.quadlayout.Singularity> expected,
            java.util.List<ixdar.geometry.mesh.quadlayout.Singularity> actual,
            HalfEdgeMesh mesh) {
        SingularityPairing positivePairing = pairSameSign(filterBySign(expected, +1), filterBySign(actual, +1), mesh);
        SingularityPairing negativePairing = pairSameSign(filterBySign(expected, -1), filterBySign(actual, -1), mesh);
        // Combine: take max of max, weighted means, etc. For simplicity report whichever sign-pairing is worse on max.
        if (positivePairing.maxDistance >= negativePairing.maxDistance) {
            return positivePairing;
        }
        return negativePairing;
    }

    private static java.util.List<ixdar.geometry.mesh.quadlayout.Singularity> filterBySign(
            java.util.List<ixdar.geometry.mesh.quadlayout.Singularity> singularities, int sign) {
        java.util.List<ixdar.geometry.mesh.quadlayout.Singularity> out = new java.util.ArrayList<>();
        for (var s : singularities) {
            if (Integer.signum(s.index4()) == sign) {
                out.add(s);
            }
        }
        return out;
    }

    private static SingularityPairing pairSameSign(
            java.util.List<ixdar.geometry.mesh.quadlayout.Singularity> expected,
            java.util.List<ixdar.geometry.mesh.quadlayout.Singularity> actual,
            HalfEdgeMesh mesh) {
        if (expected.isEmpty() || actual.isEmpty()) {
            return new SingularityPairing(0.0f, 0.0f, 0.0f, "");
        }

        // Pre-compute positions and the full distance matrix.
        org.joml.Vector3f tmp = new org.joml.Vector3f();
        float[][] expPos = new float[expected.size()][3];
        for (int i = 0; i < expected.size(); i++) {
            mesh.vertexPosition(expected.get(i).vertexId(), tmp);
            expPos[i][0] = tmp.x; expPos[i][1] = tmp.y; expPos[i][2] = tmp.z;
        }
        float[][] actPos = new float[actual.size()][3];
        for (int i = 0; i < actual.size(); i++) {
            mesh.vertexPosition(actual.get(i).vertexId(), tmp);
            actPos[i][0] = tmp.x; actPos[i][1] = tmp.y; actPos[i][2] = tmp.z;
        }

        // Build cost matrix and run Hungarian (Kuhn–Munkres) for optimal bipartite
        // matching. Square matrix by padding with a large dummy cost when sizes differ.
        int n = Math.max(expected.size(), actual.size());
        float[][] cost = new float[n][n];
        float padCost = 1e9f;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i < expected.size() && j < actual.size()) {
                    float dx = actPos[j][0] - expPos[i][0];
                    float dy = actPos[j][1] - expPos[i][1];
                    float dz = actPos[j][2] - expPos[i][2];
                    cost[i][j] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                } else {
                    cost[i][j] = padCost;
                }
            }
        }
        int[] eToA = hungarianMinCost(cost);

        java.util.List<Pairing> pairings = new java.util.ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            int j = eToA[i];
            if (j < 0 || j >= actual.size()) continue;
            float dx = actPos[j][0] - expPos[i][0];
            float dy = actPos[j][1] - expPos[i][1];
            float dz = actPos[j][2] - expPos[i][2];
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            pairings.add(new Pairing(
                    expected.get(i).vertexId(), expPos[i][0], expPos[i][1], expPos[i][2],
                    actual.get(j).vertexId(), actPos[j][0], actPos[j][1], actPos[j][2],
                    d, expected.get(i).index4()));
        }
        java.util.List<Float> distances = pairings.stream().map(p -> p.distance).sorted().toList();
        float median = distances.get(distances.size() / 2);
        double sum = 0.0;
        for (float d : distances) sum += d;
        float mean = (float) (sum / distances.size());
        float max = distances.get(distances.size() - 1);

        // Top outliers: pairs whose distance is more than 5x the median.
        StringBuilder report = new StringBuilder();
        java.util.List<Pairing> sorted = new java.util.ArrayList<>(pairings);
        sorted.sort((a, b) -> Float.compare(b.distance, a.distance));
        int outlierCount = (int) sorted.stream().filter(p -> p.distance > 5 * median).count();
        report.append(String.format("  %d outliers (distance > 5 × median = %.4f); top:%n", outlierCount, 5 * median));
        int top = Math.min(5, sorted.size());
        for (int i = 0; i < top; i++) {
            Pairing p = sorted.get(i);
            report.append(String.format(
                    "    [%+d/4] ref vertex %d (%.3f,%.3f,%.3f) -> gen vertex %d (%.3f,%.3f,%.3f) dist=%.4f%n",
                    p.signedIndex4, p.expectedVid, p.ex, p.ey, p.ez,
                    p.actualVid, p.ax, p.ay, p.az, p.distance));
        }
        return new SingularityPairing(max, mean, median, report.toString());
    }

    private record Pairing(int expectedVid, float ex, float ey, float ez,
            int actualVid, float ax, float ay, float az, float distance, int signedIndex4) {}

    /**
     * Standard Hungarian (Kuhn–Munkres) algorithm for the minimum-cost assignment
     * problem on a square cost matrix. Returns rowAssignment[i] = column index
     * matched to row i. O(n³). For n≤200 this is well under a millisecond.
     *
     * @param cost square non-negative cost matrix
     * @return per-row column assignment minimising total cost
     */
    private static int[] hungarianMinCost(float[][] cost) {
        int n = cost.length;
        // Working in doubles for numerical stability of the potentials.
        double[] u = new double[n + 1];
        double[] v = new double[n + 1];
        int[] p = new int[n + 1];
        int[] way = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[n + 1];
            boolean[] used = new boolean[n + 1];
            java.util.Arrays.fill(minv, Double.POSITIVE_INFINITY);
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = -1;
                for (int j = 1; j <= n; j++) {
                    if (!used[j]) {
                        double cur = cost[i0 - 1][j - 1] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] rowAssignment = new int[n];
        java.util.Arrays.fill(rowAssignment, -1);
        for (int j = 1; j <= n; j++) {
            if (p[j] != 0) {
                rowAssignment[p[j] - 1] = j - 1;
            }
        }
        return rowAssignment;
    }

    private record SingularityPairing(float maxDistance, float meanDistance, float medianDistance,
            String outlierReport) {}

    /** Reduce a signed angle difference to [0, π/4] modulo the 4-RoSy symmetry. */
    private static float wrapToQuarterPi(float angle) {
        float mod = (float) (((angle % HALF_PI) + HALF_PI) % HALF_PI);
        return Math.min(mod, HALF_PI - mod);
    }

    private static boolean isBinaryOff(Path off) {
        try {
            byte[] head = new byte[OFF_HEADER_PROBE_BYTES];
            int read;
            try (var in = Files.newInputStream(off)) {
                read = in.read(head);
            }
            String header = new String(head, 0, Math.max(0, read), StandardCharsets.ISO_8859_1);
            return header.contains("BINARY");
        } catch (IOException e) {
            return false;
        }
    }

    private static List<Pair> discoverPairs() throws IOException {
        if (!Files.isDirectory(RESOURCES_ROOT)) {
            return List.of();
        }
        try (Stream<Path> figureDirs = Files.list(RESOURCES_ROOT)) {
            return figureDirs
                    .filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().startsWith("figure_"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .flatMap(CrossFieldNdfReferenceTest::pairsInDir)
                    .toList();
        }
    }

    private static Stream<Pair> pairsInDir(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(p -> p.getFileName().toString().endsWith(".ndf"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(ndf -> toPair(dir, ndf))
                    .filter(Objects::nonNull)
                    .toList()
                    .stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private static Pair toPair(Path figureDir, Path ndf) {
        String fileName = ndf.getFileName().toString();
        int markerIndex = fileName.indexOf("_in_cf");
        if (markerIndex < 0) {
            return null;
        }
        String modelName = fileName.substring(0, markerIndex);
        boolean enhanced = fileName.contains("_in_cf_enhanced");
        Path off = figureDir.resolve(modelName + "_in_tri.off");
        if (!Files.exists(off)) {
            return null;
        }
        return new Pair(figureDir, modelName, enhanced, off, ndf);
    }

    private record Pair(Path figureDir, String modelName, boolean enhanced, Path offPath, Path ndfPath) {
        @Override
        public String toString() {
            return figureDir.getFileName() + "/" + modelName + (enhanced ? " (enhanced)" : "");
        }
    }
}
