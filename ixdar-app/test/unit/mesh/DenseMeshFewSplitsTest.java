package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedContraction;

/**
 * Refining the triangle mesh under a fixed layout must not multiply the edge splits the contraction
 * makes. A mesh that is dense relative to its arcs has ample free vertices for the re-router, so it
 * should refine almost none of them — the number of splits is a property of the layout, not of how
 * finely the surface is triangulated.
 *
 * <p>This is the counterfactual behind the fertility slowdown: there the working copy inflates 18×
 * during contraction, which would be inevitable if splits scaled with mesh size. They do not, so
 * that inflation is a real defect elsewhere (arc paths bloating as they are dragged), not the cost
 * of a fine mesh.
 *
 * <p>The layout is on a closed torus rather than a planar disk because contracting a bounded surface
 * needs LCBK19 §6.1's border rule, which drags a border arc onto a joint path — currently
 * unimplemented, so a disk collapse pivots off the mesh boundary and throws.
 */
class DenseMeshFewSplitsTest {

    /** A refinement fine enough that any density-proportional split cost would dwarf the layout's. */
    private static final int DENSE_SCALE = 8;

    /** A coarse baseline the dense run is compared against. */
    private static final int COARSE_SCALE = 1;

    /** The dense mesh must exceed the layout's arc count by at least this factor to be "dense". */
    private static final int DENSITY_FACTOR = 100;

    /** The dense run's splits may exceed the coarse run's by at most this factor. */
    private static final double SPLIT_GROWTH_TOLERANCE = 2.0;

    @Test
    void refiningTheMeshDoesNotMultiplyEdgeSplits() {
        int coarseSplits = contractAndCountSplits(COARSE_SCALE, false);
        int denseSplits = contractAndCountSplits(DENSE_SCALE, true);

        assertTrue(denseSplits <= coarseSplits * SPLIT_GROWTH_TOLERANCE,
                "refining the mesh " + DENSE_SCALE + "x raised edge splits from " + coarseSplits
                        + " to " + denseSplits + "; splits must track the layout, not the mesh, or"
                        + " a dense surface inflates without bound during contraction");
    }

    /**
     * Contracts the scaled fixture and returns how many vertices the working copy gained, which is
     * exactly the number of edge splits since each split adds one vertex.
     *
     * @param scale             refinement scale for the fixture
     * @param requireMuchDenser whether to assert the mesh dwarfs the arc count at this scale
     * @return the edge-split count
     */
    private int contractAndCountSplits(int scale, boolean requireMuchDenser) {
        ScaledTorusLayoutFixture fixture = new ScaledTorusLayoutFixture(scale);
        int verticesBefore = fixture.topology.copy.vertexCount();
        long liveArcs = fixture.tmesh.arcs.stream().filter(arc -> arc.alive).count();
        new EmbeddedContraction(fixture.tmesh,
                ScaledTorusLayoutFixture.TORUS_EULER_CHARACTERISTIC).contract();

        int splits = fixture.topology.copy.vertexCount() - verticesBefore;
        if (requireMuchDenser) {
            assertTrue(verticesBefore > liveArcs * DENSITY_FACTOR,
                    "the fixture at scale " + scale + " has " + verticesBefore + " vertices for "
                            + liveArcs + " arcs, which is not dense enough to test the claim");
            assertTrue(splits < verticesBefore / 10,
                    "at scale " + scale + " the contraction split " + splits + " edges into a "
                            + verticesBefore + "-vertex mesh; a mesh dense relative to its arcs"
                            + " should refine almost none of it");
        }
        return splits;
    }
}
