package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.SingularSystemDiagnoser;
import ixdar.geometry.mesh.quadlayout.solver.SingularSystemDiagnosis;
import ixdar.geometry.mesh.quadlayout.solver.chol.CholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.ordering.OrderingMethod;

/**
 * The three ways the seamless system goes singular, built on purpose in DOF space: a chart with no
 * constraint row tying it down, a vertex whose incident faces all have zero area, and a pair of
 * identical columns left by a redundant constraint row. Each must classify correctly, and the
 * ladder's shifted retry must still produce a finite solution on both the native and EJML rungs.
 */
public final class SingularSystemDiagnosisTest {

    /** DOFs per chart in the two-chart gauge system. */
    private static final int CHART_DOF_COUNT = 4;

    /** Coordinates per vertex position. */
    private static final int POSITION_COMPONENTS = 3;

    /** Spacing between the fabricated vertex positions, so no two coincide. */
    private static final double VERTEX_SPACING = 0.25;

    /** Second chart's index in the gauge system. */
    private static final int UNTIED_CHART = 1;

    /** DOF carrying the zero row in the sliver system. */
    private static final int SLIVER_DOF = 4;

    /** Free variables of the sliver system. */
    private static final int SLIVER_DOF_COUNT = 5;

    /** Free variables of the duplicate-row system. */
    private static final int DUPLICATE_DOF_COUNT = 4;

    /** Diagonal of a variable that appears in two of the duplicate system's squares. */
    private static final double DUPLICATE_INNER_DIAGONAL = 2.0;

    /**
     * A chart whose translation nothing pins: the second block is a bare path Laplacian, so its
     * DOFs move together for free while the first block is pinned and healthy.
     */
    @Test
    public void untiedChartClassifiesAsChartGauge() {
        int dofCount = 2 * CHART_DOF_COUNT;
        double[] diagonal = new double[dofCount];
        double[] rightHandSide = new double[dofCount];
        Map<Long, Double> upper = new HashMap<>();
        for (int chart = 0; chart < 2; chart++) {
            int base = chart * CHART_DOF_COUNT;
            for (int step = 0; step + 1 < CHART_DOF_COUNT; step++) {
                couple(upper, diagonal, base + step, base + step + 1);
            }
        }
        diagonal[0] += 1.0;
        NormalMatrix matrix = new NormalMatrix(diagonal, upper, rightHandSide);

        int[] dofChartId = new int[dofCount];
        int[] dofVertexId = new int[dofCount];
        for (int dof = 0; dof < dofCount; dof++) {
            dofChartId[dof] = dof / CHART_DOF_COUNT;
            dofVertexId[dof] = dof;
        }
        SingularSystemDiagnosis diagnosis = diagnose(matrix, dofChartId, dofVertexId,
                new boolean[dofCount]);

        assertEquals(SingularSystemDiagnosis.CLASSIFICATION_CHART_GAUGE,
                diagnosis.classification, diagnosis.logLine());
        assertEquals(CHART_DOF_COUNT, diagnosis.supportSize, diagnosis.logLine());
        assertEquals(1, diagnosis.distinctChartCount, diagnosis.logLine());
        for (int entry = 0; entry < diagnosis.supportChartId.length; entry++) {
            assertEquals(UNTIED_CHART, diagnosis.supportChartId[entry], diagnosis.logLine());
        }
        assertShiftedSolveIsFinite(matrix, dofCount);
    }

    /**
     * A vertex surrounded by zero-area faces: its DOF picks up no energy at all, leaving an empty
     * row whose null direction is the DOF itself.
     */
    @Test
    public void zeroAreaVertexClassifiesAsSliverCluster() {
        double[] diagonal = new double[SLIVER_DOF_COUNT];
        double[] rightHandSide = new double[SLIVER_DOF_COUNT];
        Map<Long, Double> upper = new HashMap<>();
        for (int step = 0; step + 1 < SLIVER_DOF; step++) {
            couple(upper, diagonal, step, step + 1);
        }
        diagonal[0] += 1.0;
        NormalMatrix matrix = new NormalMatrix(diagonal, upper, rightHandSide);

        int[] dofChartId = new int[SLIVER_DOF_COUNT];
        int[] dofVertexId = new int[SLIVER_DOF_COUNT];
        for (int dof = 0; dof < SLIVER_DOF_COUNT; dof++) {
            dofVertexId[dof] = dof;
        }
        boolean[] vertexIsDegenerate = new boolean[SLIVER_DOF_COUNT];
        vertexIsDegenerate[SLIVER_DOF] = true;
        SingularSystemDiagnosis diagnosis = diagnose(matrix, dofChartId, dofVertexId,
                vertexIsDegenerate);

        assertEquals(SingularSystemDiagnosis.CLASSIFICATION_SLIVER_CLUSTER,
                diagnosis.classification, diagnosis.logLine());
        assertEquals(1, diagnosis.supportSize, diagnosis.logLine());
        assertEquals(SLIVER_DOF, diagnosis.supportVertexId[0], diagnosis.logLine());
        assertShiftedSolveIsFinite(matrix, SLIVER_DOF_COUNT);
    }

