package unit.mesh;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ScaledTorusLayoutFixture;

class PlaneContractionSplitMeasureTest {

    @Test
    void classifySplitCauses() {
        System.setProperty("embeddedTMesh.classifySplits", "true");
        try {
            for (int scale : new int[] { 1, 4, 16 }) {
                ScaledTorusLayoutFixture fixture = new ScaledTorusLayoutFixture(scale);
                int before = fixture.topology.copy.vertexCount();
                EmbeddedContraction contraction = new EmbeddedContraction(fixture.tmesh,
                        ScaledTorusLayoutFixture.TORUS_EULER_CHARACTERISTIC).contract();
                int splits = fixture.topology.copy.vertexCount() - before;

                System.out.println("[scale=" + scale + "] verts=" + before + " meshGrowth=" + splits
                        + " | collapse" + counters(contraction.collapseArc.rerouter)
                        + " | split" + counters(contraction.splitPatch.rerouter));
            }
        } finally {
            System.clearProperty("embeddedTMesh.classifySplits");
        }
    }

    private String counters(ArcRerouter r) {
        return "[refined=" + r.refinedEdgeSplitCount + " gate=" + r.gateSplitCount
                + " blocked=" + r.blockedSplitCount + " spoke=" + r.spokeSplitCount
                + " | blockedFallback avoidable=" + r.blockedFallbackAvoidable + "("
                + r.blockedSplitsAvoidable + " splits) genuine=" + r.blockedFallbackGenuine + "]";
    }
}
