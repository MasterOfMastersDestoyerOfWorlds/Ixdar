package ixdar.geometry.mesh.quadlayout.solver;

/**
 * Preconditioner for {@link AdaptiveSolver#preconditionedConjugateGradient}:
 * applies {@code M⁻¹} to a residual vector. {@link IncrementalCholeskySolver#solve}
 * matches this signature, so a cached Cholesky factor can be passed as a method
 * reference.
 *
 * <p>See also: BZK09 Section 5.4
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
