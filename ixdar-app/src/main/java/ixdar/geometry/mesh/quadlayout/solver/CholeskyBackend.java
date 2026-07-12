package ixdar.geometry.mesh.quadlayout.solver;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.mkl.global.mkl_rt;

/**
 * Always-auto Cholesky backend selection, the solver-package analogue of the
 * {@code ixdar.platform.gl} launcher choosing {@code LwjglGL} vs
 * {@code HeadlessGL}: here the discriminator is native-library availability
 * rather than the entry point, so the choice lives in this one factory
 * instead of the launchers. There is deliberately no flag or property — every
 * factorization uses the fastest backend that can load, and
 * {@link EjmlCholeskyFactor} is the permanent pure-Java fallback (used on
 * mac, where MKL ships no artifacts, and anywhere the natives fail to load).
 */
public final class CholeskyBackend {

    private static Boolean pardisoLoadable;
    private static boolean preloadStarted;

    private CholeskyBackend() {
    }

    /**
     * Kick off the native-library probe on a background daemon thread so the
     * ~0.7s MKL load overlaps earlier pipeline stages instead of stalling the
     * first factorization. Safe to call repeatedly; only the first call
     * spawns a thread, and {@link #pardisoAvailable()} is synchronized so a
     * concurrent foreground probe simply waits for the same load.
     */
    public static synchronized void preloadAsync() {
        if (preloadStarted) {
            return;
        }
        preloadStarted = true;
        Thread preload = new Thread(CholeskyBackend::pardisoAvailable, "pardiso-preload");
        preload.setDaemon(true);
        preload.start();
    }

    /**
     * Factor the free-variable submatrix of {@code matrix} under the supplied
     * permutation with the best available backend.
     *
     * @param matrix    symmetric system matrix (full-symmetric CSR storage)
     * @param freeCount number of free (non-fixed) variables
     * @param fixed     full-size fixed-variable mask
     * @param compactOf full-index → compact-index, or -1 if fixed
     * @param fullOf    compact-index → full-index, length {@code freeCount}
     * @param perm      permuted-index → old compact-index, length
     *                  {@code freeCount}
     * @param invPerm   old compact-index → permuted-index, length
     *                  {@code freeCount}
     * @return the factorized system operating in permuted compact index space
     */
    public static FactorizedSystem factor(NormalMatrix matrix, int freeCount, boolean[] fixed,
            int[] compactOf, int[] fullOf, int[] perm, int[] invPerm) {
        if (pardisoAvailable()) {
            return new PardisoCholesky(
                    matrix.toPermutedUpperCompressedSparseRow(
                            freeCount, fixed, compactOf, fullOf, perm, invPerm),
                    freeCount);
        }
        return new EjmlCholeskyFactor(
                matrix.toPermutedUpperCompressedSparseColumn(
                        freeCount, fixed, compactOf, fullOf, perm, invPerm),
                freeCount);
    }

    /**
     * Probe once whether the MKL natives can load on this machine. The probe
     * must never crash the app — any load failure just means the pure-Java
     * backend is used — so it catches every linkage/extraction failure mode
     * JavaCPP's loader produces.
     *
     * @return true iff the PARDISO (MKL) native backend is usable
     */
    public static synchronized boolean pardisoAvailable() {
        if (pardisoLoadable == null) {
            long loadStart = System.nanoTime();
            try {
                Loader.load(mkl_rt.class);
                pardisoLoadable = Boolean.TRUE;
                System.out.printf("[solver] PARDISO (MKL) native backend loaded in %.3fs%n",
                        (System.nanoTime() - loadStart) / 1.0e9);
            } catch (LinkageError | RuntimeException loadFailure) {
                pardisoLoadable = Boolean.FALSE;
                System.out.println("[solver] PARDISO natives unavailable ("
                        + loadFailure.getMessage() + "); using pure-Java EJML backend");
            }
        }
        return pardisoLoadable;
    }
}