    /**
     * A redundant constraint row leaving two DOFs the energy cannot tell apart: the last two
     * columns are identical, so their difference costs nothing.
     */
    @Test
    public void identicalColumnsClassifyAsDuplicateRow() {
        double[] diagonal = { DUPLICATE_INNER_DIAGONAL, DUPLICATE_INNER_DIAGONAL, 1.0, 1.0 };
        double[] rightHandSide = new double[DUPLICATE_DOF_COUNT];
        Map<Long, Double> upper = new HashMap<>();
        upper.put(key(0, 1), -1.0);
        upper.put(key(1, 2), -1.0);
        upper.put(key(1, 3), -1.0);
        upper.put(key(2, 3), 1.0);
        NormalMatrix matrix = new NormalMatrix(diagonal, upper, rightHandSide);

        int[] dofChartId = new int[DUPLICATE_DOF_COUNT];
        int[] dofVertexId = new int[DUPLICATE_DOF_COUNT];
        for (int dof = 0; dof < DUPLICATE_DOF_COUNT; dof++) {
            dofVertexId[dof] = dof;
        }
        SingularSystemDiagnosis diagnosis = diagnose(matrix, dofChartId, dofVertexId,
                new boolean[DUPLICATE_DOF_COUNT]);

        assertEquals(SingularSystemDiagnosis.CLASSIFICATION_DUPLICATE_ROW,
                diagnosis.classification, diagnosis.logLine());
        assertEquals(2, diagnosis.supportSize, diagnosis.logLine());
        assertEquals(2, diagnosis.supportDof[0], diagnosis.logLine());
        assertEquals(DUPLICATE_DOF_COUNT - 1, diagnosis.supportDof[1], diagnosis.logLine());
        assertShiftedSolveIsFinite(matrix, DUPLICATE_DOF_COUNT);
    }

    /**
     * Run the diagnosis over a system with every variable free, fabricating one position per
     * vertex so the log line has coordinates to print.
     *
     * @param matrix             the singular system
     * @param dofChartId         per DOF chart map
     * @param dofVertexId        per DOF vertex map
     * @param vertexIsDegenerate per vertex zero-area flag
     * @return the filled diagnosis
     */
    private static SingularSystemDiagnosis diagnose(NormalMatrix matrix, int[] dofChartId,
            int[] dofVertexId, boolean[] vertexIsDegenerate) {
        double[] vertexPositionXyz =
                new double[vertexIsDegenerate.length * POSITION_COMPONENTS];
        for (int vertex = 0; vertex < vertexIsDegenerate.length; vertex++) {
            vertexPositionXyz[vertex * POSITION_COMPONENTS] = vertex * VERTEX_SPACING;
        }
        return SingularSystemDiagnoser.diagnose(matrix, new boolean[matrix.variableCount],
                -1, 0.0, dofChartId, dofVertexId, vertexPositionXyz, vertexIsDegenerate);
    }

    /**
     * Solve the singular system through the backend ladder on both rungs and require a finite
     * answer plus a reported shift, which is what the fall-through promises the caller.
     *
     * @param matrix   the singular system
     * @param dofCount its variable count
     */
    private static void assertShiftedSolveIsFinite(NormalMatrix matrix, int dofCount) {
        for (boolean forceEjml : new boolean[] { false, true }) {
            CholeskyBackend.forceEjml = forceEjml;
            matrix.singularPivotIndex = -1;
            matrix.appliedDiagonalShift = 0.0;
            try {
                double[] solution = DirectSolver.solve(matrix, new double[dofCount],
                        new boolean[dofCount], OrderingMethod.AMD);
                for (double value : solution) {
                    assertTrue(Double.isFinite(value),
                            "shifted solve returned " + value + " with forceEjml=" + forceEjml);
                }
                assertTrue(matrix.appliedDiagonalShift > 0.0,
                        "the ladder should report the shift it needed, forceEjml=" + forceEjml);
            } finally {
                CholeskyBackend.forceEjml = false;
            }
        }
    }

    /**
     * Add a unit spring between two variables: both diagonals up one, the off-diagonal down one.
     *
     * @param upper    upper-triangle accumulator
     * @param diagonal diagonal accumulator
     * @param first    one variable
     * @param second   the other variable
     */
    private static void couple(Map<Long, Double> upper, double[] diagonal, int first, int second) {
        diagonal[first] += 1.0;
        diagonal[second] += 1.0;
        upper.merge(key(first, second), -1.0, Double::sum);
    }

    /**
     * Pack an upper-triangle (row, column) pair the way {@link NormalMatrix} keys them.
     *
     * @param row    smaller index
     * @param column larger index
     * @return the packed key
     */
    private static long key(int row, int column) {
        return ((long) row << NormalMatrix.KEY_ROW_SHIFT) | column;
    }
}
