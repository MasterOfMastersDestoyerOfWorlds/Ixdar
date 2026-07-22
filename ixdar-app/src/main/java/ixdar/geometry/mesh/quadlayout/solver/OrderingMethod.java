package ixdar.geometry.mesh.quadlayout.solver;

/**
 * Fill-reducing column ordering for sparse Cholesky factorization. The choice
 * determines nnz(L), and so the cost of every incremental rank-1 update that
 * walks a column of L.
 */
public enum OrderingMethod {

    /**
     * Identity permutation. Use only when the caller has already ordered
     * the matrix or wants to measure the un-permuted cost.
     */
    NATURAL,

    /**
     * Reverse Cuthill-McKee. Minimises matrix bandwidth; appropriate for band
     * solvers and for the cross-field stage.
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
