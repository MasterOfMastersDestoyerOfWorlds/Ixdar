package ixdar.geometry.mesh.quadlayout.solver;

/**
 * Complex Hermitian sparse matrix represented via the standard 2&times;2 real
 * block lifting:
 *
 * <pre>
 *   M (n x n complex) = A + iB
 *     ↓ lift
 *   ~M (2n x 2n real) =
 *     [  A  -B ]
 *     [  B   A ]
 * </pre>
 *
 * This is the only viable route here because both ojAlgo's
 * {@code SparseLU/SparseQDLDL} and MTJ's {@code ArpackSym} are
 * primitive-double only. Lifted system properties:
 * <ul>
 *   <li>If {@code M} is Hermitian (M = M*) then {@code ~M} is real-symmetric.
 *   <li>If {@code M} is positive-definite Hermitian, {@code ~M} is real SPD.
 *   <li>Eigenvalues of {@code ~M} are exactly the eigenvalues of {@code M},
 *       each appearing with doubled multiplicity. Eigenvectors come in pairs
 *       {@code (Re v, Im v)} and {@code (-Im v, Re v)}.
 *   <li>Linear solves: writing {@code x = u + iv}, {@code b = p + iq},
 *       {@code Mx = b} ⇔ {@code ~M [u;v] = [p;q]}.
 * </ul>
 *
 * Layout convention used here: indices {@code 0..n-1} are the real parts of
 * complex rows/columns; indices {@code n..2n-1} are the imaginary parts.
 * Callers must respect this convention end-to-end.
 */
public final class ComplexSparseMatrix {

    private final int n;
    private final SparseMatrix lifted;

    /**
     * Allocate an empty {@code n × n} complex matrix backed by a {@code 2n × 2n} real lift.
     *
     * @param n complex dimension; must be positive
     * @throws IllegalArgumentException if {@code n} is not positive
     */
    public ComplexSparseMatrix(int n) {
        if (n <= 0) throw new IllegalArgumentException("n must be positive");
        this.n = n;
        this.lifted = new SparseMatrix(2 * n, 2 * n);
    }

    /**
     * Complex dimension of the matrix.
     *
     * @return the complex dimension {@code n} (the lifted real matrix is {@code 2n × 2n})
     */
    public int dimension() { return n; }

    /**
     * Set complex entry {@code (i,j)} to {@code re + i*im}. Updates all four
     * blocks of the lifted matrix consistently.
     *
     * @param i  complex row index in {@code [0, n)}
     * @param j  complex column index in {@code [0, n)}
     * @param re real part of the entry
     * @param im imaginary part of the entry
     */
    public void set(int i, int j, double re, double im) {
        bounds(i, j);
        lifted.set(i, j, re);
        lifted.set(n + i, n + j, re);
        lifted.set(i, n + j, -im);
        lifted.set(n + i, j, im);
    }

    /**
     * Accumulate {@code re + i*im} into complex entry {@code (i, j)}, leaving
     * the four corresponding real-block cells consistent. Skips updates whose
     * scalar component is exactly zero.
     *
     * @param i  complex row index in {@code [0, n)}
     * @param j  complex column index in {@code [0, n)}
     * @param re real part to add
     * @param im imaginary part to add
     */
    public void add(int i, int j, double re, double im) {
        bounds(i, j);
        if (re != 0.0) {
            lifted.add(i, j, re);
            lifted.add(n + i, n + j, re);
        }
        if (im != 0.0) {
            lifted.add(i, n + j, -im);
            lifted.add(n + i, j, im);
        }
    }

    /**
     * Read the real part of complex entry (i, j).
     *
     * @param i complex row index in {@code [0, n)}
     * @param j complex column index in {@code [0, n)}
     * @return real part of the entry at {@code (i, j)}
     */
    public double getReal(int i, int j) {
        bounds(i, j);
        return lifted.get(i, j);
    }

    /**
     * Read the imaginary part of complex entry (i, j).
     *
     * @param i complex row index in {@code [0, n)}
     * @param j complex column index in {@code [0, n)}
     * @return imaginary part of the entry at {@code (i, j)}
     */
    public double getImag(int i, int j) {
        bounds(i, j);
        return lifted.get(n + i, j);
    }

    /**
     * The 2N x 2N real block-lifted SparseMatrix. Pass this to
     * {@link SparseLu}/{@link SparseQDLDL}/{@link GeneralizedEigen}.
     *
     * @return the underlying lifted real sparse matrix (live reference, not a copy)
     */
    public SparseMatrix realBlockLift() {
        return lifted;
    }

    /**
     * Assemble a real RHS of length 2n from a complex RHS {@code re + i*im}
     * (each of length n). Convention: {@code [re ; im]}.
     *
     * @param re real components of the complex RHS
     * @param im imaginary components of the complex RHS
     * @throws IllegalArgumentException if {@code re} and {@code im} have different lengths
     * @return concatenated real vector {@code [re ; im]} of length {@code 2n}
     */
    public static double[] liftRhs(double[] re, double[] im) {
        if (re.length != im.length) throw new IllegalArgumentException("re/im length mismatch");
        int n = re.length;
        double[] out = new double[2 * n];
        System.arraycopy(re, 0, out, 0, n);
        System.arraycopy(im, 0, out, n, n);
        return out;
    }

    /**
     * Inverse of {@link #liftRhs}: split a length-2n real solution into (re, im).
     *
     * @param xLifted real solution vector of even length {@code 2n}
     * @throws IllegalArgumentException if {@code xLifted.length} is odd
     * @return two-element array {@code {re, im}}, each of length {@code n}
     */
    public static double[][] unliftSolution(double[] xLifted) {
        if ((xLifted.length & 1) != 0) throw new IllegalArgumentException("expected even length");
        int n = xLifted.length / 2;
        double[] re = new double[n];
        double[] im = new double[n];
        System.arraycopy(xLifted, 0, re, 0, n);
        System.arraycopy(xLifted, n, im, 0, n);
        return new double[][] { re, im };
    }

    private void bounds(int i, int j) {
        if (i < 0 || i >= n || j < 0 || j >= n) {
            throw new IndexOutOfBoundsException("(" + i + "," + j + ") out of " + n + "x" + n);
        }
    }
}
