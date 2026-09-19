package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.seamless.SeamlessDofSystem;
import ixdar.geometry.mesh.quadlayout.solver.InteriorPointQp;
import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;

/**
 * The BZK09 §5.2 feature-alignment guard on a hand-authored two-triangle seamless
 * system, whose numbers are the shape of bolt's activeEdge 3213.
 *
 * <p>
 * Pinning a feature edge whose two faces run it opposite ways leaves their BCE13
 * §3.1 sector constraints jointly infeasible.
 */
class FeatureAlignmentGuardTest {

    /** Edge direction in the first face's target frame: mostly along u. */
    private static final double[] ALONG_U_IN_FIRST = { 0.93, 0.11 };

    /** The same edge in the second face's frame when the two targets agree. */
    private static final double[] ALONG_U_IN_SECOND = { 0.88, -0.06 };

    /** The same edge in the second face when the target runs it the other way. */
    private static final double[] REVERSED_IN_SECOND = { -0.85, 0.2 };

    /** The same edge in the second face when that face calls v the along coordinate. */
    private static final double[] ALONG_V_IN_SECOND = { 0.12, 0.91 };

    /** First face's sector normal, whose v component points one way. */
    private static final double[] FIRST_SECTOR_NORMAL = { 1.0, 0.3 };

    /** Second face's sector normal, whose v component points the other way. */
    private static final double[] SECOND_SECTOR_NORMAL = { 1.0, -0.4 };

    /** The ε margin BCE13 Equation 4 keeps each corner inside its sector by. */
    private static final double SECTOR_MARGIN = 0.05;

    /** Components of the shared edge's parametric offset, u then v. */
    private static final int OFFSET_COMPONENTS = 2;

    /** Slack allowed when a solved offset is checked against the margin. */
    private static final double MARGIN_TOLERANCE = 1.0e-9;

    /**
     * Two faces that agree on which coordinate runs along the feature edge and on
     * which way it runs keep the alignment row, pinning the other coordinate.
     */
    @Test
    void agreeingFacesPinTheIsoCoordinate() {
        assertEquals(SeamlessDofSystem.ALIGN_AXIS_V,
            SeamlessDofSystem.alignmentIsoAxis(ALONG_U_IN_FIRST, ALONG_U_IN_SECOND),
            "the edge runs along u in both faces, so v is the iso coordinate");
    }

    /**
     * A boundary feature edge has only one face to ask, so its own frame decides.
     */
    @Test
    void aBoundaryFeatureEdgeIsPinnedFromItsOneFace() {
        assertEquals(SeamlessDofSystem.ALIGN_AXIS_V,
            SeamlessDofSystem.alignmentIsoAxis(ALONG_U_IN_FIRST, null),
            "a boundary edge has no second face to disagree with");
    }

    /**
     * Bolt's case: both faces call u the along coordinate but their targets run the
     * edge opposite ways, so the row is withdrawn.
     */
    @Test
    void facesRunningTheEdgeOppositeWaysDropTheRow() {
        assertEquals(SeamlessDofSystem.NOT_ALIGNMENT,
            SeamlessDofSystem.alignmentIsoAxis(ALONG_U_IN_FIRST, REVERSED_IN_SECOND),
            "the two faces disagree on which way the edge runs");
    }

    /**
     * A feature edge the field has rotated by about a quarter turn across is along u
     * in one face and along v in the other, which is also unpinnable.
     */
    @Test
    void facesDisagreeingOnTheAxisDropTheRow() {
        assertEquals(SeamlessDofSystem.NOT_ALIGNMENT,
            SeamlessDofSystem.alignmentIsoAxis(ALONG_U_IN_FIRST, ALONG_V_IN_SECOND),
            "the two faces disagree on which coordinate runs along the edge");
    }

    /**
     * With the row kept, the shared edge's parametric offset is confined to the u
     * axis and the two faces' sector constraints demand opposite u extents, which
     * the solver reports as an infeasible active set with a Farkas certificate.
     */
    @Test
    void pinningADisagreeingEdgeMakesTheSectorConstraintsInfeasible() {
        InteriorPointQp solver = sectorConstraintsOverOffset(true);
        double[] offset = new double[1];
        solver.solve(offset);

        assertTrue(solver.infeasible, "the pinned system admits no consistently oriented offset");
        assertTrue(solver.certificateValue > 0.0, "the certificate proves it rather than stalling");
    }

    /**
     * Dropping the row gives the offset its v component back, and the same two
     * sector constraints are then satisfied with room to spare.
     */
    @Test
    void droppingTheRowLeavesTheSectorConstraintsSatisfiable() {
        InteriorPointQp solver = sectorConstraintsOverOffset(false);
        double[] offset = new double[OFFSET_COMPONENTS];
        solver.solve(offset);

        assertTrue(solver.converged, "the unpinned system converges");
        assertFalse(solver.infeasible, "the unpinned system is feasible");
        assertTrue(dot(FIRST_SECTOR_NORMAL, offset) >= SECTOR_MARGIN - MARGIN_TOLERANCE,
            "the first face's sector constraint is satisfied");
        assertTrue(-dot(SECOND_SECTOR_NORMAL, offset) >= SECTOR_MARGIN - MARGIN_TOLERANCE,
            "the second face's sector constraint is satisfied");
    }

    /**
     * The two faces' sector constraints on the shared edge's parametric offset,
     * either over u alone (the alignment row pins v to zero) or over both.
     *
     * @param pinned whether the alignment row has eliminated the v component
     * @return the solver over the surviving components
     */
    private InteriorPointQp sectorConstraintsOverOffset(boolean pinned) {
        int components = pinned ? 1 : OFFSET_COMPONENTS;
        double[] diagonal = new double[components];
        Arrays.fill(diagonal, 1.0);
        NormalMatrix energy = new NormalMatrix(diagonal, new HashMap<>(), new double[components]);
        int[] dofs = new int[components];
        for (int component = 0; component < components; component++) {
            dofs[component] = component;
        }
        double[] first = new double[components];
        double[] second = new double[components];
        for (int component = 0; component < components; component++) {
            first[component] = FIRST_SECTOR_NORMAL[component];
            second[component] = -SECOND_SECTOR_NORMAL[component];
        }
        return new InteriorPointQp(energy, new int[][] { dofs, dofs },
            new double[][] { first, second },
            new double[] { SECTOR_MARGIN, SECTOR_MARGIN });
    }

    /**
     * The inner product of a sector normal with an offset, over the offset's own
     * length so a pinned offset reads as having no v component.
     *
     * @param normal the sector normal, u then v
     * @param offset the shared edge's parametric offset
     * @return their inner product
     */
    private double dot(double[] normal, double[] offset) {
        double total = 0.0;
        for (int component = 0; component < offset.length; component++) {
            total += normal[component] * offset[component];
        }
        return total;
    }
}
