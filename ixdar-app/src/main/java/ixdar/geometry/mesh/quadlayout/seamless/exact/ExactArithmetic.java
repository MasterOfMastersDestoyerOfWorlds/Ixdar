package ixdar.geometry.mesh.quadlayout.seamless.exact;

import java.math.BigInteger;

/**
 * Exact-arithmetic toolbox for seamless constraint satisfaction: fraction-free
 * integer elimination in {@link BigInteger}, plus double arithmetic confined to the
 * {@code F_d} fixed-point subset of IEEE 754, the multiples of {@code 2^(K-52)} for
 * {@code d = 2^K}. Results stay bit-exact while they remain in {@code (-d, +d)}.
 *
 * <p>See also: MC19 Section 4
 */
public final class ExactArithmetic {

    /** Rotation value not expressible as a {@code switch} literal. */
    public static final int ROTATION_THREE_QUARTERS = 3;
    /** Error message prefix for invalid rotations passed to {@link #integerCosine(int)}/{@link #integerSine(int)}. */
    public static final String INVALID_ROTATION_MESSAGE = "rotation must be in {0,1,2,3}, got ";
    /** Mantissa precision of an IEEE 754 double-precision float. */
    public static final int DOUBLE_MANTISSA_BITS = 52;

    private ExactArithmetic() {
    }

    // =====================================================================
    // §4.3 — fixed-point F_d truncation (Ebke et al. trick)
    // =====================================================================

    /**
     * Round {@code value} to the nearest member of {@code F_d} via the Ebke et al.
     * carry trick {@code v -> (v + sv·d) - sv·d}, where {@code sv} is the sign of
     * {@code v}. Exploits IEEE 754 round-to-nearest to zero the low bits below
     * exponent {@code log2 d - precision}.
     *
     * @param value any finite double
     * @param d     the {@code F_d} scale, a positive power of two ≥ |value|
     * @return the snapped value in {@code F_d}
     */
    public static double truncateToFd(double value, double d) {
        if (value >= 0.0) {
            return (value + d) - d;
        }
        return (value - d) + d;
    }

    /**
     * Pick a power-of-two {@code d = 2^K} large enough to host every value in
     * {@code xBar}, as {@code K = max_i ceil(log2 |x̄_i|) + 1}. No extra headroom
     * may be added: a coarser lattice merges chart vertices that the downstream
     * repair pass requires to stay distinguishable.
     *
     * <p>See also: MC19 Section 4.3
     *
     * @param xBar the vector of input free-variable assignments
     * @return the chosen {@code d}; at least {@code 2}
     */
    public static double chooseFdScale(double[] xBar) {
        double maxAbs = 0.0;
        for (double v : xBar) {
            double a = Math.abs(v);
            if (a > maxAbs) {
                maxAbs = a;
            }
        }
        int naturalExponent = maxAbs == 0.0
                ? 0
                : (int) Math.ceil(Math.log(maxAbs) / Math.log(2.0)) + 1;
        return Math.pow(2.0, naturalExponent);
    }

    // =====================================================================
    // §4.1 — fraction-free integer RREF (Algs 1 & 2)
    // =====================================================================

