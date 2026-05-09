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
import ixdar.geometry.mesh.quadlayout.CrossField;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;

/**
 * For every {@code *_in_cf*.ndf} reference under {@code test/resources/quadlayout/figure_*}
 * that has a matching {@code *_in_tri.off}, run {@link QuadLayoutEngine#pipeline}
 * on the OFF and assert the resulting {@link CrossField} matches the NDF reference.
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
    /** Maximum allowed displacement per matched singularity, as a fraction of bounding-box diagonal. */
    private static final float SINGULARITY_PLACEMENT_TOLERANCE_FRACTION = 0.03f;
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
        CrossField generated = QuadLayoutEngine.pipeline(halfEdgeMesh, ALPHA);

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
        float maxAllowedDistance = SINGULARITY_PLACEMENT_TOLERANCE_FRACTION * bboxDiag;
        SingularityPairing pairing = greedyPairingBySign(expected.singularities, actual.singularities, mesh);
        if (pairing.maxDistance > maxAllowedDistance) {
            fail(String.format(
                    "singularity placement mismatch for %s: max=%.4f (%.2f%%) > %.2f%% of bbox diag %.4f; (mean=%.2f%%, median=%.2f%%)%n%s",
                    pair,
                    pairing.maxDistance, 100.0 * pairing.maxDistance / bboxDiag,
                    100.0 * SINGULARITY_PLACEMENT_TOLERANCE_FRACTION, bboxDiag,
                    100.0 * pairing.meanDistance / bboxDiag,
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
            java.util.List<ixdar.geometry.mesh.quadlayout.vectorfield.Singularity> expected,
            java.util.List<ixdar.geometry.mesh.quadlayout.vectorfield.Singularity> actual,
            HalfEdgeMesh mesh) {
        SingularityPairing positivePairing = pairSameSign(filterBySign(expected, +1), filterBySign(actual, +1), mesh);
        SingularityPairing negativePairing = pairSameSign(filterBySign(expected, -1), filterBySign(actual, -1), mesh);
        // Combine: take max of max, weighted means, etc. For simplicity report whichever sign-pairing is worse on max.
        if (positivePairing.maxDistance >= negativePairing.maxDistance) {
            return positivePairing;
        }
        return negativePairing;
    }

    private static java.util.List<ixdar.geometry.mesh.quadlayout.vectorfield.Singularity> filterBySign(
            java.util.List<ixdar.geometry.mesh.quadlayout.vectorfield.Singularity> singularities, int sign) {
        java.util.List<ixdar.geometry.mesh.quadlayout.vectorfield.Singularity> out = new java.util.ArrayList<>();
        for (var s : singularities) {
            if (Integer.signum(s.index4()) == sign) {
                out.add(s);
            }
        }
        return out;
    }

    private static SingularityPairing pairSameSign(
            java.util.List<ixdar.geometry.mesh.quadlayout.vectorfield.Singularity> expected,
            java.util.List<ixdar.geometry.mesh.quadlayout.vectorfield.Singularity> actual,
            HalfEdgeMesh mesh) {
        if (expected.isEmpty() || actual.isEmpty()) {
            return new SingularityPairing(0.0f, 0.0f, 0.0f, "");
        }
        boolean[] used = new boolean[actual.size()];
        org.joml.Vector3f ep = new org.joml.Vector3f();
        org.joml.Vector3f ap = new org.joml.Vector3f();
        java.util.List<Pairing> pairings = new java.util.ArrayList<>();
        for (int idx = 0; idx < expected.size(); idx++) {
            var e = expected.get(idx);
            mesh.vertexPosition(e.vertexId(), ep);
            int bestI = -1;
            float bestD2 = Float.POSITIVE_INFINITY;
            for (int i = 0; i < actual.size(); i++) {
                if (used[i]) continue;
                mesh.vertexPosition(actual.get(i).vertexId(), ap);
                float dx = ap.x - ep.x;
                float dy = ap.y - ep.y;
                float dz = ap.z - ep.z;
                float d2 = dx * dx + dy * dy + dz * dz;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestI = i;
                }
            }
            used[bestI] = true;
            float d = (float) Math.sqrt(bestD2);
            mesh.vertexPosition(actual.get(bestI).vertexId(), ap);
            pairings.add(new Pairing(e.vertexId(), ep.x, ep.y, ep.z,
                    actual.get(bestI).vertexId(), ap.x, ap.y, ap.z, d, e.index4()));
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
