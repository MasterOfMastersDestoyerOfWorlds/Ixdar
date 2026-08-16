package ixdar.geometry.mesh.quadlayout.quantization;

/**
 * Outcome of an {@link IntegerProgram#minimise()} call.
 */
public final class IntegerProgramSolution {

    /** Whether the solver found an assignment satisfying every constraint. */
    public final boolean feasible;

    /** Whether that assignment is proven optimal rather than merely feasible. */
    public final boolean optimal;

    /** Solver state name, for logging and failure messages. */
    public final String state;

    /** Weighted objective at the returned assignment. */
    public final double objectiveValue;

    /** Value per variable, indexed by the program's variable creation order. */
    public final double[] variableValues;

    /**
     * Build a solution record.
     *
     * @param feasible whether every constraint is satisfied
     * @param optimal whether the assignment is proven optimal
     * @param state solver state name for logging
     * @param objectiveValue weighted objective at the assignment
     * @param variableValues value per variable in creation order
     */
    public IntegerProgramSolution(boolean feasible, boolean optimal, String state,
            double objectiveValue, double[] variableValues) {
        this.feasible = feasible;
        this.optimal = optimal;
        this.state = state;
        this.objectiveValue = objectiveValue;
        this.variableValues = variableValues;
    }
}
