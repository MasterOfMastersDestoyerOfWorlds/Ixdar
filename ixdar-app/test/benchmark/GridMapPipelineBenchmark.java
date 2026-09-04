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
 * Wall-clock benchmark of the pipeline through the grid-map relaxation,
 * printing per-stage times; pick the mesh with {@code -Dbenchmark.off}.
 */
public final class GridMapPipelineBenchmark {

    private static final String OFF_PROPERTY = "benchmark.off";
    private static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/fertility_in_tri.off";
    private static final double NANOS_PER_SECOND = 1.0e9;

    /**
     * Runs the engine through {@code buildGlobalGridMap} and prints the wall time
     * of each stage group plus the grid-map stage on its own.
     *
     * @throws IOException when the mesh file cannot be read
     */
    @Test
    public void buildGlobalGridMap() throws IOException {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, QuadLayoutEngine.DEFAULT_ALPHA_RADIANS);

        long start = System.nanoTime();
        engine.buildSeamless();
        long seamlessEnd = System.nanoTime();
        engine.buildContractedTMesh();
        long contractedEnd = System.nanoTime();
        engine.buildGlobalGridMap();
        long gridMapEnd = System.nanoTime();

        Platforms.log("[benchmark] %s V=%d F=%d%n", offPath, mesh.vertexCount(),
                mesh.faceCount());
        Platforms.log(
                "[benchmark] cross field+seamless %.3fs, contract %.3fs,"
                        + " grid map %.3fs, total %.3fs%n",
                (seamlessEnd - start) / NANOS_PER_SECOND,
                (contractedEnd - seamlessEnd) / NANOS_PER_SECOND,
                (gridMapEnd - contractedEnd) / NANOS_PER_SECOND,
                (gridMapEnd - start) / NANOS_PER_SECOND);
        Platforms.log("[benchmark] grid-optimize energy %.6e -> %.6e iterations=%d"
                + " flipped=%d%n",
                engine.globalGrid.gridOptimizer.energyBefore,
                engine.globalGrid.gridOptimizer.energyAfter,
                engine.globalGrid.gridOptimizer.iterationCount,
                engine.globalGrid.gridOptimizer.flippedTriangleCount);
    }
}
