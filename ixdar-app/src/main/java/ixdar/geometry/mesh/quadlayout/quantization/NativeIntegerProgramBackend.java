package ixdar.geometry.mesh.quadlayout.quantization;

/**
 * Native integer programming, supplied by the platform rather than referenced directly, so the
 * browser build never reaches OR-Tools and TeaVM never tries to link it.
 */
public interface NativeIntegerProgramBackend {

    /**
     * Whether the native libraries load on this machine, probed once; a failure means the
     * built-in solver is used.
     *
     * @return true iff the backend is usable
     */
    boolean available();

    /**
     * Minimise {@code Σ weight·x} over non-negative integer columns under the rows'
     * {@code lower ≤ Σ coefficient·x ≤ upper}, to the proven optimum within {@code relativeGap}.
     *
     * @param columnWeights      objective weight per column
     * @param columnLower        lower bound per column; every column is unbounded above
     * @param rowColumns         columns of each row, ascending
     * @param rowCoefficients    coefficients parallel to {@code rowColumns}
     * @param rowLower           lower side per row
     * @param rowUpper           upper side per row, infinite for a cover
     * @param relativeGap        relative gap the search may stop at; {@code 0} proves the optimum
     * @param nodeBudget         branch and bound nodes the search may use
     * @throws IllegalStateException when the solver ends without proving the optimum, naming the gap
     * @return the integer value per column, or {@code null} when the program is infeasible
     */
    double[] solve(double[] columnWeights, double[] columnLower, int[][] rowColumns,
            double[][] rowCoefficients, double[] rowLower, double[] rowUpper, double relativeGap,
            long nodeBudget);
}
