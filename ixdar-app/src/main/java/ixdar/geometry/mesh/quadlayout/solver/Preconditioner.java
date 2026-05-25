package ixdar.geometry.mesh.quadlayout.solver;

/**
 * Preconditioner for {@link AdaptiveSolver#preconditionedConjugateGradient}:
 * applies {@code M⁻¹} to a residual vector. Common choices: Jacobi (divide
 * by {@code diag(A)}), a cached sparse Cholesky factor of an approximate
 * {@code A} (back-solve through {@code L Lᵀ}), or an incomplete
 * factorization.
 *
 * <p>The BZK09 §5.4 IRLS stiffening loop uses the cold Cholesky factor of
 * iter 0's matrix as the preconditioner for all subsequent iters' PCG
 * solves. {@link IncrementalCholeskySolver#solve} matches this interface
 * directly, so the caller can pass a method reference.
 */
@FunctionalInterface
public interface Preconditioner {
    /**
     * Apply {@code M⁻¹} to {@code residual}, writing the result into
     * {@code output}. May alias inputs ({@code residual == output}) only if
     * the underlying implementation supports it.
     *
     * @param residual length-N input vector
     * @param output   length-N destination vector
     */
    void solve(double[] residual, double[] output);
}
