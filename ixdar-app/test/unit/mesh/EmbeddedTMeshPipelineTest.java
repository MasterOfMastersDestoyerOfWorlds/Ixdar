package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMeshBuilder;

/**
 * Drives the real pipeline into the embedded T-mesh: load a mesh, run
 * {@link QuadLayoutEngine} through the carve, and assemble an {@link EmbeddedTMesh} with
 * {@link EmbeddedTMeshBuilder}. The assembly is the checkpoint — its {@code build()} validates the
 * result against the surface's Euler characteristic — so this proves the pipeline-derived nodes,
 * arcs, quantized lengths, carved paths, and four-sided patches form a cell decomposition, on real
 * data rather than only the hand-authored fixture.
 *
 * <p>Contracting this raw T-mesh with the operators is a separate, later step: on dense pipeline
 * meshes the operator-(1) Dijkstra re-route cannot always find an unclaimed corridor, so routing
 * moves into the {@link ixdar.geometry.mesh.quadlayout.embedding.PatchRectangleMap} rectangle,
 * which is not yet wired into the operators. This test therefore stops at the validated build.
 *
 * <p>The default mesh is small so the test stays cheap; point it at the paper benchmarks with
 * {@code -DtmeshPipeline.off=test/resources/quadlayout/figure_8/fertility_in_tri.off}.
 */
class EmbeddedTMeshPipelineTest {

    private static final String OFF_PROPERTY = "tmeshPipeline.off";
    private static final String DEFAULT_OFF =
            "test/resources/quadlayout/figure_7/sphere_base_in_tri.off";
    private static final double ALPHA_RADIANS = Math.toRadians(15.0);

    @Test
    void pipelineAssemblesAValidatedTMesh() throws Exception {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());

        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, (float) ALPHA_RADIANS);
        engine.buildLayoutEmbedding();

        EmbeddedTMeshBuilder builder = new EmbeddedTMeshBuilder(engine.embedding);
        EmbeddedTMesh tmesh = builder.build();

        System.out.printf(
                "[tmesh-pipeline] %s | nodes=%d arcs=%d (zero=%d) patches=%d | euler=%d%n",
                offPath, tmesh.nodes.size(), tmesh.arcs.size(), countZeroArcs(tmesh),
                tmesh.patches.size(), builder.expectedEulerCharacteristic);

        assertEquals(engine.motorcycleGraph.patches.size(), tmesh.patches.size(),
                "every motorcycle-graph patch should become one embedded patch");
        assertTrue(tmesh.arcs.size() > 0, "the layout should have arcs");
        assertTrue(tmesh.nodes.size() > 0, "the layout should have nodes");
    }

    /**
     * The number of live zero-length arcs in a T-mesh.
     *
     * @param tmesh T-mesh to scan
     * @return count of live arcs whose quantized length is zero
     */
    private int countZeroArcs(EmbeddedTMesh tmesh) {
        int zero = 0;
        for (int arcId = 0; arcId < tmesh.arcs.size(); arcId++) {
            if (tmesh.arcs.get(arcId).alive && tmesh.arcs.get(arcId).quantizedLength == 0) {
                zero++;
            }
        }
        return zero;
    }
}
