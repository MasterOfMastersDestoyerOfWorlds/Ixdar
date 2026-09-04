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
 * The contraction restricts each drag's re-route to the two patches flanking the dragged arc,
 * reading the patch a copy face belongs to from the cover labels it maintains. That is only the
 * region LCBK19 §6.1 allows if the labels keep saying what a fresh flood of the patch would say.
 *
 * <p>A drag with no route inside those two patches has nowhere else to go, so the count of
 * blocked drags is an assertion here rather than a statistic.
 *
 * <p>See also: LCBK19 Section 6.1
 */
class PatchCoverDriftTest {

    /** Refinement of the scaled fixture: fine enough that a drag sweeps many faces. */
    private static final int DENSE_SCALE = 4;

    @Test
    void collapsingOneZeroArcKeepsEveryCoverLabelTrue() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
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
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        NetworkContraction contraction = new NetworkContraction(fixtureNet);
        contraction.contract();

        assertEquals(0, contraction.collapseArc.blockedDragCount,
                "drags found no route inside the patches their arc separates");
        assertEquals("", coverDrift(fixtureNet),
                "the contracted layout's cover labels disagree with the patches they name");
    }

    @Test
    void contractingTheStackedZeroRowTorusKeepsEveryCoverLabelTrue() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/stacked_zero_row_torus.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        NetworkContraction contraction = new NetworkContraction(fixtureNet);
        contraction.contract();

        assertEquals(0, contraction.collapseArc.blockedDragCount,
                "drags found no route inside the patches their arc separates");
        assertEquals("", coverDrift(fixtureNet),
                "the contracted layout's cover labels disagree with the patches they name");
    }

    @Test
    void contractingADenseTorusKeepsEveryCoverLabelTrue() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/scaled_torus.dsl", Map.of(
                "carrier.major_segments", 12 * DENSE_SCALE, "carrier.minor_segments", 8 * DENSE_SCALE));
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        NetworkContraction contraction = new NetworkContraction(fixtureNet);
        contraction.contract();

        assertEquals(0, contraction.collapseArc.blockedDragCount,
                "drags found no route inside the patches their arc separates");
        assertEquals("", coverDrift(fixtureNet),
                "the contracted layout's cover labels disagree with the patches they name");
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
