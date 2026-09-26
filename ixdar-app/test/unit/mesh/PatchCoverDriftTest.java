package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.embedding.records.PatchCorridor;

/**
 * The contraction keeps a cover label per copy face, and the pipeline reads them as if they
 * were a fresh flood of each patch. These pin that the operators leave them saying so.
 *
 * <p>See also: LCBK19 Section 6.1
 */
class PatchCoverDriftTest {

    /** Refinement of the scaled fixture: fine enough that a drag sweeps many faces. */
    private static final int DENSE_SCALE = 4;

    /** The authored torus fixture, one zero row on a quantized grid of patches. */
    private static final String TORUS_DSL = "dsl/fixtures/torus_layout.dsl";

    /** Fixture output naming the authored arc network. */
    private static final String NETWORK_OUTPUT = "net";

    /** Failure text of a drag that found no edge path at all. */
    private static final String BLOCKED_DRAGS = "drags found no edge path inside the region their"
            + " arc separates";

    /** Failure text of a label that no longer says what a flood of its patch would. */
    private static final String DRIFTED_LABELS = "the contracted layout's cover labels disagree"
            + " with the patches they name";

    @Test
    void collapsingOneZeroArcKeepsEveryCoverLabelTrue() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(TORUS_DSL, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);
        fixtureNet.labelPatchCovers();
        NetworkContraction contraction = new NetworkContraction(fixtureNet);

        int arcId = contraction.collapseArc.mostContendedArc();
        assertNotEquals(ArcNetwork.NONE, arcId, "the zero row must offer a collapsible arc");
        contraction.collapseArc.collapse(arcId);

        assertEquals("", coverDrift(fixtureNet),
                "one operator-(1) collapse already left the cover labels disagreeing with the"
                        + " patches they name");
    }

    @Test
    void contractingTheTorusKeepsEveryCoverLabelTrue() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(TORUS_DSL, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);
        NetworkContraction contraction = new NetworkContraction(fixtureNet);
        contraction.contract();

        assertEquals(0, contraction.collapseArc.blockedDragCount, BLOCKED_DRAGS);
        assertEquals("", coverDrift(fixtureNet), DRIFTED_LABELS);
    }

    /**
     * The same contraction one operator at a time, so a drifting label names the operator that
     * left it rather than the round it was noticed in.
     */
    @Test
    void steppingTheTorusContractionKeepsEveryCoverLabelTrue() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(TORUS_DSL, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);
        fixtureNet.labelPatchCovers();
        NetworkContraction contraction = new NetworkContraction(fixtureNet);

        String applied = contraction.contractStep();
        while (applied != null) {
            assertEquals("", coverDrift(fixtureNet), "after " + applied);
            applied = contraction.contractStep();
        }
    }

    @Test
    void contractingTheStackedZeroRowTorusKeepsEveryCoverLabelTrue() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/stacked_zero_row_torus.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);
        NetworkContraction contraction = new NetworkContraction(fixtureNet);
        contraction.contract();

        assertEquals(0, contraction.collapseArc.blockedDragCount, BLOCKED_DRAGS);
        assertEquals("", coverDrift(fixtureNet), DRIFTED_LABELS);
    }

    @Test
    void contractingADenseTorusKeepsEveryCoverLabelTrue() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/scaled_torus.dsl", Map.of(
                "carrier.major_segments", 12 * DENSE_SCALE, "carrier.minor_segments", 8 * DENSE_SCALE));
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);
        NetworkContraction contraction = new NetworkContraction(fixtureNet);
        contraction.contract();

        assertEquals(0, contraction.collapseArc.blockedDragCount, BLOCKED_DRAGS);
        assertEquals("", coverDrift(fixtureNet), DRIFTED_LABELS);
    }

    /**
     * Compares the maintained cover labels against a fresh flood of every live patch, which is
     * what the labels are a cache of.
     *
     * @param tmesh contracted T-mesh whose labels are checked
     * @return an empty string when every label is true, else a description of the worst
     *         disagreement and how widespread it is
     */
    private static String coverDrift(ArcNetwork tmesh) {
        EmbeddedMeshTopology topology = tmesh.topology;
        HalfEdgeMesh copy = topology.copy;
        int orphaned = 0;
        for (int index = 0; index < copy.faceCount(); index++) {
            int label = topology.resolvePatch(topology.patchLabelOf(copy.faceIdAt(index)));
            if (label == EmbeddedMeshTopology.UNCLAIMED || !tmesh.patches.get(label).alive) {
                orphaned++;
            }
        }

        PatchCorridor corridor = new PatchCorridor(tmesh);
        int worstPatchId = ArcNetwork.NONE;
        int worstStolen = 0;
        int worstTrueFaces = 0;
        int worstThief = ArcNetwork.NONE;
        int driftingPatches = 0;
        int stolenTotal = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            IntIdList trueFaces = corridor.patchFaces(patch.patchId);
            int stolen = 0;
            int thief = ArcNetwork.NONE;
            for (int index = 0; index < trueFaces.size(); index++) {
                int label = topology.resolvePatch(topology.patchLabelOf(trueFaces.get(index)));
                if (label != patch.patchId) {
                    stolen++;
                    thief = label;
                }
            }
            if (stolen == 0) {
                continue;
            }
            driftingPatches++;
            stolenTotal += stolen;
            if (stolen > worstStolen) {
                worstStolen = stolen;
                worstPatchId = patch.patchId;
                worstTrueFaces = trueFaces.size();
                worstThief = thief;
            }
        }
        if (driftingPatches == 0 && orphaned == 0) {
            return "";
        }
        return driftingPatches + " of the live patches are mislabeled, " + stolenTotal + " of "
                + copy.faceCount() + " faces in all, and " + orphaned + " carry no live patch at"
                + " all; worst is patch " + worstPatchId + ", which covers " + worstTrueFaces
                + " faces but has lost " + worstStolen + " of them to patch " + worstThief;
    }
}
