package benchmark;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.platform.Platforms;

/**
 * Wall time and operator counts of the T-mesh contraction on one mesh ({@code -Dbenchmark.off}).
 */
public final class ContractionBenchmark {

    private static final String OFF_PROPERTY = "benchmark.off";
    private static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/botijo_in_tri.off";
    private static final double NANOS_PER_SECOND = 1.0e9;

    /**
     * Contracts the mesh's T-mesh the way the pipeline does and prints the wall time with the
     * operator counts at the fixed point, or the wall time and the diagnostic when it fails.
     *
     * @throws IOException when the mesh file cannot be read
     */
    @Test
    public void timeContraction() throws IOException {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, QuadLayoutEngine.DEFAULT_ALPHA_RADIANS);
        ArcNetwork tmesh = engine.buildTMesh();
        NetworkContraction contraction = new NetworkContraction(tmesh);
        long start = System.nanoTime();
        try {
            contraction.contract();
        } catch (RuntimeException failure) {
            Platforms.log("[benchmark] %s contraction FAILED in %.3fs: %s%n", offPath,
                    (System.nanoTime() - start) / NANOS_PER_SECOND, failure.getMessage());
            throw failure;
        }
        Platforms.log("[benchmark] %s contraction reached its fixed point in %.3fs"
                + " (collapses=%d patchCollapses=%d patchSplits=%d)%n", offPath,
                (System.nanoTime() - start) / NANOS_PER_SECOND, contraction.arcCollapseCount,
                contraction.patchCollapseCount, contraction.patchSplitCount);
    }
}
