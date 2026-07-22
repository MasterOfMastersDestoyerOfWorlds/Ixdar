package ixdar.geometry.mesh.quadlayout.seamless.exact;

import java.math.BigInteger;

/**
 * Result of reducing a constraint system {@code Cx = b} to integer reduced row
 * echelon form by {@link ExactArithmetic#reduceToIrref(BigInteger[][], BigInteger[])}.
 * Rows at or beyond {@link #rank} are all zero and represent linear dependencies
 * in the original system.
 */
public final class IrrefResult {

    /** The matrix after fraction-free Gauss + Jordan elimination. */
    public final BigInteger[][] matrix;

    /** The right-hand side vector transformed in lockstep with {@link #matrix}. */
    public final BigInteger[] rhs;

    /**
     * The pivot column index of each non-zero row, length {@link #rank}. Row
     * {@code i} has its pivot at column {@code pivotColumns[i]}.
     */
    public final int[] pivotColumns;

    /** Number of non-zero rows (linear rank of the original matrix). */
    public final int rank;

    /**
     * Construct a result holder.
     *
     * @param matrix       the integer reduced row echelon form matrix
     * @param rhs          the matching transformed right-hand side
     * @param pivotColumns pivot column index per non-zero row
     * @param rank         number of non-zero rows
     */
    public IrrefResult(BigInteger[][] matrix, BigInteger[] rhs, int[] pivotColumns, int rank) {
        this.matrix = matrix;
        this.rhs = rhs;
        this.pivotColumns = pivotColumns;
        this.rank = rank;
    }
}