    /**
     * Reduce {@code (matrix, rhs)} to integer reduced row echelon form via
     * fraction-free Gauss then fraction-free Jordan, with common-factor cleanup
     * after every row update. Both arrays are mutated in place and the returned
     * {@link IrrefResult} aliases them. Rank-deficient rectangular matrices are
     * accepted; surplus rows end up all zero.
     *
     * <p>See also: MC19 Section 4.1
     *
     * @param matrix mutable {@code m × n} integer matrix, transformed in place
     * @param rhs    mutable length-{@code m} integer right-hand side, transformed
     *               in lockstep with {@code matrix}
     * @return the matrix, the right-hand side, the pivot column per non-zero row,
     *         and the rank
     */
    public static IrrefResult reduceToIrref(BigInteger[][] matrix, BigInteger[] rhs) {
        int rowCount = matrix.length;
        int columnCount = rowCount == 0 ? 0 : matrix[0].length;
        int[] pivotColumnsTmp = new int[Math.min(rowCount, columnCount)];

        int pivotRow = 0;
        for (int column = 0; column < columnCount && pivotRow < rowCount; column++) {
            int foundRow = -1;
            for (int row = pivotRow; row < rowCount; row++) {
                if (matrix[row][column].signum() != 0) {
                    foundRow = row;
                    break;
                }
            }
            if (foundRow < 0) {
                continue;
            }
            if (foundRow != pivotRow) {
                BigInteger[] tmpRow = matrix[pivotRow];
                matrix[pivotRow] = matrix[foundRow];
                matrix[foundRow] = tmpRow;
                BigInteger tmpRhs = rhs[pivotRow];
                rhs[pivotRow] = rhs[foundRow];
                rhs[foundRow] = tmpRhs;
            }

            BigInteger pivot = matrix[pivotRow][column];
            for (int row = pivotRow + 1; row < rowCount; row++) {
                BigInteger factor = matrix[row][column];
                if (factor.signum() == 0) {
                    continue;
                }
                for (int j = column; j < columnCount; j++) {
                    matrix[row][j] = pivot.multiply(matrix[row][j])
                            .subtract(factor.multiply(matrix[pivotRow][j]));
                }
                rhs[row] = pivot.multiply(rhs[row]).subtract(factor.multiply(rhs[pivotRow]));
                reduceRowByGcd(matrix[row], rhs, row, column);
            }

            pivotColumnsTmp[pivotRow] = column;
            pivotRow++;
        }

        int rank = pivotRow;

        for (int reverseIdx = rank - 1; reverseIdx >= 1; reverseIdx--) {
            int pivotCol = pivotColumnsTmp[reverseIdx];
            BigInteger pivot = matrix[reverseIdx][pivotCol];
            for (int row = reverseIdx - 1; row >= 0; row--) {
                BigInteger factor = matrix[row][pivotCol];
                if (factor.signum() == 0) {
                    continue;
                }
                for (int j = 0; j < columnCount; j++) {
                    if (j < pivotCol) {
                        matrix[row][j] = pivot.multiply(matrix[row][j]);
                    } else {
                        matrix[row][j] = pivot.multiply(matrix[row][j])
                                .subtract(factor.multiply(matrix[reverseIdx][j]));
                    }
                }
                rhs[row] = pivot.multiply(rhs[row]).subtract(factor.multiply(rhs[reverseIdx]));
                reduceRowByGcd(matrix[row], rhs, row, 0);
            }
        }

        int[] pivotColumns = new int[rank];
        System.arraycopy(pivotColumnsTmp, 0, pivotColumns, 0, rank);
        return new IrrefResult(matrix, rhs, pivotColumns, rank);
    }

    /**
     * Divide an entire row (matrix columns {@code [startColumn..n)} plus the
     * matching {@code rhs[row]} entry) by the greatest common divisor of all its
     * absolute values. Bounds integer growth in fraction-free elimination per
     * MC19 §4.1 ("remove common factors and thereby reduce the growth of values
     * as much as possible").
     *
     * @param rowEntries  the row of the matrix being reduced (mutated)
     * @param rhs         the matching right-hand side vector (mutated)
     * @param row         the index into {@code rhs} for this row
     * @param startColumn the first column to include in the GCD (entries before
     *                    this are already zero in row-echelon form)
     */
    private static void reduceRowByGcd(BigInteger[] rowEntries, BigInteger[] rhs, int row, int startColumn) {
        BigInteger g = rhs[row].abs();
        for (int j = startColumn; j < rowEntries.length; j++) {
            if (g.equals(BigInteger.ONE)) {
                return;
            }
            BigInteger abs = rowEntries[j].abs();
            if (abs.signum() != 0) {
                g = g.signum() == 0 ? abs : g.gcd(abs);
            }
        }
        if (g.signum() == 0 || g.equals(BigInteger.ONE)) {
            return;
        }
        for (int j = startColumn; j < rowEntries.length; j++) {
            rowEntries[j] = rowEntries[j].divide(g);
        }
        rhs[row] = rhs[row].divide(g);
    }

