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

        assertCrossFieldsEquivalent(reference, generated, pair);
    }

    private static void assertCrossFieldsEquivalent(CrossField expected, CrossField actual, Pair pair) {
        assertArrayEquals(expected.singularityIndexQuarter, actual.singularityIndexQuarter,
                () -> "singularityIndexQuarter mismatch for " + pair);
        assertArrayEquals(expected.periodJump, actual.periodJump,
                () -> "periodJump mismatch for " + pair);

        assertEquals(expected.theta.length, actual.theta.length,
                () -> "theta length mismatch for " + pair);
        for (int faceIndex = 0; faceIndex < expected.theta.length; faceIndex++) {
            float diff = wrapToQuarterPi(actual.theta[faceIndex] - expected.theta[faceIndex]);
            if (diff > THETA_TOLERANCE) {
                fail(String.format(
                        "theta[%d] diverges by %.4f rad (tolerance %.4f) for %s",
                        faceIndex, diff, THETA_TOLERANCE, pair));
            }
        }
    }

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
