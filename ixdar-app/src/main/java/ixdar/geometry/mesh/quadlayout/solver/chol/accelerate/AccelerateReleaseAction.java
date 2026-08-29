package ixdar.geometry.mesh.quadlayout.solver.chol.accelerate;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Frees one {@link AccelerateCholeskyFactor}: destroys the opaque numeric
 * factorization (which also drops its retained symbolic analysis) and closes
 * the arena holding every off-heap struct and buffer. Registered with a
 * {@link java.lang.ref.Cleaner}, so it must never hold a reference to the
 * factor it frees.
 */
final class AccelerateReleaseAction implements Runnable {

    public final Arena arena;
    public final MemorySegment factorization;

    /**
     * Capture the arena and the factorization struct the release needs.
     *
     * @param arena         shared arena owning every off-heap segment
     * @param factorization SparseOpaqueFactorization_Double struct segment
     */
    AccelerateReleaseAction(Arena arena, MemorySegment factorization) {
        this.arena = arena;
        this.factorization = factorization;
    }

    @Override
    public void run() {
        try {
            AccelerateSparseLibrary.DESTROY_OPAQUE_NUMERIC.invokeExact(factorization);
        } catch (Throwable failure) {
            throw new IllegalStateException("Accelerate factor release failed", failure);
        } finally {
            arena.close();
        }
    }
}
