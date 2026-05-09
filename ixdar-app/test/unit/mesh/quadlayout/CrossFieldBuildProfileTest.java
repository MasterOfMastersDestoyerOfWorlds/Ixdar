package unit.mesh.quadlayout;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ixdar.geometry.mesh.data.load.CrossFieldLoader;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.CrossField;

/**
 * Runs {@link CrossField#build()} on the hand mesh ({@code figure_6/hand_in_tri.off},
 * F=8480) with the {@code -DcrossField.profile=true} system property enabled so
 * the per-phase timings introduced in {@code CrossField} are emitted to stdout.
 * Use this test as a baseline for solver-performance work — BZK09 reports under
 * 10 s per cross field on 2009 consumer hardware, so anything materially slower
 * is a hot-spot worth investigating.
 *
 * <p>Generous 5-minute timeout: this test is for profiling, not regression.
 */
class CrossFieldBuildProfileTest {

    private static final Path HAND_OFF = Path.of(
            "test", "resources", "quadlayout", "figure_6", "hand_in_tri.off");
    private static final Path HAND_NDF = Path.of(
            "test", "resources", "quadlayout", "figure_6", "hand_in_cf.ndf");

    @Test
    @Timeout(value = 5, unit = java.util.concurrent.TimeUnit.MINUTES)
    void profileHandMeshBuild() throws IOException {
        System.setProperty("crossField.profile", "true");

        ArrayMesh arrayMesh = MeshLoader.load(HAND_OFF.toString());
        HalfEdgeMesh halfEdgeMesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());

        long start = System.nanoTime();
        CrossField cf = new CrossField(halfEdgeMesh);
        // Disable curvature constraints by raising the threshold; isolate solver
        // quality from constraint-pass quality.
        cf.curvatureScaleK = Float.parseFloat(System.getProperty("crossField.kScale", "0.1"));
        CrossField generated = cf.build();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        CrossField reference = CrossFieldLoader.load(HAND_NDF.toString(), halfEdgeMesh);
        // Reference NDF only carries solver outputs (theta, periodJump, singularities);
        // borrow the just-computed geometric arrays so we can score it on the same scale.
        reference.kappa = generated.kappa;
        reference.faceX = generated.faceX;
        reference.faceY = generated.faceY;
        reference.faceIdToActive = generated.faceIdToActive;
        reference.edgeIdToActive = generated.edgeIdToActive;

        int expectedCount = reference.singularities.size();
        int actualCount = generated.singularities.size();
        long expectedPositive = reference.singularities.stream().filter(s -> s.index4() > 0).count();
        long expectedNegative = reference.singularities.stream().filter(s -> s.index4() < 0).count();
        long actualPositive = generated.singularities.stream().filter(s -> s.index4() > 0).count();
        long actualNegative = generated.singularities.stream().filter(s -> s.index4() < 0).count();

        CrossField.SmoothnessStats refStats = reference.smoothnessStats();
        CrossField.SmoothnessStats genStats = generated.smoothnessStats();

        System.out.printf("[cross-field profile] hand mesh build wall time: %.2fs%n",
                elapsed.toMillis() / 1000.0);
        System.out.printf("[cross-field profile] singularities expected=%d (+%d/-%d) actual=%d (+%d/-%d)%n",
                expectedCount, expectedPositive, expectedNegative,
                actualCount, actualPositive, actualNegative);
        System.out.printf("[cross-field profile] smoothEnergy reference=%.4f generated=%.4f (delta=%+.4f)%n",
                refStats.energy(), genStats.energy(), genStats.energy() - refStats.energy());
        System.out.printf("[cross-field profile] maxResidual reference=%.4f generated=%.4f%n",
                refStats.maxResidual(), genStats.maxResidual());
    }
}
