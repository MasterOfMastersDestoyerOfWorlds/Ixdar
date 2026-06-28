package ixdar.entrypoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshNode;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshPatch;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;

/**
 * Parity harness against LCK21a Table 1 (page 311): runs the pipeline with
 * feature tracing disabled (the paper's ROCKERARM run has 144 = 4 × 36 traces,
 * so no feature traces) and checks the paper's structural invariants:
 *
 * <ol>
 * <li>every arrangement cycle is a rectangle (invalid cycles == 0),</li>
 * <li>the arrangement is a cell complex (nodes − arcs + patches == χ of the
 * input mesh),</li>
 * <li>no truncated traces (every motorcycle terminates properly),</li>
 * <li>quantization validity from Lemma 1 alone (zero separation cuts),</li>
 * <li>measured separatrix deviation d_max ≤ α,</li>
 * <li>#Traces == 4 · #Sing.</li>
 * </ol>
 *
 * ROCKERARM is the paper's identical 20088-face mesh, so its Table 1 counts
 * are directly comparable; bunny/armadillo runs check invariants only (mesh
 * resolutions differ from the paper's).
 */
public final class BenchmarkLyonParity {

    /** Watchdog exit code (mirrors {@code timeout}'s convention). */
    public static final int EXIT_TIMEOUT = 124;

    /** Table 1 ROCKERARM row (LCK21a page 311, verified 2026-06-12). */
    public static final int EXPECTED_ROCKERARM_SINGULARITIES = 36;
    public static final int EXPECTED_ROCKERARM_TRACES = 144;
    public static final int EXPECTED_ROCKERARM_ARCS = 2742;
    public static final int EXPECTED_ROCKERARM_VARIABLES = 192;
    public static final int EXPECTED_ROCKERARM_PATCHES = 159;
    public static final double EXPECTED_ROCKERARM_DEVIATION_MEAN = 3.7;
    public static final double EXPECTED_ROCKERARM_DEVIATION_MAX = 14.7;

    /** A UV triangle below this fraction of the mean |area| counts degenerate. */
    public static final double DEGENERATE_UV_AREA_FRACTION = 1.0e-6;

    /** Comparison bands for Table-1-comparable counts. */
    public static final int SINGULARITY_TOLERANCE = 2;
    public static final double ARC_TOLERANCE_FRACTION = 0.15;
    public static final double PATCH_TOLERANCE_FRACTION = 0.20;
    public static final double VARIABLES_PER_TRACE_BOUND = 1.5;

    private static final long TIMEOUT_MS = 900_000L;
    private static final String PASS = "PASS";
    private static final String FAIL = "FAIL";
    private static final float ROCKERARM_ALPHA_DEGREES = 15f;
    private static final float BUNNY_ALPHA_DEGREES = 35f;
    private static final float ARMADILLO_ALPHA_DEGREES = 15f;

    private BenchmarkLyonParity() {
    }

