package ixdar.geometry.mesh.quadlayout.quantization;

/**
 * One constraint row of an {@link IntegerProgram}: a weighted sum of variables plus a bound.
 */
public interface IntegerProgramExpression {

    /**
     * Set the coefficient of one variable in this row.
     *
     * @param variableIndex position of the variable in the program's creation order
     * @param coefficient weight the variable carries in this row
     */
    void set(int variableIndex, double coefficient);

    /**
     * Constrain the row's weighted sum to be at least {@code bound}.
     *
     * @param bound inclusive lower bound
     */
    void lower(double bound);

    /**
     * Constrain the row's weighted sum to equal {@code value}.
     *
     * @param value the exact value the row must take
     */
    void level(double value);
}
