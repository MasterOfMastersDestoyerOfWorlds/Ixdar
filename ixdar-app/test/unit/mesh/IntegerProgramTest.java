package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.quantization.IntegerProgram;

/**
 * The quantization integer program on hand-sized instances whose optimum is known: a fractional
 * relaxation branch and bound must close, a presolve infeasibility, and a near tie the cheaper
 * side of which must win.
 */
class IntegerProgramTest {

    private static final double TOLERANCE = 1e-9;

    private static final double EXPECTED_OBJECTIVE = 6.0;

    private static final double SUBSTITUTED_WEIGHT = 3.0;

    private static final double NEAR_TIE_MARGIN = 1e-6;

    @Test
    void branchingClosesAFractionalRelaxationAndRestoresSubstitutedVariables() {
        IntegerProgram program = new IntegerProgram(new double[] {SUBSTITUTED_WEIGHT, 1.0, 1.0, 2.0});
        program.equalityLeftSides.add(new int[] {0});
        program.equalityRightSides.add(new int[] {1, 2});
        program.coverRows.add(new int[] {1, 3});
        program.coverRows.add(new int[] {2, 3});
        program.coverRows.add(new int[] {1, 2});

        program.solve();

        assertTrue(program.feasible);
        assertEquals(EXPECTED_OBJECTIVE, program.objectiveValue, TOLERANCE);
        int[] values = program.values;
        assertEquals(values[0], values[1] + values[2]);
        assertTrue(values[1] + values[3] >= 1);
        assertTrue(values[2] + values[3] >= 1);
        assertTrue(values[1] + values[2] >= 1);
    }

    @Test
    void aCoverOverAVariablePinnedToZeroIsInfeasible() {
        IntegerProgram program = new IntegerProgram(new double[] {1.0, 1.0});
        program.equalityLeftSides.add(new int[] {0});
        program.equalityRightSides.add(new int[0]);
        program.coverRows.add(new int[] {0});

        program.solve();

        assertFalse(program.feasible);
    }

    /**
     * Covering both rows with variable 2 alone leaves more zeros but costs a hair more than
     * variables 0 and 1 together, so the cheaper cover is the optimum.
     */
    @Test
    void aNearTieKeepsTheCheaperCover() {
        IntegerProgram program = new IntegerProgram(new double[] {1.0, 1.0, 2.0 + NEAR_TIE_MARGIN});
        program.coverRows.add(new int[] {0, 2});
        program.coverRows.add(new int[] {1, 2});

        program.solve();

        assertTrue(program.feasible);
        assertEquals(2.0, program.objectiveValue, TOLERANCE);
        assertArrayEquals(new int[] {1, 1, 0}, program.values);
    }

    @Test
    void negativeWeightsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new IntegerProgram(new double[] {-1.0}));
    }
}
