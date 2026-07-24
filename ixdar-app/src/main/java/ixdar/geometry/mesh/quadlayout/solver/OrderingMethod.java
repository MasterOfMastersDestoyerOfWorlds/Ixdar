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
     * Reverse Cuthill-McKee. Minimises matrix bandwidth; kept as an option for
     * band solvers, but no longer the default — prefer {@link #AMD}.
     */
    RCM,

    /**
     * Approximate Minimum Degree (Amestoy, Davis, Duff), via the SuiteSparse
     * {@link AMDOrdering} port. Greedy elimination of the lowest-degree variable
     * at each step, with approximate degree updates via quotient-graph element
     * absorption. <strong>The default</strong> fill-reducing ordering for sparse
     * Cholesky on our mesh Laplacians — typically reduces nnz(L) by 2-3× over RCM.
     */
    AMD
}