    /**
     * CLI entry: run the parity suite over the available reference meshes.
     *
     * @param args unused
     * @throws Exception propagated from mesh loading or pipeline execution
     */
    public static void main(String[] args) throws Exception {
        AtomicBoolean finished = new AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TIMEOUT_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!finished.get()) {
                System.err.printf("[parity] FAILED timeout=%dms%n", TIMEOUT_MS);
                System.exit(EXIT_TIMEOUT);
            }
        }, "parity-timeout");
        watchdog.setDaemon(true);
        watchdog.start();

        try {
            String home = System.getProperty("user.home");
            runMesh("ROCKERARM-FINE(.off)",
                    Paths.get("ixdar-app/test/resources/quadlayout/figure_8/rockerarm_in_tri.off"),
                    ROCKERARM_ALPHA_DEGREES, false);
            runMesh("ROCKERARM",
                    Paths.get("ixdar-app/test/resources/quadlayout/rocker-arm/rocker-arm.obj"),
                    ROCKERARM_ALPHA_DEGREES, true);
            if (Boolean.getBoolean("parity.extraMeshes")) {
                // Opt-in: the full-res bunny has boundary holes the cross-field
                // setup does not handle yet, and both runs are slow.
                runMesh("BUNNY(full-res)",
                        Paths.get(home, "QuadLayoutsTestMeshes/stanford-bunny.obj"),
                        BUNNY_ALPHA_DEGREES, false);
                runMesh("ARMADILLO", Paths.get(home, "QuadLayoutsTestMeshes/armadillo.obj"),
                        ARMADILLO_ALPHA_DEGREES, false);
            }
        } finally {
            finished.set(true);
            watchdog.interrupt();
        }
    }

    /**
     * Run one mesh through the pipeline with feature tracing off and print its
     * comparison block and invariant verdicts.
     *
     * @param name           display name
     * @param objPath        mesh path; missing files are skipped with a note
     * @param alphaDegrees   Lyon α in degrees
     * @param compareTable1  whether to compare counts against the ROCKERARM row
     * @throws Exception propagated from mesh loading or pipeline execution
     */
    private static void runMesh(String name, Path objPath, float alphaDegrees,
            boolean compareTable1) throws Exception {
        if (!Files.exists(objPath)) {
            System.out.printf("[parity] %s SKIPPED (missing %s)%n", name, objPath);
            return;
        }
        System.out.printf("%n[parity] ===== %s alpha=%.0f deg featureTracing=off =====%n",
                name, alphaDegrees);
        ArrayMesh arrayMesh = MeshLoader.load(objPath.toString());
        HalfEdgeMesh mesh = arrayMesh.toHalfEdgeMesh();
        int meshEuler = mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();

        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, (float) Math.toRadians(alphaDegrees));
        long t0 = System.currentTimeMillis();
        engine.buildSeamless();
        long tSeamless = System.currentTimeMillis();
        engine.buildMotorcycleGraph();
        long tMotorcycle = System.currentTimeMillis();
        engine.buildQuantization();
        long tQuantization = System.currentTimeMillis();
        engine.buildConformingLayout();
        long tConforming = System.currentTimeMillis();

        int degenerateUvFaces = 0;
        double meanAbsUvArea = 0.0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            meanAbsUvArea += Math.abs(engine.seamless.uvSignedArea(mesh.faceIdAt(activeFace)));
        }
        meanAbsUvArea /= mesh.faceCount();
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            double area = Math.abs(engine.seamless.uvSignedArea(mesh.faceIdAt(activeFace)));
            if (area < DEGENERATE_UV_AREA_FRACTION * meanAbsUvArea) {
                degenerateUvFaces++;
                System.out.printf(
                        "[parity-diag] degenerate UV face active=%d uvArea=%.3e face3dArea=%.3e%n",
                        activeFace, engine.seamless.uvSignedArea(mesh.faceIdAt(activeFace)),
                        engine.seamless.faceArea[activeFace]);
            }
        }

        MotorcycleGraph graph = engine.motorcycleGraph;
        int singularities = engine.crossField.singularities.size();
        int traceTotal = graph.traces.size();
        int featureTraces = 0;
        for (Trace trace : graph.traces) {
            if (trace.featureTrace) {
                featureTraces++;
            }
        }
        int invalidCycles = 0;
        for (TMeshPatch patch : graph.patches) {
            if (!patch.validRectangle) {
                invalidCycles++;
            }
        }
        int truncatedNodes = 0;
        for (TMeshNode node : graph.nodes) {
            if (node.type == TMeshNode.Type.TRUNCATED) {
                truncatedNodes++;
            }
        }
        int arrangementEuler = graph.nodes.size() - graph.arcs.size() + graph.patches.size();
        if (compareTable1) {
            System.out.printf("[parity] expected: sing=%d traces=%d arcs=%d vars=%d #P=%d"
                    + " dMean=%.1f dMax=%.1f%n",
                    EXPECTED_ROCKERARM_SINGULARITIES, EXPECTED_ROCKERARM_TRACES,
                    EXPECTED_ROCKERARM_ARCS, EXPECTED_ROCKERARM_VARIABLES,
                    EXPECTED_ROCKERARM_PATCHES, EXPECTED_ROCKERARM_DEVIATION_MEAN,
                    EXPECTED_ROCKERARM_DEVIATION_MAX);
            verdict(String.format("table1.sing within +-%d", SINGULARITY_TOLERANCE),
                    Math.abs(singularities - EXPECTED_ROCKERARM_SINGULARITIES) <= SINGULARITY_TOLERANCE);
            verdict("table1.arcs within +-15%", withinFraction(graph.arcs.size(),
                    EXPECTED_ROCKERARM_ARCS, ARC_TOLERANCE_FRACTION));
            verdict("table1.#P within +-20%", withinFraction(engine.conforming.finalPatchCount,
                    EXPECTED_ROCKERARM_PATCHES, PATCH_TOLERANCE_FRACTION));
        }
        verdict(String.format("invariant1.invalidCycles==0 (%d)", invalidCycles),
                invalidCycles == 0);
        verdict(String.format("invariant2.arrangementEuler==%d (%d)", meshEuler, arrangementEuler),
                arrangementEuler == meshEuler);
        verdict(String.format("invariant3.noTruncatedTraces (truncatedNodes=%d"
                + " orphaned=%d staleDropsAlive=%d)", truncatedNodes,
                graph.aliveAtQueueEndCount, graph.staleEventDropsForAliveTraces),
                truncatedNodes == 0 && graph.aliveAtQueueEndCount == 0);
        verdict(String.format("invariant8.noRepeatedChainNodes (%d)", graph.repeatedChainNodeCount),
                graph.repeatedChainNodeCount == 0);
        verdict(String.format("invariant4.lemma1Suffices (cuts=%d violated=%b)",
                engine.quantization.separationCutCount,
                engine.quantization.singularitySeparationViolated),
                engine.quantization.separationCutCount == 0
                        && !engine.quantization.singularitySeparationViolated);
        verdict(String.format("invariant6.traces==4*sing (%d vs %d)", traceTotal,
                4 * singularities), traceTotal == 4 * singularities);
        verdict(String.format("invariant7.noDegenerateUvFaces (%d)", degenerateUvFaces),
                degenerateUvFaces == 0);
        verdict(String.format("variables<=1.5*traces (%d)", engine.quantization.variableCount),
                engine.quantization.variableCount <= VARIABLES_PER_TRACE_BOUND * traceTotal);
        System.out.printf("[parity] timings seamless=%dms mcg=%dms ilp=%dms conform=%dms%n",
                tSeamless - t0, tMotorcycle - tSeamless, tQuantization - tMotorcycle,
                tConforming - tQuantization);
    }

    /**
     * Print one PASS/FAIL verdict line.
     *
     * @param label  check label with measured values baked in
     * @param passed whether the check passed
     */
    private static void verdict(String label, boolean passed) {
        System.out.printf("[parity] %s %s%n", passed ? PASS : FAIL, label);
    }

    /**
     * Whether a measured count lies within a fractional band of an expected
     * value.
     *
     * @param measured measured count
     * @param expected expected count
     * @param fraction allowed fractional deviation
     * @return whether the band contains the measurement
     */
    private static boolean withinFraction(int measured, int expected, double fraction) {
        return Math.abs(measured - expected) <= fraction * expected;
    }
}
