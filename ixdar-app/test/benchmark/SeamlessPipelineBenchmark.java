package benchmark;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.data.SceneModelNode;
import ixdar.geometry.mesh.quadlayout.nodes.QuadLayoutNode;
import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;
import ixdar.numeric.sparse.chol.CholeskyBackend;
import ixdar.numeric.sparse.system.DofSystem;
import ixdar.platform.Platforms;

/**
 * Headless timing harness for the cross-field and seamless stages, run explicitly with
 * {@code -Dtest=SeamlessPipelineBenchmark -Dbenchmark.off=<mesh>}.
 *
 * <p>
 * {@code -Dbenchmark.forceEjml=true} skips every native backend to measure the pure-Java EJML
 * baseline.
 */
public final class SeamlessPipelineBenchmark {

    private static final String OFF_PROPERTY = "benchmark.off";
    private static final String FORCE_EJML_PROPERTY = "benchmark.forceEjml";
    private static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/fertility_in_tri.off";

    /**
     * Times the cross field and the seamless solve on one mesh and prints the totals and the
     * metrics line for regression comparison.
     *
     * @throws Exception when the mesh cannot be read or a stage fails
     */
    @Test
    public void buildSeamless() throws Exception {
        CholeskyBackend.forceEjml = Boolean.getBoolean(FORCE_EJML_PROPERTY);
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        NodeGraphRuntime layout = new NodeGraphRuntime(QuadLayoutNode.overSceneModel());
        layout.graphInputs.put(SceneModelNode.BINDING, GeometryBundle.ofMesh(mesh));

        long crossFieldStart = System.nanoTime();
        CrossField crossField = (CrossField) layout.pull(
                QuadLayoutNode.sceneOutput(QuadLayoutNode.FIELD));
        long seamlessStart = System.nanoTime();
        MapNodeContext seamlessCtx = new MapNodeContext(new SeamlessParameterization())
                .with(SeamlessParameterization.FIELD, crossField)
                .eval();
        long seamlessEnd = System.nanoTime();
        SeamlessUv seamless = seamlessCtx.output(SeamlessParameterization.UV, SeamlessUv.class);

        Platforms.log("[benchmark] %s V=%d F=%d%n", offPath, mesh.vertexCount(), mesh.faceCount());
        Platforms.log("[benchmark] cross field total %.3fs, seamless total %.3fs%n",
                (seamlessStart - crossFieldStart) / 1.0e9,
                (seamlessEnd - seamlessStart) / 1.0e9);
        System.out.println("[benchmark] singularities=" + crossField.singularityCount()
                + " flipped=" + seamlessCtx.output(SeamlessParameterization.FLIPPED_TRIANGLES, Integer.class)
                + " injective=" + seamless.injective);
        System.out.println("[benchmark] leftoverConstraints=" + seamless.cutGraph.leftoverConstraints.length
                + " dofCount=" + seamlessCtx.output(SeamlessParameterization.DOFS, DofSystem.class).dofCount);
    }
}
