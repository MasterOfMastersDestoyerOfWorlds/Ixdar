package benchmark;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.nodes.data.SceneModelNode;
import ixdar.nodegraph.nodes.quadlayout.QuadLayoutNode;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
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
     * Pulls the layout through the relaxed grid map and prints the wall time
     * of each stage group plus the grid-map stage on its own.
     *
     * @throws Exception when the mesh file cannot be read or a stage fails
     */
    @Test
    public void buildGlobalGridMap() throws Exception {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        NodeGraphRuntime layout = new NodeGraphRuntime(QuadLayoutNode.overSceneModel());
        layout.graphInputs.put(SceneModelNode.BINDING, GeometryBundle.ofMesh(mesh));

        long start = System.nanoTime();
        layout.pull(QuadLayoutNode.sceneOutput(QuadLayoutNode.SEAMLESS));
        long seamlessEnd = System.nanoTime();
        layout.pull(QuadLayoutNode.sceneOutput(QuadLayoutNode.TMESH));
        long contractedEnd = System.nanoTime();
        GlobalGridMap globalGrid = (GlobalGridMap) layout.pull(
                QuadLayoutNode.sceneOutput(QuadLayoutNode.UV));
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
                globalGrid.gridOptimizer.energyBefore,
                globalGrid.gridOptimizer.energyAfter,
                globalGrid.gridOptimizer.iterationCount,
                globalGrid.gridOptimizer.flippedTriangleCount);
    }
}
