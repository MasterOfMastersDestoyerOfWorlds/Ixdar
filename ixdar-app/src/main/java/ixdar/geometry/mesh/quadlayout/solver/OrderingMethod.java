package ixdar.geometry.mesh.quadlayout.solver;

/**
 * Fill-reducing column ordering for sparse Cholesky factorization. Kept
 * separate from EJML's {@code FillReducing} enum so callers aren't locked
 * into the library's options.
 *
 * <p>Picking the right ordering matters a lot for sparse Cholesky on
 * mesh-derived matrices: the wrong choice can grow nnz(L) by an order of
 * magnitude, and incremental rank-1 updates pay the price on every pin
 * because they walk L's columns.
 */
public enum OrderingMethod {

    /**
     * Identity permutation. Use only when the caller has already ordered
     * the matrix or wants to measure the un-permuted cost.
     */
    NATURAL,

    /**
     * Reverse Cuthill-McKee. Minimises matrix bandwidth — appropriate for
     * band solvers and for the cross-field stage where the original
     * AdaptiveSolver pipeline was tuned around RCM.
     */
    RCM,

    /**
     * Approximate Minimum Degree (Amestoy, Davis, Duff). Greedy elimination
     * of the lowest-degree variable at each step, with approximate degree
     * updates via quotient-graph element absorption. The standard choice
     * for sparse Cholesky on mesh Laplacians — typically reduces nnz(L) by
     * 2-3× over RCM on the matrices we factor in the seamless stage.
     */
    AMD
}
