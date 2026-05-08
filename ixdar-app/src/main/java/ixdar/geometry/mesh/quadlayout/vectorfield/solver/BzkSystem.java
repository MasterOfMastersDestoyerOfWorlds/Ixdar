package ixdar.geometry.mesh.quadlayout.vectorfield.solver;

/**
 * Symmetric quadratic-form system for the Bommes-Zimmer-Kobbelt 2009
 * cross-field smoothness energy:
 *
 * <pre>
 *   E(θ, m) = Σ_{e ∈ E_int} ( θ_a − θ_b + κ_e + (π/2)·m_e )²
 * </pre>
 *
 * where θ ∈ ℝ^F per face and m ∈ ℤ on the chord set of a dual spanning tree
 * (one integer per non-tree interior edge — the cycle-space basis). Tree edges
 * are gauged to m=0; their integer is recovered post-hoc from the solved θ.
 *
 * <p>
 * Variables are packed as
 * {@code [θ_0 ... θ_{F-1}, m_chord_0 ... m_chord_{C-1}]} for a total dimension
 * {@code N = F + C}.
 *
 * <p>
 * The matrix A and base RHS b are built ONCE from edge metadata and stored in
 * CSR (off-diagonal entries) plus a separate diagonal array. They are immutable
 * for the lifetime of this object — pinned variables (gauge, directional
 * constraints, chord pins) are eliminated by substitution at solve time, never
 * by penalty terms or rebuilding the matrix.
 */
public final class BzkSystem {

    private static final double PI_HALF = Math.PI * 0.5;

    private final int F;
    private final int E;
    private final int C;
    private final int N;

    private final int[] edgeFaceA;
    private final int[] edgeFaceB;
    private final double[] kappa;
    private final boolean[] isTreeEdge;
    private final int[] chordOfEdge;
    private final int[] edgeOfChord;

    // CSR (off-diagonal only). A is symmetric so each off-diagonal entry
    // appears in two rows.
    private final int[] rowStart;
    private final int[] rowCol;
    private final double[] rowVal;
    private final double[] diag;
    private final double[] baseRhs;

    /**
     * TODO: document {@code BzkSystem}.
     *
     * @param F TODO: describe
     * @param E TODO: describe
     * @param edgeFaceA TODO: describe
     * @param edgeFaceB TODO: describe
     * @param kappa TODO: describe
     * @param isTreeEdge TODO: describe
     */
    public BzkSystem(int F, int E,
            int[] edgeFaceA, int[] edgeFaceB,
            double[] kappa, boolean[] isTreeEdge) {
        this.F = F;
        this.E = E;
        this.edgeFaceA = edgeFaceA;
        this.edgeFaceB = edgeFaceB;
        this.kappa = kappa;
        this.isTreeEdge = isTreeEdge;

        int chords = 0;
        for (int e = 0; e < E; e++)
            if (!isTreeEdge[e])
                chords++;
        this.C = chords;
        this.N = F + C;
        this.chordOfEdge = new int[E];
        this.edgeOfChord = new int[C];
        int c = 0;
        for (int e = 0; e < E; e++) {
            if (!isTreeEdge[e]) {
                chordOfEdge[e] = c;
                edgeOfChord[c] = e;
                c++;
            } else {
                chordOfEdge[e] = -1;
            }
        }

        // Pass 1: row degree count.
        int[] deg = new int[N];
        for (int e = 0; e < E; e++) {
            int fa = edgeFaceA[e], fb = edgeFaceB[e];
            int chordIdx = chordOfEdge[e];
            deg[fa]++;
            deg[fb]++;
            if (chordIdx >= 0) {
                deg[fa]++;
                deg[fb]++;
                deg[F + chordIdx] += 2;
            }
        }
        rowStart = new int[N + 1];
        for (int k = 0; k < N; k++)
            rowStart[k + 1] = rowStart[k] + deg[k];
        int totalNnz = rowStart[N];
        rowCol = new int[totalNnz];
        rowVal = new double[totalNnz];
        diag = new double[N];
        baseRhs = new double[N];
        int[] cursor = rowStart.clone();

        // Pass 2: assemble.
        // Energy gradient w.r.t. θ_a, θ_b, m_chord:
        // row θ_a: diag += 1, A[a,b] -= 1, rhs[a] -= κ
        // if chord: A[a,m_c] += π/2
        // row θ_b: diag += 1, A[b,a] -= 1, rhs[b] += κ
        // if chord: A[b,m_c] -= π/2
        // if chord row m_c: diag += (π/2)², A[m_c,a] += π/2, A[m_c,b] -= π/2,
        // rhs[m_c] -= π/2 · κ
        for (int e = 0; e < E; e++) {
            int fa = edgeFaceA[e];
            int fb = edgeFaceB[e];
            int chordIdx = chordOfEdge[e];
            double k = kappa[e];

            diag[fa] += 1.0;
            diag[fb] += 1.0;
            rowCol[cursor[fa]] = fb;
            rowVal[cursor[fa]++] = -1.0;
            rowCol[cursor[fb]] = fa;
            rowVal[cursor[fb]++] = -1.0;
            baseRhs[fa] -= k;
            baseRhs[fb] += k;
            if (chordIdx >= 0) {
                int mc = F + chordIdx;
                rowCol[cursor[fa]] = mc;
                rowVal[cursor[fa]++] = PI_HALF;
                rowCol[cursor[mc]] = fa;
                rowVal[cursor[mc]++] = PI_HALF;
                rowCol[cursor[fb]] = mc;
                rowVal[cursor[fb]++] = -PI_HALF;
                rowCol[cursor[mc]] = fb;
                rowVal[cursor[mc]++] = -PI_HALF;
                diag[mc] += PI_HALF * PI_HALF;
                baseRhs[mc] -= PI_HALF * k;
            }
        }
    }

