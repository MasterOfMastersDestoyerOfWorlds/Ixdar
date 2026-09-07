package ixdar.geometry.mesh.quadlayout.solver;

/**
 * A Cholesky backend refused a factorization because the system is singular: some combination of
 * the unknowns leaves the energy unchanged, so a pivot came out zero or negative.
 */
public final class SingularSystemException extends IllegalStateException {

    /** Pivot index of a backend that reports none. */
    public static final int UNKNOWN_PIVOT = -1;

    /**
     * Row where the backend hit the zero pivot, in the factored (permuted, compact) index space,
     * or {@link #UNKNOWN_PIVOT} when the backend does not report one.
     */
    public final int pivotIndex;

    /** Backend that refused the factorization, named in the fall-through log line. */
    public final transient String backend;

    /**
     * Names the backend, the pivot it stopped on and what it reported.
     *
     * @param backend    backend that refused the factorization
     * @param pivotIndex zero-pivot row, or {@link #UNKNOWN_PIVOT} when unreported
     * @param detail     the backend's own description of the failure
     */
    public SingularSystemException(String backend, int pivotIndex, String detail) {
        super(backend + " reports a singular system at pivot " + pivotIndex + ": " + detail);
        this.backend = backend;
        this.pivotIndex = pivotIndex;
    }
}
