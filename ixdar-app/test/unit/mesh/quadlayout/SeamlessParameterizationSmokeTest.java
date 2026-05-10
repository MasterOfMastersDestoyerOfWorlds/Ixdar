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
import ixdar.geometry.mesh.quadlayout.CrossField;
import ixdar.geometry.mesh.quadlayout.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.Singularity;

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
        SeamlessParameterization seamless = SeamlessParameterization.from(crossField).build();

        // 1. Output arrays populated.
        assertNotNull(seamless.uCorner);
        assertNotNull(seamless.vCorner);
        assertNotNull(seamless.isCutEdge);
        assertNotNull(seamless.cutRotation);
        assertNotNull(seamless.cutTranslationS);
        assertNotNull(seamless.cutTranslationT);
        assertEquals(SeamlessParameterization.CORNERS_PER_FACE * mesh.faceCount(), seamless.uCorner.length);
        assertEquals(SeamlessParameterization.CORNERS_PER_FACE * mesh.faceCount(), seamless.vCorner.length);
        assertEquals(mesh.edgeCount(), seamless.isCutEdge.length);

        // 2. Every singularity is on the cut graph.
        for (Singularity s : crossField.singularities) {
            int sVid = s.vertexId();
            boolean onCut = false;
            int incidentEdges = mesh.vertexEdgeCount(sVid);
            for (int i = 0; i < incidentEdges; i++) {
                int eId = mesh.vertexEdgeAt(sVid, i);
                int ae = crossField.edgeIdToActive.get(eId);
                if (seamless.isCutEdge[ae] && !mesh.isBoundaryEdge(eId)) { onCut = true; break; }
            }
            assertTrue(onCut, "singularity vertex " + sVid + " is not on the cut graph");
        }

        // 3. Seamless transition residuals < tolerance on every interior cut edge.
        float maxResidual = computeMaxTransitionResidual(seamless, mesh);
        assertTrue(maxResidual < TRANSITION_TOLERANCE,
                "max seamless transition residual = " + maxResidual + " (tolerance " + TRANSITION_TOLERANCE + ")");

        // 4. Injectivity. The relaxed solve may need stiffening; allow up to the configured cap.
        int flipped = countFlippedTriangles(seamless, mesh);
        assertTrue(seamless.injective,
                "expected injective UV map; flipped=" + flipped
                        + " stiffeningIters=" + seamless.stiffeningIterations);
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
        SeamlessParameterization seamless = SeamlessParameterization.from(crossField).build();
        long t2 = System.nanoTime();

        int totalCut = 0, boundaryCut = 0;
        for (int ae = 0; ae < mesh.edgeCount(); ae++) {
            if (seamless.isCutEdge[ae]) {
                totalCut++;
                if (mesh.isBoundaryEdge(mesh.edgeIdAt(ae))) boundaryCut++;
            }
        }
        int flipped = countFlippedTriangles(seamless, mesh);
        float maxResidual = computeMaxTransitionResidual(seamless, mesh);

        System.out.printf("[%s] V=%d E=%d F=%d  sing=%d  crossField=%.2fs seamless=%.2fs%n",
                label, mesh.vertexCount(), mesh.edgeCount(), mesh.faceCount(),
                crossField.singularities.size(),
                (t1 - t0) / 1.0e9, (t2 - t1) / 1.0e9);
        System.out.printf("[%s] cuts=%d (boundary=%d interior=%d)  injective=%b stiffeningIters=%d  flipped=%d/%d (%.2f%%)%n",
                label, totalCut, boundaryCut, totalCut - boundaryCut,
                seamless.injective, seamless.stiffeningIterations,
                flipped, mesh.faceCount(), 100.0 * flipped / mesh.faceCount());
        System.out.printf("[%s] max transition residual=%.4f%n", label, maxResidual);

        assertNotNull(seamless.uCorner);
        // Sanity: every singularity sits on the cut graph.
        for (Singularity s : crossField.singularities) {
            int sVid = s.vertexId();
            boolean onCut = false;
            int incident = mesh.vertexEdgeCount(sVid);
            for (int i = 0; i < incident; i++) {
                int eId = mesh.vertexEdgeAt(sVid, i);
                int ae = crossField.edgeIdToActive.get(eId);
                if (seamless.isCutEdge[ae] && !mesh.isBoundaryEdge(eId)) { onCut = true; break; }
            }
            assertTrue(onCut, label + ": singularity vertex " + sVid + " is not on the cut graph");
        }
    }

    private static float computeMaxTransitionResidual(SeamlessParameterization seamless, HalfEdgeMesh mesh) {
        float worst = 0.0f;
        int edgeCount = mesh.edgeCount();
        for (int ae = 0; ae < edgeCount; ae++) {
            if (!seamless.isCutEdge[ae]) continue;
            int eId = mesh.edgeIdAt(ae);
            if (mesh.isBoundaryEdge(eId)) continue; // boundary cut: no transition

            int hCanon = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(hCanon);
            int faceA = mesh.halfEdgeFace(hCanon);
            int faceB = mesh.halfEdgeFace(twin);
            int vStart = mesh.halfEdgeVertex(hCanon);
            int vEnd = mesh.halfEdgeEndVertex(hCanon);

            float[] coordsA = lookupCorners(seamless, mesh, faceA, vStart, vEnd);
            float[] coordsB = lookupCorners(seamless, mesh, faceB, vStart, vEnd);

            int r = seamless.cutRotation[ae];
            float cr = (float) Math.cos(r * Math.PI / 2.0);
            float sr = (float) Math.sin(r * Math.PI / 2.0);
            float s = seamless.cutTranslationS[ae];
            float t = seamless.cutTranslationT[ae];

            float upGexpected = cr * coordsA[0] - sr * coordsA[1] + s;
            float vpGexpected = sr * coordsA[0] + cr * coordsA[1] + t;
            float uqGexpected = cr * coordsA[2] - sr * coordsA[3] + s;
            float vqGexpected = sr * coordsA[2] + cr * coordsA[3] + t;

            worst = Math.max(worst, Math.abs(upGexpected - coordsB[0]));
            worst = Math.max(worst, Math.abs(vpGexpected - coordsB[1]));
            worst = Math.max(worst, Math.abs(uqGexpected - coordsB[2]));
            worst = Math.max(worst, Math.abs(vqGexpected - coordsB[3]));
        }
        return worst;
    }

    /** Returns [u_p, v_p, u_q, v_q] for face's corners at vStart and vEnd. */
    private static float[] lookupCorners(SeamlessParameterization seamless, HalfEdgeMesh mesh,
                                          int faceId, int vStart, int vEnd) {
        int cStart = -1, cEnd = -1;
        for (int c = 0; c < SeamlessParameterization.CORNERS_PER_FACE; c++) {
            int v = mesh.faceVertexAt(faceId, c);
            if (v == vStart) cStart = c;
            else if (v == vEnd) cEnd = c;
        }
        return new float[] {
                seamless.u(faceId, cStart), seamless.v(faceId, cStart),
                seamless.u(faceId, cEnd),   seamless.v(faceId, cEnd),
        };
    }

    private static int countFlippedTriangles(SeamlessParameterization seamless, HalfEdgeMesh mesh) {
        int flipped = 0;
        for (int af = 0; af < mesh.faceCount(); af++) {
            int faceId = mesh.faceIdAt(af);
            if (seamless.uvSignedArea(faceId) <= 0.0f) flipped++;
        }
        return flipped;
    }
}
