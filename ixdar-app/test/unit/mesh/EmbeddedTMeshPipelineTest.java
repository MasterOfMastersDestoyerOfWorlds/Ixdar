package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.data.SceneModelNode;
import ixdar.geometry.mesh.quadlayout.nodes.QuadLayoutNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;

/**
 * Drives the real pipeline into the embedded T-mesh by pulling {@link QuadLayoutNode} through the
 * carve, whose build validates the cell decomposition against the surface's Euler characteristic.
 *
 * <p>It stops before contraction. The default mesh is small; point it at a paper benchmark with
 * {@code -DtmeshPipeline.off=test/resources/quadlayout/figure_8/fertility_in_tri.off}.
 */
class EmbeddedTMeshPipelineTest {

    private static final String OFF_PROPERTY = "tmeshPipeline.off";
    private static final String DEFAULT_OFF =
            "test/resources/quadlayout/figure_7/sphere_base_in_tri.off";
    private static final double ALPHA_RADIANS = Math.toRadians(15.0);

    // LCK21a: a UV triangle below this fraction of the mean |area| counts as degenerate.
    private static final double DEGENERATE_UV_AREA_FRACTION = 1.0e-6;

    // LCK21a §5.2: the quantization stays within this many variables per trace.
    private static final double VARIABLES_PER_TRACE_BOUND = 1.5;

    @Test
    void pipelineAssemblesAValidatedTMesh() throws Exception {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        HalfEdgeMesh mesh = loadMesh(offPath);

        NodeGraphRuntime layout = layoutOver(mesh);
        ArcNetwork tmesh = (ArcNetwork) layout.pull(QuadLayoutNode.sceneOutput(QuadLayoutNode.EMBEDDED));
        ArcNetwork arrangement = (ArcNetwork) layout.pull(
                QuadLayoutNode.sceneOutput(QuadLayoutNode.MOTORCYCLE));

        System.out.printf(
                "[tmesh-pipeline] %s | nodes=%d arcs=%d (zero=%d) patches=%d | euler=%d%n",
                offPath, tmesh.nodes.size(), tmesh.arcs.size(), countZeroArcs(tmesh),
                tmesh.patches.size(), tmesh.expectedEulerCharacteristic);

        assertEquals(arrangement.patches.size(), tmesh.patches.size(),
                "every motorcycle-graph patch should become one embedded patch");
        assertTrue(tmesh.arcs.size() > 0, "the layout should have arcs");
        assertTrue(tmesh.nodes.size() > 0, "the layout should have nodes");
    }

    /**
     * The mesh-agnostic structural invariants of LCK21a's Table-1 parity harness: a rectangular
     * cell complex, no truncated trace, no separation cut, no degenerate UV triangle, bounded
     * variables.
     *
     * @throws Exception propagated from mesh loading or the pipeline
     */
    @Test
    void pipelineHoldsTheLyonInvariants() throws Exception {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        HalfEdgeMesh mesh = loadMesh(offPath);
        int meshEuler = mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();

        NodeGraphRuntime layout = layoutOver(mesh);
        layout.pull(QuadLayoutNode.sceneOutput(QuadLayoutNode.EMBEDDED));
        ArcNetwork graph = (ArcNetwork) layout.pull(QuadLayoutNode.sceneOutput(QuadLayoutNode.MOTORCYCLE));

        int arrangementEuler = graph.nodes.size() - graph.arcs.size() + graph.patches.size();
        assertEquals(meshEuler, arrangementEuler, "the arrangement is a cell complex");

        for (EmbeddedPatch patch : graph.patches) {
            assertTrue(patch.validRectangle, "patch " + patch.patchId + " is a valid rectangle");
        }
        for (EmbeddedNode node : graph.nodes) {
            assertFalse(node.truncated,
                    "node " + node.nodeId + " is a truncated trace");
        }
        assertEquals(0, layout.pull("layout/motorcycle.orphaned_traces"),
                "no motorcycle left alive at the queue end");
        assertEquals(0, layout.pull("layout/motorcycle.repeated_chain_nodes"),
                "no arc chain repeats a node");

        assertEquals(0, layout.pull("layout/quantize.separation_cuts"),
                "Lemma 1 suffices: the quantization needs no separation cuts");
        assertEquals(false, layout.pull("layout/quantize.separation_violated"),
                "no singularity separation is violated");
        int variables = (Integer) layout.pull("layout/quantize.variables");
        assertTrue(variables <= VARIABLES_PER_TRACE_BOUND * graph.traces.size(),
                "variables=" + variables + " stays within 1.5x traces");

        SeamlessUv seamless = (SeamlessUv) layout.pull(QuadLayoutNode.sceneOutput(QuadLayoutNode.SEAMLESS));
        double meanAbsArea = 0.0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            meanAbsArea += Math.abs(seamless.uvSignedArea(mesh.faceIdAt(activeFace)));
        }
        meanAbsArea /= mesh.faceCount();
        int degenerate = 0;
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            if (Math.abs(seamless.uvSignedArea(mesh.faceIdAt(activeFace)))
                    < DEGENERATE_UV_AREA_FRACTION * meanAbsArea) {
                degenerate++;
            }
        }
        assertEquals(0, degenerate, "no degenerate UV triangle");
    }

    /**
     * The quad-layout graph over a mesh at the test's alpha, nothing evaluated yet.
     *
     * @param mesh the input mesh
     * @return the runtime to pull stage outputs from
     */
    private static NodeGraphRuntime layoutOver(HalfEdgeMesh mesh) {
        NodeGraphRuntime layout = new NodeGraphRuntime(QuadLayoutNode.overSceneModel());
        layout.graphInputs.put(SceneModelNode.BINDING, GeometryBundle.ofMesh(mesh));
        layout.setOverride(QuadLayoutNode.ALPHA_DEGREES_OVERRIDE, (float) Math.toDegrees(ALPHA_RADIANS));
        return layout;
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
    private int countZeroArcs(ArcNetwork tmesh) {
        int zero = 0;
        for (int arcId = 0; arcId < tmesh.arcs.size(); arcId++) {
            if (tmesh.arcs.get(arcId).alive && tmesh.arcs.get(arcId).quantizedLength == 0) {
                zero++;
            }
        }
        return zero;
    }
}
