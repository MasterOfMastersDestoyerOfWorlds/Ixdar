package unit.mesh.quadlayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.ParameterizationMetrics;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Smoke test for {@link SeamlessParameterization}. Runs the BZK09 §5 pipeline
 * end-to-end on the {@code sphere_base} fixture (504 faces), checking the
 * properties any caller of the seamless parametrization stage must rely on:
 *
 * <ul>
 *   <li>build runs without exception, populating all output arrays;</li>
 *   <li>every singularity lies on the cut graph (BZK09 §5 cutting requirement);</li>
 *   <li>each cut edge's four seamless transition equations satisfy
 *       {@code (u', v') = R<sub>r·π/2</sub>(u, v) + (s, t)} to floating-point
 *       precision (exactness is MC19's job, not this stage's);</li>
 *   <li>injectivity: every triangle has positive UV signed area after at
 *       most {@link SeamlessParameterization#maxStiffeningIterations} §5.4
 *       reweighting passes.</li>
 * </ul>
 */
class SeamlessParameterizationSmokeTest {

    private static final Path SPHERE_OFF = Path.of(
            "test/resources/quadlayout/figure_7/sphere_base_in_tri.off");
    private static final Path BOLT_OFF = Path.of(
            "test/resources/quadlayout/figure_10/bolt_in_tri.off");
    private static final Path FANDISK_OFF = Path.of(
            "test/resources/quadlayout/figure_10/fandisk_in_tri.off");

    /**
     * Tolerance for the floating-point seamless transition residual. The relaxed
     * BZK09 §5 solve enforces the four transition equations per cut edge as soft
     * penalties, so the residual is roughly the (u, v) magnitude divided by
     * {@link SeamlessParameterization#seamPenaltyWeight}^½. Driving this to 0 is
     * MC19's job (LCK21 §3 — "exact seamlessness"); for the seamless input
     * Lyon's pipeline expects a few percent of the parametric scale is fine.
     */
    private static final float TRANSITION_TOLERANCE = 1.0f;

    @Test
    @Timeout(value = 2, unit = java.util.concurrent.TimeUnit.MINUTES)
    void buildSphereBase() throws IOException {
        ArrayMesh arrayMesh = MeshLoader.load(SPHERE_OFF.toString());
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());

        CrossField crossField = new CrossField(mesh).build();
        SeamlessParameterization seamless = new SeamlessParameterization(crossField);
        ParameterizationMetrics metrics = seamless.build();

        // 1. Output arrays populated.
        assertNotNull(seamless.uCorner);
        assertNotNull(seamless.vCorner);
        assertNotNull(seamless.cutGraph.isCutEdge);
        assertNotNull(seamless.cutGraph.cutRotation);
        assertNotNull(seamless.cutTranslationS);
        assertNotNull(seamless.cutTranslationT);
        assertEquals(SeamlessParameterization.CORNERS_PER_FACE * mesh.faceCount(), seamless.uCorner.length);
        assertEquals(SeamlessParameterization.CORNERS_PER_FACE * mesh.faceCount(), seamless.vCorner.length);
        assertEquals(mesh.edgeCount(), seamless.cutGraph.isCutEdge.length);

        // 2. Every singularity is on the cut graph (BZK09 §5 cutting requirement).
        for (Singularity s : crossField.singularities) {
            int sVid = s.vertexId();
            boolean onCut = false;
            int incidentEdges = mesh.vertexEdgeCount(sVid);
            for (int i = 0; i < incidentEdges; i++) {
                int eId = mesh.vertexEdgeAt(sVid, i);
                int ae = crossField.edgeIdToActive.get(eId);
                if (seamless.cutGraph.isCutEdge[ae] && !mesh.isBoundaryEdge(eId)) { onCut = true; break; }
            }
            assertTrue(onCut, "singularity vertex " + sVid + " is not on the cut graph");
        }

        // 3. BZK09 §5 hard combinatorial invariants.
        assertBzk09Invariants(metrics, seamless, "sphere");

        // 4. Seamless transition residuals < tolerance on every interior cut edge
        //    (BZK09 §5 soft-penalty seamlessness; exactness is MC19's job).
        assertTrue(metrics.maxTransitionResidual < TRANSITION_TOLERANCE,
                "max seamless transition residual = " + metrics.maxTransitionResidual
                        + " (tolerance " + TRANSITION_TOLERANCE + ")");

        // 5. Injectivity. The relaxed solve may need §5.4 stiffening; allow up to the
        //    configured cap.
        assertTrue(seamless.injective,
                "expected injective UV map; flipped=" + metrics.flippedTriangleCount
                        + " stiffeningIters=" + seamless.stiffeningIterations);
    }

    /**
     * Sphere fixture with MC19 exact-seamless projection enabled. After the
     * BZK09 → MC19 stages the per-cut-edge transition residual must be
     * <em>literally zero</em>, not just small — that is what MC19 promises.
     *
     * @throws IOException if the fixture mesh cannot be loaded
     */
    @Test
    @Timeout(value = 3, unit = java.util.concurrent.TimeUnit.MINUTES)
    void exactSeamsSphereBase() throws IOException {
        ArrayMesh arrayMesh = MeshLoader.load(SPHERE_OFF.toString());
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());

        CrossField crossField = new CrossField(mesh).build();
        SeamlessParameterization seamless = new SeamlessParameterization(crossField);
        seamless.exactSeams = true;
        ParameterizationMetrics metrics = seamless.build();

        assertBzk09Invariants(metrics, seamless, "sphere-exact");
        assertEquals(0.0f, metrics.maxTransitionResidual,
                "sphere-exact: MC19 must yield literal zero residual, got "
                        + metrics.maxTransitionResidual);
        for (float u : seamless.uCorner) {
            assertTrue(Float.isFinite(u),
                    "sphere-exact: non-finite uCorner after MC19");
        }
        for (float v : seamless.vCorner) {
            assertTrue(Float.isFinite(v),
                    "sphere-exact: non-finite vCorner after MC19");
        }
    }

    /**
     * Assert the hard combinatorial invariants from BZK09 §5: branch consistency
     * across every interior edge (matches on non-cut, differs by
     * {@code cutRotation[ae]} on cut) and {@code cutRotation ∈ {0..3}} on every
     * edge. These hold by construction on a correct {@code CutGraph} build, so a
     * failure here is a bug, not a tolerance issue.
     *
     * @param metrics  metrics summary produced by {@link SeamlessParameterization#build()}
     * @param seamless the parameterization whose {@code cutGraph} is being checked
     * @param label    fixture name for failure messages
     */
    private static void assertBzk09Invariants(ParameterizationMetrics metrics,
            SeamlessParameterization seamless, String label) {
        assertEquals(0, metrics.validBranchConsistency,
                label + ": BZK09 §5 branch consistency violated on "
                        + metrics.validBranchConsistency + " interior edges");
        int[] cutRotation = seamless.cutGraph.cutRotation;
        for (int ae = 0; ae < cutRotation.length; ae++) {
            int r = cutRotation[ae];
            assertTrue(r >= 0 && r < SeamlessParameterization.BRANCH_COUNT,
                    label + ": cutRotation[" + ae + "] = " + r + " out of {0..3}");
        }
        assertTrue(metrics.disconnectedChartCount >= 1,
                label + ": chartCount = " + metrics.disconnectedChartCount + " (expected ≥ 1)");
    }

    /**
     * Real-mesh harness on the {@code bolt} fixture (5780 faces, 26 singularities).
     * Reports detailed stats; does not strictly fail on injectivity since the
     * §5.4 stiffening on harder meshes may not always reach 0 flips within the
     * iteration cap.
     *
     * <p>Use {@code mvn test -Dtest=SeamlessParameterizationSmokeTest#realMeshBolt}
     * to run by itself.
     */
    @Test
    @Timeout(value = 5, unit = java.util.concurrent.TimeUnit.MINUTES)
    void realMeshBolt() throws IOException {
        runRealMeshDiagnostic("bolt", BOLT_OFF);
    }

    /**
     * Real-mesh harness on the {@code fandisk} fixture (14454 faces).
     */
    @Test
    @Timeout(value = 10, unit = java.util.concurrent.TimeUnit.MINUTES)
    void realMeshFandisk() throws IOException {
        runRealMeshDiagnostic("fandisk", FANDISK_OFF);
    }

    private static void runRealMeshDiagnostic(String label, Path offPath) throws IOException {
        ArrayMesh arrayMesh = MeshLoader.load(offPath.toString());
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());

        long t0 = System.nanoTime();
        CrossField crossField = new CrossField(mesh).build();
        long t1 = System.nanoTime();
        SeamlessParameterization seamless = new SeamlessParameterization(crossField);
        seamless.exactSeams = true;
        ParameterizationMetrics metrics = seamless.build();
        long t2 = System.nanoTime();

        int totalCut = 0, boundaryCut = 0;
        for (int ae = 0; ae < mesh.edgeCount(); ae++) {
            if (seamless.cutGraph.isCutEdge[ae]) {
                totalCut++;
                if (mesh.isBoundaryEdge(mesh.edgeIdAt(ae))) boundaryCut++;
            }
        }

        System.out.printf("[%s] V=%d E=%d F=%d  sing=%d  crossField=%.2fs seamless=%.2fs%n",
                label, mesh.vertexCount(), mesh.edgeCount(), mesh.faceCount(),
                crossField.singularities.size(),
                (t1 - t0) / 1.0e9, (t2 - t1) / 1.0e9);
        System.out.printf("[%s] cuts=%d (boundary=%d interior=%d)  injective=%b stiffeningIters=%d  flipped=%d/%d (%.2f%%)%n",
                label, totalCut, boundaryCut, totalCut - boundaryCut,
                seamless.injective, seamless.stiffeningIterations,
                metrics.flippedTriangleCount, mesh.faceCount(),
                100.0 * metrics.flippedTriangleCount / mesh.faceCount());
        System.out.printf("[%s] residual: max=%.4f mean=%.4f  distortion(mean)=%.4f  charts=%d  branchViolations=%d%n",
                label, metrics.maxTransitionResidual, metrics.meanTransitionResidual,
                metrics.meanDistortion, metrics.disconnectedChartCount, metrics.validBranchConsistency);

        assertNotNull(seamless.uCorner);
        // BZK09 §5 hard combinatorial invariants — must hold regardless of solver quality.
        assertBzk09Invariants(metrics, seamless, label);
        // MC19 §5.3.1 exact-seamlessness gate.
        assertEquals(0.0f, metrics.maxTransitionResidual,
                label + ": MC19 must yield exactly zero residual, got "
                        + metrics.maxTransitionResidual);
        // Sanity: every singularity sits on the cut graph.
        for (Singularity s : crossField.singularities) {
            int sVid = s.vertexId();
            boolean onCut = false;
            int incident = mesh.vertexEdgeCount(sVid);
            for (int i = 0; i < incident; i++) {
                int eId = mesh.vertexEdgeAt(sVid, i);
                int ae = crossField.edgeIdToActive.get(eId);
                if (seamless.cutGraph.isCutEdge[ae] && !mesh.isBoundaryEdge(eId)) { onCut = true; break; }
            }
            assertTrue(onCut, label + ": singularity vertex " + sVid + " is not on the cut graph");
        }
    }



}