    // =====================================================================
    // §4.3 — Algorithm 4 (makeDiv) and Algorithm 5 (safeDot)
    // =====================================================================

    /**
     * Algorithm 4 ({@code makeDiv}): snap a free-variable assignment {@code xBar}
     * so that any later division by each integer in {@code divisors} is exact in
     * {@code F_d}. If {@code divisors} is empty, falls back to plain
     * {@link #truncateToFd(double, double)}.
     *
     * @param xBar     input approximate value
     * @param divisors the integers that the snapped result must be {@code F_d}-divisible by
     * @param d        the {@code F_d} scale
     * @return the snapped value, guaranteed to be in {@code F_d} and divisible by
     *         {@code lcm(divisors)}
     */
    public static double makeDiv(double xBar, BigInteger[] divisors, double d) {
        if (divisors.length == 0) {
            return truncateToFd(xBar, d);
        }
        BigInteger lcmAll = BigInteger.ONE;
        for (BigInteger divisor : divisors) {
            if (lcmAll.signum() == 0 || divisor.signum() == 0) {
                lcmAll = BigInteger.ZERO;
                break;
            }
            lcmAll = lcmAll.abs().divide(lcmAll.gcd(divisor)).multiply(divisor.abs());
        }
        if (lcmAll.equals(BigInteger.ONE)) {
            return truncateToFd(xBar, d);
        }
        double lcmDouble = lcmAll.doubleValue();
        return truncateToFd(xBar / lcmDouble, d) * lcmDouble;
    }

    /**
     * Evaluate {@code Σ coefficients[i]·values[i]}, reordering the summation so no
     * partial sum leaves {@code (-d, +d)} and the result is therefore exact.
     * Requires every {@code values[i]} to be in {@code F_d} and the true result to
     * lie in {@code (-d, +d)}.
     *
     * <p>See also: MC19 Algorithm 5
     *
     * @param coefficients integer coefficients
     * @param values       paired values, each in {@code F_d}
     * @param d            the {@code F_d} scale
     * @return the exact dot product, in {@code F_d}
     * @throws IllegalArgumentException if {@code coefficients} and {@code values}
     *         have different lengths
     * @throws ArithmeticException if a partial sum would exceed {@code (-d, +d)},
     *         indicating the input violated the safeDot range precondition
     */
    public static double safeDot(BigInteger[] coefficients, double[] values, double d) {
        if (coefficients.length != values.length) {
            throw new IllegalArgumentException("coefficient/value array length mismatch");
        }
        int positiveCount = 0;
        int negativeCount = 0;
        for (int i = 0; i < coefficients.length; i++) {
            if (coefficients[i].signum() == 0 || values[i] == 0.0) {
                continue;
            }
            if ((coefficients[i].signum() > 0) == (values[i] > 0.0)) {
                positiveCount++;
            } else {
                negativeCount++;
            }
        }

        BigInteger[] posRemaining = new BigInteger[positiveCount];
        double[] posValueAbs = new double[positiveCount];
        BigInteger[] negRemaining = new BigInteger[negativeCount];
        double[] negValueAbs = new double[negativeCount];

        int posIdx = 0;
        int negIdx = 0;
        for (int i = 0; i < coefficients.length; i++) {
            if (coefficients[i].signum() == 0 || values[i] == 0.0) {
                continue;
            }
            BigInteger absCoeff = coefficients[i].abs();
            double absValue = Math.abs(values[i]);
            if ((coefficients[i].signum() > 0) == (values[i] > 0.0)) {
                posRemaining[posIdx] = absCoeff;
                posValueAbs[posIdx] = absValue;
                posIdx++;
            } else {
                negRemaining[negIdx] = absCoeff;
                negValueAbs[negIdx] = absValue;
                negIdx++;
            }
        }

        double accumulator = 0.0;
        int posCursor = 0;
        int negCursor = 0;
        while (posCursor < positiveCount || negCursor < negativeCount) {
            boolean takeFromPositive = posCursor < positiveCount
                    && (accumulator < 0.0 || negCursor >= negativeCount);
            if (takeFromPositive) {
                int idx = nextNonEmpty(posRemaining, posCursor, positiveCount);
                posCursor = idx;
                if (idx >= positiveCount) {
                    continue;
                }
                double headroom = d - accumulator;
                long kLimit = (long) Math.floor(headroom / posValueAbs[idx]);
                if (kLimit < 0) {
                    kLimit = 0;
                }
                BigInteger take = posRemaining[idx].min(BigInteger.valueOf(kLimit));
                if (take.signum() <= 0) {
                    throw new ArithmeticException(
                            "safeDot exceeded F_d range (positive) at index " + idx);
                }
                accumulator = accumulator + take.doubleValue() * posValueAbs[idx];
                posRemaining[idx] = posRemaining[idx].subtract(take);
            } else {
                int idx = nextNonEmpty(negRemaining, negCursor, negativeCount);
                negCursor = idx;
                if (idx >= negativeCount) {
                    continue;
                }
                double headroom = -d - accumulator;
                long kLimit = (long) Math.floor(-headroom / negValueAbs[idx]);
                if (kLimit < 0) {
                    kLimit = 0;
                }
                BigInteger take = negRemaining[idx].min(BigInteger.valueOf(kLimit));
                if (take.signum() <= 0) {
                    throw new ArithmeticException(
                            "safeDot exceeded F_d range (negative) at index " + idx);
                }
                accumulator = accumulator - take.doubleValue() * negValueAbs[idx];
                negRemaining[idx] = negRemaining[idx].subtract(take);
            }
        }
        return accumulator;
    }

