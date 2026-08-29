package ixdar.geometry.mesh.quadlayout.solver.chol.accelerate;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;

import ixdar.geometry.mesh.quadlayout.solver.FactorizedSystem;
import ixdar.geometry.mesh.quadlayout.solver.matrix.CompressedSparseRowArrays;

/**
 * Native {@link FactorizedSystem} backed by Accelerate's sparse Cholesky,
 * factoring the upper-CSR SPD system once in the constructor.
 *
 * <p>
 * The upper-triangle CSR arrays are handed to Accelerate unchanged as the
 * symmetric lower triangle in CSC, which is the same storage transposed.
 * {@link #release()} frees off-heap state, backstopped by a {@link Cleaner}.
 */
public final class AccelerateCholeskyFactor implements FactorizedSystem {

    /** Byte alignment for Accelerate workspace and value buffers. */
    public static final long NATIVE_ALIGNMENT = 16;

    private static final Cleaner CLEANER = Cleaner.create();

    public final int dimension;
    public final Arena arena;
    public final MemorySegment valuesNative;
    public final MemorySegment matrix;
    public final MemorySegment numericOptions;
    public final MemorySegment factorization;
    public final MemorySegment refactorWorkspace;
    public final MemorySegment solveWorkspace;
    public final MemorySegment rhsNative;
    public final MemorySegment solutionNative;
    public final MemorySegment rhsColumn;
    public final MemorySegment solutionColumn;
    public final Cleaner.Cleanable cleanable;

    /**
     * Copy the CSR system off-heap and run Accelerate's symbolic analysis plus
     * numeric Cholesky factorization.
     *
     * @param upperCsr  upper triangle (col ≥ row, ascending columns per row)
     *                  of the SPD system in the factored index space
     * @param dimension number of rows/columns of the factored system
     * @throws IllegalStateException if Accelerate reports a non-OK status
     */
    public AccelerateCholeskyFactor(CompressedSparseRowArrays upperCsr, int dimension) {
        this.dimension = dimension;
        this.arena = Arena.ofShared();
        MemorySegment columnStarts = arena.allocate(ValueLayout.JAVA_LONG, dimension + 1L);
        for (int column = 0; column <= dimension; column++) {
            columnStarts.setAtIndex(ValueLayout.JAVA_LONG, column, upperCsr.rowPtr[column]);
        }
        MemorySegment rowIndices = arena.allocateFrom(ValueLayout.JAVA_INT, upperCsr.colIdx);
        this.valuesNative = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, upperCsr.values);
        this.matrix = AccelerateSparseLibrary.newSymmetricLowerMatrix(arena, dimension,
                columnStarts, rowIndices, valuesNative);
        MemorySegment symbolicOptions = AccelerateSparseLibrary.newSymbolicOptions(arena);
        this.numericOptions = AccelerateSparseLibrary.newNumericOptions(arena);

        MemorySegment factored;
        try {
            factored = (MemorySegment) AccelerateSparseLibrary.FACTOR_SYMMETRIC.invokeExact(
                    (SegmentAllocator) arena, AccelerateSparseLibrary.FACTORIZATION_CHOLESKY,
                    matrix, symbolicOptions, numericOptions);
        } catch (Throwable failure) {
            arena.close();
            throw new IllegalStateException("Accelerate Cholesky factor call failed", failure);
        }
        this.factorization = factored;
        int status = factorization.get(ValueLayout.JAVA_INT,
                AccelerateSparseLibrary.FACTORIZATION_STATUS_OFFSET);
        if (status != AccelerateSparseLibrary.SPARSE_STATUS_OK) {
            new AccelerateReleaseAction(arena, factorization).run();
            throw new IllegalStateException(
                    "Accelerate Cholesky factorization failed with status " + status);
        }

        long refactorBytes = factorization.get(ValueLayout.JAVA_LONG,
                AccelerateSparseLibrary.SYMBOLIC_WORKSPACE_DOUBLE_OFFSET);
        long solveBytes = factorization.get(ValueLayout.JAVA_LONG,
                AccelerateSparseLibrary.SOLVE_WORKSPACE_STATIC_OFFSET)
                + factorization.get(ValueLayout.JAVA_LONG,
                        AccelerateSparseLibrary.SOLVE_WORKSPACE_PER_RHS_OFFSET);
        this.refactorWorkspace = arena.allocate(
                Math.max(NATIVE_ALIGNMENT, refactorBytes), NATIVE_ALIGNMENT);
        this.solveWorkspace = arena.allocate(
                Math.max(NATIVE_ALIGNMENT, solveBytes), NATIVE_ALIGNMENT);
        this.rhsNative = arena.allocate(ValueLayout.JAVA_DOUBLE, dimension);
        this.solutionNative = arena.allocate(ValueLayout.JAVA_DOUBLE, dimension);
        this.rhsColumn = AccelerateSparseLibrary.newDenseColumn(arena, dimension, rhsNative);
        this.solutionColumn = AccelerateSparseLibrary.newDenseColumn(arena, dimension,
                solutionNative);
        this.cleanable = CLEANER.register(this,
                new AccelerateReleaseAction(arena, factorization));
    }

    @Override
    public void solve(double[] rhs, double[] out) {
        MemorySegment.copy(rhs, 0, rhsNative, ValueLayout.JAVA_DOUBLE, 0, dimension);
        try {
            AccelerateSparseLibrary.SOLVE_OPAQUE.invokeExact(
                    factorization, rhsColumn, solutionColumn, solveWorkspace);
        } catch (Throwable failure) {
            throw new IllegalStateException("Accelerate solve call failed", failure);
        }
        MemorySegment.copy(solutionNative, ValueLayout.JAVA_DOUBLE, 0, out, 0, dimension);
    }

    @Override
    public void refactorize(double[] values) {
        MemorySegment.copy(values, 0, valuesNative, ValueLayout.JAVA_DOUBLE, 0, values.length);
        try {
            AccelerateSparseLibrary.REFACTOR_SYMMETRIC.invokeExact(
                    matrix, factorization, numericOptions, refactorWorkspace);
        } catch (Throwable failure) {
            throw new IllegalStateException("Accelerate refactor call failed", failure);
        }
        int status = factorization.get(ValueLayout.JAVA_INT,
                AccelerateSparseLibrary.FACTORIZATION_STATUS_OFFSET);
        if (status != AccelerateSparseLibrary.SPARSE_STATUS_OK) {
            throw new IllegalStateException(
                    "Accelerate refactorization failed with status " + status);
        }
    }

    @Override
    public void release() {
        cleanable.clean();
    }
}
