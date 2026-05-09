package unit.mesh.quadlayout;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

    @Test
    @Timeout(value = 5, unit = java.util.concurrent.TimeUnit.MINUTES)
    void profileHandMeshBuild() throws IOException {
        System.setProperty("crossField.profile", "true");

        ArrayMesh arrayMesh = MeshLoader.load(HAND_OFF.toString());
        HalfEdgeMesh halfEdgeMesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());

        long start = System.nanoTime();
        new CrossField(halfEdgeMesh).build();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        System.out.printf("[cross-field profile] hand mesh build wall time: %.2fs%n",
                elapsed.toMillis() / 1000.0);
    }
}