    /**
     * Advance {@code cursor} past any zero-remaining slots in a safeDot pile.
     *
     * @param remaining the per-summand remaining-multiplicity array
     * @param cursor    starting search index
     * @param limit     exclusive end index
     * @return the first index in {@code [cursor, limit)} with non-zero remaining,
     *         or {@code limit} if all are exhausted
     */
    private static int nextNonEmpty(BigInteger[] remaining, int cursor, int limit) {
        int idx = cursor;
        while (idx < limit && remaining[idx].signum() == 0) {
            idx++;
        }
        return idx;
    }

    // =====================================================================
    // §4.3 — Algorithm 3 (Evaluation)
    // =====================================================================

    /**
     * Given an integer reduced row echelon form and an approximate input vector,
     * produce a vector exactly satisfying the original {@code Cx = b} in real
     * arithmetic, with every entry in {@code F_d}.
     *
     * <p>See also: MC19 Algorithm 3
     *
     * @param irref the reduced system from {@link #reduceToIrref}
     * @param xBar  approximate input values for every column (length {@code n})
     * @param d     the {@code F_d} scale
     * @return an exact solution {@code x} of length {@code n}; aliases are not
     *         shared with {@code xBar}
     */
    public static double[] evaluate(IrrefResult irref, double[] xBar, double d) {
        int columnCount = xBar.length;
        double[] x = new double[columnCount];
        int[] pivotRowForColumn = new int[columnCount];
        for (int c = 0; c < columnCount; c++) {
            pivotRowForColumn[c] = -1;
        }
        for (int r = 0; r < irref.rank; r++) {
            pivotRowForColumn[irref.pivotColumns[r]] = r;
        }

        for (int col = columnCount - 1; col >= 0; col--) {
            int row = pivotRowForColumn[col];
            if (row < 0) {
                BigInteger[] divisors = collectDivisorsForFreeColumn(irref, col);
                x[col] = makeDiv(xBar[col], divisors, d);
            } else {
                x[col] = computeImpliedValue(irref, x, row, col, d);
            }
        }
        return x;
    }

