package ixdar.geometry.mesh.quadlayout.solver;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.mkl.global.mkl_rt;

import ixdar.platform.Platforms;

/**
 * PARDISO (Intel MKL) implementation of {@link NativeCholeskyBackend}.
 *
 * <p>The only class in the solver package that names JavaCPP or MKL. Reached solely through the
 * desktop and headless platforms, so the browser build never links it.
 */
public final class PardisoBackend implements NativeCholeskyBackend {

    private static final double NANOS_PER_SECOND = 1.0e9;

    private static Boolean loadable;
    private static boolean preloadStarted;

    /**
     * Probe once whether the MKL natives load on this machine. The probe must never crash the app —
     * any load failure just means the pure-Java backend is used — so it catches every linkage and
     * extraction failure mode JavaCPP's loader produces.
     *
     * @return true iff the PARDISO (MKL) native backend is usable
     */
    public static synchronized boolean probe() {
        if (loadable == null) {
            long loadStart = System.nanoTime();
            try {
                Loader.load(mkl_rt.class);
                loadable = Boolean.TRUE;
                Platforms.log("[solver] PARDISO (MKL) native backend loaded in %.3fs%n",
                        (System.nanoTime() - loadStart) / NANOS_PER_SECOND);
            } catch (LinkageError | RuntimeException loadFailure) {
                loadable = Boolean.FALSE;
                Platforms.log("[solver] PARDISO natives unavailable ("
                        + loadFailure.getMessage() + "); using pure-Java EJML backend");
            }
        }
        return loadable;
    }

    /** {@inheritDoc}. */
    @Override
    public boolean available() {
        return probe();
    }

    /** {@inheritDoc}. */
    @Override
    public synchronized void preloadAsync() {
        if (preloadStarted) {
            return;
        }
        preloadStarted = true;
        Thread preload = new Thread(PardisoBackend::probe, "pardiso-preload");
        preload.setDaemon(true);
        preload.start();
    }

    /** {@inheritDoc}. */
    @Override
    public FactorizedSystem factorUpper(CompressedSparseRowArrays upperCsr, int dimension) {
        return new PardisoCholesky(upperCsr, dimension);
    }
}
