package benchmark;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;
import ixdar.geometry.mesh.quadlayout.solver.chol.CholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.system.DofSystem;
import ixdar.platform.Platforms;

/**
 * Headless timing harness for the cross-field + seamless stages — not part of
 * the default test globs (package {@code benchmark}); run explicitly:
 *
 * <pre>
 * mvn test -pl ixdar-app -P test -Dtest=SeamlessPipelineBenchmark \
 *     -Dbenchmark.off=test/resources/quadlayout/figure_10/buddha_in_tri.off
 * </pre>
 *
 * Stage wall times come from the {@code [seamless timing]} /
 * {@code [cross-field timing]} console lines; this harness adds the totals and
 * the final metrics line for regression comparison. Add
 * {@code -Dbenchmark.forceEjml=true} to skip every native backend (PARDISO and
 * Accelerate) and measure the pure-Java EJML baseline on the same code.
 */
public final class SeamlessPipelineBenchmark {

    private static final String OFF_PROPERTY = "benchmark.off";
    private static final String FORCE_EJML_PROPERTY = "benchmark.forceEjml";
    private static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/fertility_in_tri.off";

    @Test
    public void buildSeamless() throws IOException {
        CholeskyBackend.forceEjml = Boolean.getBoolean(FORCE_EJML_PROPERTY);
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, QuadLayoutEngine.DEFAULT_ALPHA_RADIANS);

        long crossFieldStart = System.nanoTime();
        engine.buildCrossField();
        long seamlessStart = System.nanoTime();
        MapNodeContext seamlessCtx = new MapNodeContext(new SeamlessParameterization())
                .with(SeamlessParameterization.FIELD, engine.crossField)
                .eval();
        long seamlessEnd = System.nanoTime();
        SeamlessUv seamless = seamlessCtx.output(SeamlessParameterization.UV, SeamlessUv.class);

        Platforms.log("[benchmark] %s V=%d F=%d%n", offPath, mesh.vertexCount(), mesh.faceCount());
        Platforms.log("[benchmark] cross field total %.3fs, seamless total %.3fs%n",
                (seamlessStart - crossFieldStart) / 1.0e9,
                (seamlessEnd - seamlessStart) / 1.0e9);
        System.out.println("[benchmark] singularities=" + engine.crossField.singularityCount()
                + " flipped=" + seamlessCtx.output(SeamlessParameterization.FLIPPED_TRIANGLES, Integer.class)
                + " injective=" + seamless.injective);
        System.out.println("[benchmark] leftoverConstraints=" + seamless.cutGraph.leftoverConstraints.length
                + " dofCount=" + seamlessCtx.output(SeamlessParameterization.DOFS, DofSystem.class).dofCount);
    }
}