    /**
     * Collect the pivots of rows whose row-{@code freeColumn} entry is non-zero —
     * these are the divisors that {@code makeDiv} must keep this column's value
     * compatible with.
     *
     * @param irref      the reduced system
     * @param freeColumn a column that holds no pivot
     * @return the list of pivot {@link BigInteger}s the free value must be divisible by
     */
    private static BigInteger[] collectDivisorsForFreeColumn(IrrefResult irref, int freeColumn) {
        int count = 0;
        for (int row = 0; row < irref.rank; row++) {
            if (irref.matrix[row][freeColumn].signum() != 0) {
                count++;
            }
        }
        BigInteger[] divisors = new BigInteger[count];
        int idx = 0;
        for (int row = 0; row < irref.rank; row++) {
            if (irref.matrix[row][freeColumn].signum() != 0) {
                divisors[idx++] = irref.matrix[row][irref.pivotColumns[row]];
            }
        }
        return divisors;
    }

    /**
     * Apply formula (2) for an implied variable: divide every coefficient and the
     * right-hand side by the row's pivot, then evaluate the resulting dot product
     * via {@link #safeDot}.
     *
     * @param irref     the reduced system
     * @param x         already-decided variable values (entries above
     *                  {@code pivotColumn} are populated)
     * @param row       the row whose pivot lies at {@code pivotColumn}
     * @param pivotColumn the implied-variable's column index
     * @param d         the {@code F_d} scale
     * @return the exact value for {@code x[pivotColumn]}
     * @throws ArithmeticException if a non-divisible coefficient or right-hand
     *         side is encountered (signals an inconsistent reduced system)
     */
    private static double computeImpliedValue(IrrefResult irref, double[] x,
            int row, int pivotColumn, double d) {
        BigInteger pivot = irref.matrix[row][pivotColumn];
        double pivotDouble = pivot.doubleValue();
        int columnCount = x.length;

        BigInteger rhsValue = irref.rhs[row];
        double constantPart = rhsValue.signum() == 0 ? 0.0 : rhsValue.doubleValue() / pivotDouble;

        int termCount = 0;
        for (int col = pivotColumn + 1; col < columnCount; col++) {
            if (irref.matrix[row][col].signum() != 0) {
                termCount++;
            }
        }
        BigInteger[] coefficients = new BigInteger[termCount];
        double[] values = new double[termCount];
        int idx = 0;
        for (int col = pivotColumn + 1; col < columnCount; col++) {
            BigInteger entry = irref.matrix[row][col];
            if (entry.signum() == 0) {
                continue;
            }
            coefficients[idx] = entry.negate();
            values[idx] = x[col] / pivotDouble;
            idx++;
        }
        return constantPart + safeDot(coefficients, values, d);
    }

    /**
     * Integer cosine of {@code rotation · π/2} for an integer rotation in
     * {@code {0, 1, 2, 3}}. Must be used in place of {@code Math.cos(r * π/2)},
     * whose roundoff at the float quantum masks the exact zero residuals this
     * package's output depends on.
     *
     * @param rotation integer rotation in {@code {0, 1, 2, 3}}
     * @return the integer cosine in {@code {-1, 0, +1}}
     * @throws IllegalArgumentException if {@code rotation} is not in {@code {0, 1, 2, 3}}
     */
    public static int integerCosine(int rotation) {
        switch (rotation) {
            case 0:
                return 1;
            case 1:
                return 0;
            case 2:
                return -1;
            case ROTATION_THREE_QUARTERS:
                return 0;
            default:
                throw new IllegalArgumentException(INVALID_ROTATION_MESSAGE + rotation);
        }
    }

    /**
     * Integer sine of {@code rotation · π/2} for an integer rotation in
     * {@code {0, 1, 2, 3}}. See {@link #integerCosine(int)} for why this exists
     * instead of {@code Math.sin}.
     *
     * @param rotation integer rotation in {@code {0, 1, 2, 3}}
     * @return the integer sine in {@code {-1, 0, +1}}
     * @throws IllegalArgumentException if {@code rotation} is not in {@code {0, 1, 2, 3}}
     */
    public static int integerSine(int rotation) {
        switch (rotation) {
            case 0:
                return 0;
            case 1:
                return 1;
            case 2:
                return 0;
            case ROTATION_THREE_QUARTERS:
                return -1;
            default:
                throw new IllegalArgumentException(INVALID_ROTATION_MESSAGE + rotation);
        }
    }
}
