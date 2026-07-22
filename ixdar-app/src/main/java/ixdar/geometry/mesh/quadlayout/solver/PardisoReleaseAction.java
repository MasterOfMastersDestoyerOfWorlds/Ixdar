package ixdar.geometry.mesh.quadlayout.solver;

import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.LongPointer;
import org.bytedeco.mkl.global.mkl_rt;
import org.bytedeco.mkl.global.mkl_rt._MKL_DSS_HANDLE_t;

/**
 * Frees one {@link PardisoCholesky} factorization: tells PARDISO to release
 * its internal factor memory (phase -1) and deallocates the off-heap CSR /
 * parameter arrays. Registered with a {@link java.lang.ref.Cleaner}, so it must
 * never hold a reference to the {@code PardisoCholesky} it frees.
 */
public final class PardisoReleaseAction implements Runnable {

    public final LongPointer handleSlots;
    public final int dimension;
    public final IntPointer rowPtrNative;
    public final IntPointer colIdxNative;
    public final DoublePointer valuesNative;
    public final IntPointer permNative;
    public final IntPointer iparmNative;
    public final DoublePointer rhsNative;
    public final DoublePointer solutionNative;

    /**
     * Capture everything the PARDISO release call and the deallocations need.
     * Holding these references here also keeps the off-heap arrays alive (and
     * out of JavaCPP's reference-driven deallocation) for as long as the
     * native factor might still read them.
     *
     * @param handleSlots    the 64-slot opaque PARDISO handle backing store
     * @param dimension      factored system dimension
     * @param rowPtrNative   off-heap CSR row pointers
     * @param colIdxNative   off-heap CSR column indices
     * @param valuesNative   off-heap CSR values
     * @param permNative     off-heap permutation vector
     * @param iparmNative    off-heap PARDISO parameter array
     * @param rhsNative      off-heap right-hand-side scratch vector
     * @param solutionNative off-heap solution scratch vector
     */
    public PardisoReleaseAction(LongPointer handleSlots, int dimension,
            IntPointer rowPtrNative, IntPointer colIdxNative, DoublePointer valuesNative,
            IntPointer permNative, IntPointer iparmNative,
            DoublePointer rhsNative, DoublePointer solutionNative) {
        this.handleSlots = handleSlots;
        this.dimension = dimension;
        this.rowPtrNative = rowPtrNative;
        this.colIdxNative = colIdxNative;
        this.valuesNative = valuesNative;
        this.permNative = permNative;
        this.iparmNative = iparmNative;
        this.rhsNative = rhsNative;
        this.solutionNative = solutionNative;
    }

    @Override
    public void run() {
        IntPointer maxfct = new IntPointer(1).put(0, 1);
        IntPointer mnum = new IntPointer(1).put(0, 1);
        IntPointer mtype = new IntPointer(1).put(0, PardisoCholesky.MTYPE_REAL_SPD);
        IntPointer phase = new IntPointer(1).put(0, PardisoCholesky.PHASE_RELEASE_ALL);
        IntPointer n = new IntPointer(1).put(0, dimension);
        IntPointer nrhs = new IntPointer(1).put(0, 1);
        IntPointer msglvl = new IntPointer(1).put(0, 0);
        IntPointer error = new IntPointer(1).put(0, 0);
        mkl_rt.pardiso(new _MKL_DSS_HANDLE_t(handleSlots), maxfct, mnum, mtype, phase, n,
                valuesNative, rowPtrNative, colIdxNative, permNative, nrhs,
                iparmNative, msglvl, rhsNative, solutionNative, error);
        maxfct.deallocate();
        mnum.deallocate();
        mtype.deallocate();
        phase.deallocate();
        n.deallocate();
        nrhs.deallocate();
        msglvl.deallocate();
        error.deallocate();
        rowPtrNative.deallocate();
        colIdxNative.deallocate();
        valuesNative.deallocate();
        permNative.deallocate();
        iparmNative.deallocate();
        rhsNative.deallocate();
        solutionNative.deallocate();
        handleSlots.deallocate();
    }
}
