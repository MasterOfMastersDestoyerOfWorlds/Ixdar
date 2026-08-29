package ixdar.geometry.mesh.quadlayout.solver.system;

import java.util.Arrays;
import java.util.Random;

import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.ordering.OrderingMethod;

/**
 * Inverse power iteration on the generalized eigenproblem
 * {@code A u = lambda M u} over a {@link DofSystem}: factor A once, repeatedly
 * solve against {@code M u} and mass-normalize, leaving the smallest
 * eigenvector in the solution.
 *
 * <p>See also: KCP13 Algorithm 2
 */
public final class PowerIteration {

    /** Floor under the mass norm, guarding the normalization against zero. */
    public static final double EPS = 1.0e-12;

    public final DofSystem dofs;

    /** The energy matrix A, factored once. */
    public final NormalMatrix energy;

    /** The mass matrix M, applied and normalized against every iteration. */
    public final NormalMatrix mass;

    /** Power iterations to run. */
    public int iterations;

    /** Seed of the random start vector, fixed for determinism. */
    public long seed = 12345L;

    /** Whether the factorization succeeded; false leaves the solution zero. */
    public boolean factored;

    /**
     * Stores the eigenproblem.
     *
     * @param dofs       system whose solution receives the eigenvector
     * @param energy     the energy matrix A
     * @param mass       the mass matrix M
     * @param iterations power iterations to run
     */
    public PowerIteration(DofSystem dofs, NormalMatrix energy, NormalMatrix mass, int iterations) {
        this.dofs = dofs;
        this.energy = energy;
        this.mass = mass;
        this.iterations = iterations;
    }

    /**
     * Runs the iteration, writing the mass-normalized eigenvector into
     * {@code dofs.solution}; a failed factorization leaves it zero.
     */
    public void run() {
        int size = dofs.dofCount;
        DirectSolver.CholeskyHandle handle =
                DirectSolver.factorize(energy, dofs.frozen, OrderingMethod.AMD);
        factored = handle.factor() != null;
        if (!factored) {
            Arrays.fill(dofs.solution, 0.0);
            return;
        }
        double[] u = new double[size];
        Random rng = new Random(seed);
        for (int i = 0; i < size; i++) {
            u[i] = rng.nextDouble() * 2.0 - 1.0;
        }
        massNormalize(u);
        double[] rhs = new double[size];
        double[] x = new double[size];
        double[] start = new double[size];
        for (int it = 0; it < iterations; it++) {
            for (int i = 0; i < size; i++) {
                rhs[i] = mass.rowDot(i, u);
            }
            DirectSolver.solveCompact(handle, energy, rhs, x, start, dofs.frozen);
            System.arraycopy(x, 0, u, 0, size);
            massNormalize(u);
        }
        DirectSolver.releaseHandle(handle);
        System.arraycopy(u, 0, dofs.solution, 0, size);
    }

    /**
     * Scales v to unit mass norm in place.
     *
     * @param v vector to normalize
     */
    private void massNormalize(double[] v) {
        double quadratic = 0.0;
        for (int i = 0; i < v.length; i++) {
            quadratic += v[i] * mass.rowDot(i, v);
        }
        double scale = Math.sqrt(Math.max(quadratic, EPS));
        for (int i = 0; i < v.length; i++) {
            v[i] /= scale;
        }
    }
}
