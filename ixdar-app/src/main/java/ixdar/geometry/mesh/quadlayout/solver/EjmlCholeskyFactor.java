package ixdar.geometry.mesh.quadlayout.solver;

import org.ejml.data.DMatrixRMaj;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.interfaces.linsol.LinearSolverSparse;
import org.ejml.sparse.FillReducing;
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;

/**
 * Pure-Java {@link FactorizedSystem} backed by EJML's simplicial up-looking
 * sparse Cholesky. The permanent reference backend: always available, used
 * directly on platforms without a native solver and as the ground truth the
 * native backends are validated against.
 */
public final class EjmlCholeskyFactor implements FactorizedSystem {

    public final LinearSolverSparse<DMatrixSparseCSC, DMatrixRMaj> solver;
    public final DMatrixSparseCSC systemMatrix;
    public final DMatrixRMaj rhsBuffer;
    public final DMatrixRMaj solutionBuffer;

    /**
     * Factor the upper-triangle CSC system. The caller has already applied
     * its fill-reducing permutation, so EJML runs with
     * {@link FillReducing#NONE}.
     *
     * @param upperCsc  upper triangle (col ≥ row) of the SPD system in the
     *                  factored index space
     * @param dimension number of rows/columns of the factored system
     * @throws IllegalStateException if EJML rejects the factorization (matrix
     *                               not positive definite)
     */
    public EjmlCholeskyFactor(NormalMatrix.CompressedSparseColumnArrays upperCsc, int dimension) {
        this.systemMatrix = new DMatrixSparseCSC(dimension, dimension, upperCsc.values().length);
        systemMatrix.col_idx = upperCsc.colPtr();
        systemMatrix.nz_rows = upperCsc.rowIdx();
        systemMatrix.nz_values = upperCsc.values();
        systemMatrix.nz_length = upperCsc.values().length;
        this.solver = LinearSolverFactory_DSCC.cholesky(FillReducing.NONE);
        if (!this.solver.setA(systemMatrix)) {
            throw new IllegalStateException("EJML Cholesky factorization failed (matrix not SPD?)");
        }
        this.rhsBuffer = new DMatrixRMaj(dimension, 1);
        this.solutionBuffer = new DMatrixRMaj(dimension, 1);
    }

    @Override
    public void solve(double[] rhs, double[] out) {
        System.arraycopy(rhs, 0, rhsBuffer.data, 0, rhsBuffer.numRows);
        solver.solve(rhsBuffer, solutionBuffer);
        System.arraycopy(solutionBuffer.data, 0, out, 0, solutionBuffer.numRows);
    }

    @Override
    public void refactorize(double[] values) {
        System.arraycopy(values, 0, systemMatrix.nz_values, 0, values.length);
        if (!solver.setA(systemMatrix)) {
            throw new IllegalStateException("EJML Cholesky refactorization failed (matrix not SPD?)");
        }
    }

    @Override
    public void release() {
    }
}
