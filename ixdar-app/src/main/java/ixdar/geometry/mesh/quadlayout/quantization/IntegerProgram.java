package ixdar.geometry.mesh.quadlayout.quantization;

/**
 * Integer linear program over non-negative integer variables, supplied by the platform so that
 * ojAlgo stays out of the browser build. Variables are addressed by creation order.
 */
public interface IntegerProgram {

    /**
     * Append an integer variable with lower bound zero and the given objective weight.
     *
     * @param name diagnostic name carried into solver output
     * @param weight objective coefficient
     */
    void addVariable(String name, double weight);

    /**
     * Start a new constraint expression over the variables added so far.
     *
     * @param name diagnostic name carried into solver output
     * @return the new expression, initially with no coefficients and no bound
     */
    IntegerProgramExpression newExpression(String name);

    /**
     * Solve for the variable assignment minimizing the weighted objective.
     *
     * @return the solver's result, feasible or not
     */
    IntegerProgramSolution minimise();
}
