package ixdar.geometry.mesh.quadlayout.solver.chol.accelerate;

import ixdar.geometry.mesh.quadlayout.solver.FactorizedSystem;
import ixdar.geometry.mesh.quadlayout.solver.chol.DesktopCholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.chol.NativeCholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.matrix.CompressedSparseRowArrays;
import ixdar.platform.Platforms;

/**
 * Accelerate (Apple sparse solvers) implementation of
 * {@link NativeCholeskyBackend}, bound through {@link AccelerateSparseLibrary}.
 *
 * <p>Only usable on macOS, where the framework loads; everywhere else the probe
 * fails quietly and the ladder moves on. Reached solely through
 * {@link DesktopCholeskyBackend}, so the browser build never links it.
 */
public final class AccelerateSparseBackend implements NativeCholeskyBackend {

    private static final double NANOS_PER_SECOND = 1.0e9;

    private static Boolean loadable;
    private static boolean preloadStarted;

    /**
     * Probe once whether the Accelerate sparse-solver bindings load on this
     * machine. A failure is not an error — it just means the next rung of the
     * backend ladder is used.
     *
     * @return true iff the Accelerate native backend is usable
     */
    public static synchronized boolean probe() {
        if (loadable == null) {
            long loadStart = System.nanoTime();
            try {
                AccelerateSparseLibrary.requireLoaded();
                loadable = Boolean.TRUE;
                Platforms.log("[solver] Accelerate sparse backend loaded in %.3fs%n",
                        (System.nanoTime() - loadStart) / NANOS_PER_SECOND);
            } catch (LinkageError | RuntimeException loadFailure) {
                loadable = Boolean.FALSE;
                Platforms.log("[solver] Accelerate sparse backend unavailable ("
                        + loadFailure.getMessage() + ")");
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
        Thread preload = new Thread(AccelerateSparseBackend::probe, "accelerate-preload");
        preload.setDaemon(true);
        preload.start();
    }

    /** {@inheritDoc}. */
    @Override
    public FactorizedSystem factorUpper(CompressedSparseRowArrays upperCsr, int dimension) {
        return new AccelerateCholeskyFactor(upperCsr, dimension);
    }
}
