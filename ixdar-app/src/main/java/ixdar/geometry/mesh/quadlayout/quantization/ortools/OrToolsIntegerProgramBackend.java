package ixdar.geometry.mesh.quadlayout.quantization.ortools;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPSolverParameters;
import com.google.ortools.linearsolver.MPVariable;
import ixdar.geometry.mesh.quadlayout.quantization.NativeIntegerProgramBackend;
import ixdar.platform.Platforms;

/**
 * SCIP (Apache-2.0 since 8.0.3, https://scipopt.org) through OR-Tools (Apache-2.0,
 * https://github.com/google/or-tools), whose prebuilt natives leave out SCIP's optional GPL and
 * LGPL add-ons (GMP, Bliss, MPFR, ZIMPL, libquadmath). The only class that names OR-Tools; reached
 * through the desktop and headless platforms alone, so the browser build never links it.
 */
public final class OrToolsIntegerProgramBackend implements NativeIntegerProgramBackend {

    private static final String SOLVER_ID = "SCIP";

    private static final String NODE_LIMIT_PARAMETER = "limits/nodes = ";

    private static final double NANOS_PER_SECOND = 1.0e9;

    private static Boolean loadable;

    /**
     * Load the OR-Tools natives on first use; any linkage or extraction failure means the
     * built-in solver is used from then on.
     *
     * {@inheritDoc}
     */
    @Override
    public boolean available() {
        synchronized (OrToolsIntegerProgramBackend.class) {
            if (loadable == null) {
                long loadStart = System.nanoTime();
                try {
                    Loader.loadNativeLibraries();
                    loadable = Boolean.TRUE;
                    Platforms.log("[integer-program] OR-Tools natives loaded in %.2fs%n",
                            (System.nanoTime() - loadStart) / NANOS_PER_SECOND);
                } catch (LinkageError | RuntimeException loadFailure) {
                    loadable = Boolean.FALSE;
                    Platforms.log("[integer-program] OR-Tools natives unavailable (%s); the built-in solver"
                            + " runs%n", loadFailure);
                }
            }
            return loadable;
        }
    }

    /**
     * One SCIP solve, single-threaded so the optimum it keeps among ties is reproducible.
     *
     * {@inheritDoc}
     */
    @Override
    public double[] solve(double[] columnWeights, double[] columnLower, int[][] rowColumns,
            double[][] rowCoefficients, double[] rowLower, double[] rowUpper, double relativeGap,
            long nodeBudget) {
        MPSolver solver = MPSolver.createSolver(SOLVER_ID);
        if (solver == null) {
            throw new IllegalStateException("OR-Tools was built without " + SOLVER_ID);
        }
        try {
            solver.setNumThreads(1);
            solver.setSolverSpecificParametersAsString(NODE_LIMIT_PARAMETER + nodeBudget);
            double infinity = MPSolver.infinity();
            int columns = columnWeights.length;
            MPVariable[] variables = new MPVariable[columns];
            MPObjective objective = solver.objective();
            for (int column = 0; column < columns; column++) {
                variables[column] = solver.makeIntVar(columnLower[column], infinity, "x" + column);
                objective.setCoefficient(variables[column], columnWeights[column]);
            }
            objective.setMinimization();
            for (int row = 0; row < rowColumns.length; row++) {
                MPConstraint constraint = solver.makeConstraint(rowLower[row],
                        Double.isInfinite(rowUpper[row]) ? infinity : rowUpper[row]);
                for (int position = 0; position < rowColumns[row].length; position++) {
                    constraint.setCoefficient(variables[rowColumns[row][position]],
                            rowCoefficients[row][position]);
                }
            }
            MPSolverParameters parameters = new MPSolverParameters();
            parameters.setDoubleParam(MPSolverParameters.DoubleParam.RELATIVE_MIP_GAP, relativeGap);
            MPSolver.ResultStatus status = solver.solve(parameters);
            if (status == MPSolver.ResultStatus.INFEASIBLE) {
                return null;
            }
            if (status != MPSolver.ResultStatus.OPTIMAL) {
                boolean incumbent = status == MPSolver.ResultStatus.FEASIBLE;
                double value = incumbent ? objective.value() : Double.POSITIVE_INFINITY;
                double bound = objective.bestBound();
                throw new IllegalStateException(String.format("integer program: %s ended %s within its"
                        + " node budget of %d (nodes=%d incumbent=%.6f bound=%.6f gap=%.6f)", SOLVER_ID, status,
                        nodeBudget, solver.nodes(), value, bound, value - bound));
            }
            double[] values = new double[columns];
            for (int column = 0; column < columns; column++) {
                values[column] = Math.rint(variables[column].solutionValue());
            }
            return values;
        } finally {
            solver.delete();
        }
    }
}
