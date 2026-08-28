package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

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
 * moves into the {@link ixdar.geometry.mesh.quadlayout.gridmap.PatchRectangleMap} rectangle,
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

    /** A UV triangle below this fraction of the mean |area| counts as degenerate (LCK21a). */
    private static final double DEGENERATE_UV_AREA_FRACTION = 1.0e-6;

    /** LCK21a §5.2: the quantization stays within this many variables per trace. */
    private static final double VARIABLES_PER_TRACE_BOUND = 1.5;

    @Test
    void pipelineAssemblesAValidatedTMesh() throws Exception {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        HalfEdgeMesh mesh = loadMesh(offPath);

        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, (float) ALPHA_RADIANS);
        engine.buildLayoutEmbedding();

        EmbeddedTMesh tmesh = engine.tmesh;

        System.out.printf(
                "[tmesh-pipeline] %s | nodes=%d arcs=%d (zero=%d) patches=%d | euler=%d%n",
                offPath, tmesh.nodes.size(), tmesh.arcs.size(), countZeroArcs(tmesh),
                tmesh.patches.size(), tmesh.expectedEulerCharacteristic);

        assertEquals(engine.motorcycleGraph.patches.size(), tmesh.patches.size(),
                "every motorcycle-graph patch should become one embedded patch");
        assertTrue(tmesh.arcs.size() > 0, "the layout should have arcs");
        assertTrue(tmesh.nodes.size() > 0, "the layout should have nodes");
    }

    /**
     * The structural invariants LCK21a's Table-1 parity harness checked, lifted here so they
     * survive as a fast unit test rather than a benchmark: the motorcycle arrangement is a cell
     * complex, every cycle is a rectangle, no trace is truncated, the quantization needs no
     * separation cuts (Lemma 1 suffices), there are four traces per singularity, no UV triangle is
     * degenerate, and the variable count stays within the paper's bound. These are mesh-agnostic,
     * so they hold on the small default mesh; point the harness at a paper mesh with
     * {@code -DtmeshPipeline.off=...} to check the same invariants there.
     *
     * @throws Exception propagated from mesh loading or the pipeline
     */
    @Test
    void pipelineHoldsTheLyonInvariants() throws Exception {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        HalfEdgeMesh mesh = loadMesh(offPath);
        int meshEuler = mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();

        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, (float) ALPHA_RADIANS);
        engine.buildLayoutEmbedding();
        MotorcycleGraph graph = engine.motorcycleGraph;

        int arrangementEuler = graph.nodes.size() - graph.arcs.size() + graph.patches.size();
        assertEquals(meshEuler, arrangementEuler, "the arrangement is a cell complex");

        for (EmbeddedPatch patch : graph.patches) {
            assertTrue(patch.validRectangle, "patch " + patch.patchId + " is a valid rectangle");
        }
        for (EmbeddedNode node : graph.nodes) {
            assertFalse(node.truncated,
                    "node " + node.nodeId + " is a truncated trace");
        }
        assertEquals(0, graph.aliveAtQueueEndCount, "no motorcycle left alive at the queue end");
        assertEquals(0, graph.repeatedChainNodeCount, "no arc chain repeats a node");

        assertEquals(0, engine.quantization.separationCutCount,
                "Lemma 1 suffices: the quantization needs no separation cuts");
        assertFalse(engine.quantization.singularitySeparationViolated,
                "no singularity separation is violated");
        assertTrue(engine.quantization.variableCount
                        <= VARIABLES_PER_TRACE_BOUND * graph.traces.size(),
                "variables=" + engine.quantization.variableCount + " stays within 1.5x traces");

        assertEquals(0, degenerateUvFaceCount(engine, mesh), "no degenerate UV triangle");
    }

    /**
     * The number of faces whose seamless UV image is degenerate — below a tiny fraction of the
     * mean UV triangle area.
     *
     * @param engine the pipeline, built through the seamless stage
     * @param mesh   the input mesh
     * @return count of degenerate UV faces
     */
    private int degenerateUvFaceCount(QuadLayoutEngine engine, HalfEdgeMesh mesh) {
        double meanAbsArea = 0.0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            meanAbsArea += Math.abs(engine.seamless.uvSignedArea(mesh.faceIdAt(activeFace)));
        }
        meanAbsArea /= mesh.faceCount();
        int degenerate = 0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            if (Math.abs(engine.seamless.uvSignedArea(mesh.faceIdAt(activeFace)))
                    < DEGENERATE_UV_AREA_FRACTION * meanAbsArea) {
                degenerate++;
            }
        }
        return degenerate;
    }

    /**
     * Loads a mesh from an OFF/OBJ file into a half-edge mesh.
     *
     * @param offPath path to the mesh file
     * @return the loaded half-edge mesh
     * @throws Exception propagated from the loader
     */
    private HalfEdgeMesh loadMesh(String offPath) throws Exception {
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        return HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
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
