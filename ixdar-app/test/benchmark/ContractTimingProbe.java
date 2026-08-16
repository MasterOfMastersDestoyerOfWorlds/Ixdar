package benchmark;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.platform.Platforms;

/**
 * Wall-clock probe of the pipeline through contraction only, printing the
 * elapsed time and whether contraction completed; pick the mesh with
 * {@code -Dbenchmark.off}.
 */
public final class ContractTimingProbe {

    private static final String OFF_PROPERTY = "benchmark.off";
    private static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/botijo_in_tri.off";
    private static final double NANOS_PER_SECOND = 1.0e9;

    /**
     * Runs the engine through {@code buildContractedTMesh} and prints the wall
     * time, or the failure and the wall time when contraction throws.
     *
     * @throws IOException when the mesh file cannot be read
     */
    @Test
    public void timeContraction() throws IOException {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        QuadLayoutEngine engine = new QuadLayoutEngine(mesh,
                QuadLayoutEngine.DEFAULT_ALPHA_RADIANS);
        long start = System.nanoTime();
        try {
            engine.buildContractedTMesh();
            Platforms.log("[timing] %s contraction COMPLETE in %.3fs%n", offPath,
                    (System.nanoTime() - start) / NANOS_PER_SECOND);
        } catch (RuntimeException failure) {
            Platforms.log("[timing] %s contraction FAILED in %.3fs: %s%n", offPath,
                    (System.nanoTime() - start) / NANOS_PER_SECOND, failure.getMessage());
        }
    }
}
