package ixdar.numeric.sparse.chol;

import ixdar.numeric.sparse.FactorizedSystem;
import ixdar.numeric.sparse.matrix.CompressedSparseRowArrays;

/**
 * Native Cholesky acceleration, supplied by the platform rather than referenced directly.
 *
 * <p>The indirection is what keeps MKL and JavaCPP out of the browser build: only the desktop and
 * headless backends name an implementation, so the web launcher never reaches one and TeaVM never
 * tries to link a native solver.
 */
public interface NativeCholeskyBackend {

    /**
     * Whether the native libraries actually load on this machine. Probed once and cached; a failure
     * is not an error, it just means the pure-Java {@link EjmlCholeskyFactor} path is used.
     *
     * @return true iff the native backend is usable
     */
    boolean available();

    /**
     * Start the native-library probe on a background daemon thread so the load overlaps earlier
     * pipeline stages. Safe to call repeatedly.
     */
    void preloadAsync();

    /**
     * Factor an upper-triangular permuted system with the native backend.
     *
     * @param upperCsr upper-triangular system in permuted compressed-sparse-row storage
     * @param dimension number of free variables, the factor's square dimension
     * @return the factorized system operating in permuted compact index space
     */
    FactorizedSystem factorUpper(CompressedSparseRowArrays upperCsr, int dimension);

    /**
     * Caps the native threads the calling thread's factorizations use, so many small concurrent
     * solves do not each fan out over every core.
     *
     * @param threads thread cap for this calling thread; 0 restores the process-wide setting
     * @return the calling thread's previous cap, 0 when none was set
     */
    default int limitCallingThreadSolverThreads(int threads) {
        return 0;
    }
}
