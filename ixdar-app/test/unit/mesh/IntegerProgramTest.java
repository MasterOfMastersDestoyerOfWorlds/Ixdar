package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.quantization.IntegerProgram;
import ixdar.geometry.mesh.quadlayout.quantization.ortools.OrToolsIntegerProgramBackend;

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

    private static final int STEINER_BASE_POINTS = 16;

    private static final double STEINER_OPTIMUM = 29.0;

    private static final long SOLVE_SECONDS = 60;

    /** Branch and bound and SCIP each close the fractional root and restore the substituted variable. */
    @Test
    void branchingClosesAFractionalRelaxationAndRestoresSubstitutedVariables() {
        for (boolean scip : new boolean[] {false, true}) {
            IntegerProgram program = new IntegerProgram(new double[] {SUBSTITUTED_WEIGHT, 1.0, 1.0, 2.0});
            program.backend = scip ? new OrToolsIntegerProgramBackend() : null;
            assertTrue(!scip || program.backend.available(), "the OR-Tools natives load on this platform");
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

    /**
     * The Steiner triple covering program on 45 points (Fulkerson, Nemhauser and Trotter 1974), on
     * which the root cut loop used to run without end (CRAW-147): SCIP and the built-in solver each
     * prove its optimum of 29 well inside their node budget.
     */
    @Test
    void theSteinerProgramClosesWithEitherSolver() {
        for (boolean scip : new boolean[] {true, false}) {
            int points = STEINER_BASE_POINTS - 1;
            double[] weights = new double[points * 3];
            Arrays.fill(weights, 1.0);
            IntegerProgram program = new IntegerProgram(weights);
            program.backend = scip ? new OrToolsIntegerProgramBackend() : null;
            for (int first = 1; first < STEINER_BASE_POINTS; first++) {
                for (int second = first + 1; second < STEINER_BASE_POINTS; second++) {
                    int third = first ^ second;
                    if (third <= second) {
                        continue;
                    }
                    for (int firstCopy = 0; firstCopy < 3; firstCopy++) {
                        for (int secondCopy = 0; secondCopy < 3; secondCopy++) {
                            int[] cover = {(first - 1) * 3 + firstCopy, (second - 1) * 3 + secondCopy,
                                (third - 1) * 3 + (6 - firstCopy - secondCopy) % 3};
                            Arrays.sort(cover);
                            program.coverRows.add(cover);
                        }
                    }
                }
            }
            for (int point = 0; point < points; point++) {
                program.coverRows.add(new int[] {point * 3, point * 3 + 1, point * 3 + 2});
            }

            assertTimeoutPreemptively(Duration.ofSeconds(SOLVE_SECONDS), program::solve);

            assertTrue(program.feasible);
            assertEquals(STEINER_OPTIMUM, program.objectiveValue, TOLERANCE, scip ? "SCIP" : "built-in");
        }
    }

    @Test
    void negativeWeightsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new IntegerProgram(new double[] {-1.0}));
    }
}