    /**
     * TODO: document {@code faceCount}.
     *
     * @return TODO: describe
     */
    public int faceCount() {
        return F;
    }

    /**
     * TODO: document {@code edgeCount}.
     *
     * @return TODO: describe
     */
    public int edgeCount() {
        return E;
    }

    /**
     * TODO: document {@code chordCount}.
     *
     * @return TODO: describe
     */
    public int chordCount() {
        return C;
    }

    /**
     * TODO: document {@code variableCount}.
     *
     * @return TODO: describe
     */
    public int variableCount() {
        return N;
    }

    /**
     * TODO: document {@code chordOfEdge}.
     *
     * @param e TODO: describe
     * @return TODO: describe
     */
    public int chordOfEdge(int e) {
        return chordOfEdge[e];
    }

    /**
     * TODO: document {@code edgeOfChord}.
     *
     * @param c TODO: describe
     * @return TODO: describe
     */
    public int edgeOfChord(int c) {
        return edgeOfChord[c];
    }

    /**
     * TODO: document {@code isTreeEdge}.
     *
     * @param e TODO: describe
     * @return TODO: describe
     */
    public boolean isTreeEdge(int e) {
        return isTreeEdge[e];
    }

    /**
     * TODO: document {@code edgeFaceA}.
     *
     * @param e TODO: describe
     * @return TODO: describe
     */
    public int edgeFaceA(int e) {
        return edgeFaceA[e];
    }

    /**
     * TODO: document {@code edgeFaceB}.
     *
     * @param e TODO: describe
     * @return TODO: describe
     */
    public int edgeFaceB(int e) {
        return edgeFaceB[e];
    }

    /**
     * TODO: document {@code kappa}.
     *
     * @param e TODO: describe
     * @return TODO: describe
     */
    public double kappa(int e) {
        return kappa[e];
    }

    /**
     * TODO: document {@code rowStart}.
     *
     * @param k TODO: describe
     * @return TODO: describe
     */
    public int rowStart(int k) {
        return rowStart[k];
    }

    /**
     * TODO: document {@code rowEnd}.
     *
     * @param k TODO: describe
     * @return TODO: describe
     */
    public int rowEnd(int k) {
        return rowStart[k + 1];
    }

    /**
     * TODO: document {@code rowCol}.
     *
     * @param p TODO: describe
     * @return TODO: describe
     */
    public int rowCol(int p) {
        return rowCol[p];
    }

    /**
     * TODO: document {@code rowVal}.
     *
     * @param p TODO: describe
     * @return TODO: describe
     */
    public double rowVal(int p) {
        return rowVal[p];
    }

    /**
     * TODO: document {@code diag}.
     *
     * @param k TODO: describe
     * @return TODO: describe
     */
    public double diag(int k) {
        return diag[k];
    }

    /**
     * TODO: document {@code baseRhs}.
     *
     * @param k TODO: describe
     * @return TODO: describe
     */
    public double baseRhs(int k) {
        return baseRhs[k];
    }

    /**
     * Compute y = A·x for one row of A. Off-diagonals from CSR + diagonal
     * contribution. Pinned columns are excluded (treated as zero in x).
     *
     * @param k TODO: describe
     * @param x TODO: describe
     * @param pinned TODO: describe
     * @return TODO: describe
     */
    public double rowDotUnpinned(int k, double[] x, boolean[] pinned) {
        double s = pinned[k] ? 0.0 : diag[k] * x[k];
        int rs = rowStart[k];
        int re = rowStart[k + 1];
        for (int p = rs; p < re; p++) {
            int j = rowCol[p];
            if (!pinned[j])
                s += rowVal[p] * x[j];
        }
        return s;
    }

    /**
     * Effective RHS for unpinned variable k, eliminating pinned columns: b_eff[k] =
     * baseRhs[k] − Σ_{j pinned} A[k,j] · pinVal[j].
     *
     * @param k TODO: describe
     * @param pinned TODO: describe
     * @param pinVal TODO: describe
     * @return TODO: describe
     */
    public double effectiveRhs(int k, boolean[] pinned, double[] pinVal) {
        double b = baseRhs[k];
        int rs = rowStart[k];
        int re = rowStart[k + 1];
        for (int p = rs; p < re; p++) {
            int j = rowCol[p];
            if (pinned[j])
                b -= rowVal[p] * pinVal[j];
        }
        return b;
    }
}
