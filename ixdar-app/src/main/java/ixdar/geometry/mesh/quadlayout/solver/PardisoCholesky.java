package ixdar.geometry.mesh.quadlayout.solver;

import java.lang.ref.Cleaner;

import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.LongPointer;
import org.bytedeco.mkl.global.mkl_rt;
import org.bytedeco.mkl.global.mkl_rt._MKL_DSS_HANDLE_t;

/**
 * Native supernodal {@link FactorizedSystem} backed by Intel MKL's PARDISO
 * (via the bytedeco JNI bindings). Factors the upper-CSR SPD system once in
 * the constructor (PARDISO phases 11+12: analysis + numerical factorization)
 * and serves repeated dense solves through phase 33.
 *
 * <p>
 * The caller's fill-reducing permutation is already baked into the CSR
 * arrays by {@link NormalMatrix#toPermutedUpperCompressedSparseRow}, so
 * PARDISO is handed the <em>identity</em> as a user permutation
 * ({@code iparm[4] = 1}) — it must not reorder again, keeping fill identical
 * to the EJML reference backend. Switching to PARDISO's built-in METIS
 * nested dissection later is just dropping that flag.
 *
 * <p>
 * Off-heap state (the CSR copy, parameter arrays, and PARDISO's internal
 * factor) is freed by {@link #release()}, with a {@link Cleaner}-registered
 * {@link PardisoReleaseAction} as the safety net for leaked factors.
 */
public final class PardisoCholesky implements FactorizedSystem {

    /** PARDISO mtype for a real symmetric positive definite matrix. */
    public static final int MTYPE_REAL_SPD = 2;
    /** PARDISO phase releasing all internal memory for a handle. */
    public static final int PHASE_RELEASE_ALL = -1;
    /** PARDISO phase running analysis + numerical factorization together. */
    public static final int PHASE_ANALYZE_AND_FACTOR = 12;
    /** PARDISO phase solving with the existing factor. */
    public static final int PHASE_SOLVE = 33;
    /** Slot count of the opaque PARDISO handle ({@code void *pt[64]}). */
    public static final int HANDLE_SLOT_COUNT = 64;
    /** Length of the PARDISO iparm parameter array. */
    public static final int IPARM_LENGTH = 64;
    /** iparm index (0-based) of the user-permutation switch. */
    public static final int IPARM_INDEX_USER_PERMUTATION = 4;
    /** iparm index (0-based) of the zero-based-indexing switch. */
    public static final int IPARM_INDEX_ZERO_BASED_INDEXING = 34;
    /**
     * iparm index (0-based) of the maximum iterative-refinement step count.
     * MKL's {@code pardisoinit} default performs 2 refinement steps per
     * solve — tripling the triangular-solve work — which measured ~1.5x
     * slower than EJML's simplicial back-substitution on the seamless
     * systems. The systems this backend serves are clean SPD (no pivot
     * perturbation), so refinement is disabled; the equivalence unit test
     * pins the accuracy against EJML.
     */
    public static final int IPARM_INDEX_MAX_REFINEMENT_STEPS = 7;

    private static final Cleaner CLEANER = Cleaner.create();

    public final int dimension;
    public final LongPointer handleSlots;
    public final _MKL_DSS_HANDLE_t handle;
    public final IntPointer maxfctNative;
    public final IntPointer mnumNative;
    public final IntPointer mtypeNative;
    public final IntPointer phaseNative;
    public final IntPointer nNative;
    public final IntPointer nrhsNative;
    public final IntPointer msglvlNative;
    public final IntPointer errorNative;
    public final IntPointer rowPtrNative;
    public final IntPointer colIdxNative;
    public final DoublePointer valuesNative;
    public final IntPointer permNative;
    public final IntPointer iparmNative;
    public final DoublePointer rhsNative;
    public final DoublePointer solutionNative;
    public final Cleaner.Cleanable cleanable;

    /**
     * Copy the CSR system off-heap and run PARDISO's analysis + numerical
     * factorization.
     *
     * @param upperCsr  upper triangle (col ≥ row, ascending columns per row)
     *                  of the SPD system in the factored index space
     * @param dimension number of rows/columns of the factored system
     * @throws IllegalStateException if PARDISO reports a non-zero error code
     */
    public PardisoCholesky(CompressedSparseRowArrays upperCsr, int dimension) {
        this.dimension = dimension;
        this.handleSlots = new LongPointer(HANDLE_SLOT_COUNT);
        for (long slot = 0; slot < HANDLE_SLOT_COUNT; slot++) {
            handleSlots.put(slot, 0L);
        }
        this.handle = new _MKL_DSS_HANDLE_t(handleSlots);

        this.rowPtrNative = new IntPointer(upperCsr.rowPtr);
        this.colIdxNative = new IntPointer(upperCsr.colIdx);
        this.valuesNative = new DoublePointer(upperCsr.values);
        this.permNative = new IntPointer(dimension);
        for (int i = 0; i < dimension; i++) {
            permNative.put(i, i);
        }

        this.mtypeNative = new IntPointer(1).put(0, MTYPE_REAL_SPD);
        this.iparmNative = new IntPointer(IPARM_LENGTH);
        mkl_rt.pardisoinit(handle, mtypeNative, iparmNative);
        iparmNative.put(IPARM_INDEX_USER_PERMUTATION, 1);
        iparmNative.put(IPARM_INDEX_ZERO_BASED_INDEXING, 1);
        iparmNative.put(IPARM_INDEX_MAX_REFINEMENT_STEPS, 0);

        this.maxfctNative = new IntPointer(1).put(0, 1);
        this.mnumNative = new IntPointer(1).put(0, 1);
        this.phaseNative = new IntPointer(1);
        this.nNative = new IntPointer(1).put(0, dimension);
        this.nrhsNative = new IntPointer(1).put(0, 1);
        this.msglvlNative = new IntPointer(1).put(0, 0);
        this.errorNative = new IntPointer(1);
        this.rhsNative = new DoublePointer(dimension);
        this.solutionNative = new DoublePointer(dimension);

        phaseNative.put(0, PHASE_ANALYZE_AND_FACTOR);
        callPardiso();
        this.cleanable = CLEANER.register(this, new PardisoReleaseAction(
                handleSlots, dimension, rowPtrNative, colIdxNative, valuesNative,
                permNative, iparmNative, rhsNative, solutionNative));
    }

    @Override
    public void solve(double[] rhs, double[] out) {
        rhsNative.put(rhs, 0, dimension);
        phaseNative.put(0, PHASE_SOLVE);
        callPardiso();
        solutionNative.get(out, 0, dimension);
    }

    @Override
    public void release() {
        cleanable.clean();
    }

    /**
     * Invoke PARDISO with the current {@code phaseNative} and this factor's
     * stored arguments.
     *
     * @throws IllegalStateException if PARDISO reports a non-zero error code
     */
    private void callPardiso() {
        mkl_rt.pardiso(handle, maxfctNative, mnumNative, mtypeNative, phaseNative, nNative,
                valuesNative, rowPtrNative, colIdxNative, permNative, nrhsNative,
                iparmNative, msglvlNative, rhsNative, solutionNative, errorNative);
        int error = errorNative.get(0);
        if (error != 0) {
            throw new IllegalStateException(
                    "PARDISO phase " + phaseNative.get(0) + " failed with error " + error);
        }
    }
}
