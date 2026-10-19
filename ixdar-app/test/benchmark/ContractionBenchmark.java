package benchmark;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.nodes.data.SceneModelNode;
import ixdar.nodegraph.nodes.quadlayout.QuadLayoutNode;
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
     * @throws Exception when the mesh file cannot be read or a stage fails
     */
    @Test
    public void timeContraction() throws Exception {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        HalfEdgeMesh mesh = (HalfEdgeMesh) MeshLoader.load(offPath);
        mesh.computeNormals();
        NodeGraphRuntime layout = new NodeGraphRuntime(QuadLayoutNode.overSceneModel());
        layout.graphInputs.put(SceneModelNode.BINDING, GeometryBundle.ofMesh(mesh));
        ArcNetwork tmesh = (ArcNetwork) layout.pull(QuadLayoutNode.sceneOutput(QuadLayoutNode.EMBEDDED));
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
