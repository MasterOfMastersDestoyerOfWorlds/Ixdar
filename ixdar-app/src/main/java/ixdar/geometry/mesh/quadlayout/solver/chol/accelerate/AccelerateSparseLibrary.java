package ixdar.geometry.mesh.quadlayout.solver.chol.accelerate;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import ixdar.platform.Platforms;

/**
 * FFM bindings for Apple's Accelerate Sparse Solvers (Sparse/Solve.h). Struct
 * layouts mirror the macOS SDK headers for arm64/x86_64; the bound symbols are
 * the exported functions the header's inline overloads dispatch to. Class
 * initialization fails off-macOS, which {@link AccelerateSparseBackend} treats
 * as backend-unavailable.
 */
final class AccelerateSparseLibrary {

    /** dlopen path of the Accelerate umbrella framework. */
    static final String FRAMEWORK_PATH = "/System/Library/Frameworks/Accelerate.framework/Accelerate";

    /** SparseAttributes_t bits: triangle = SparseLowerTriangle (bit 1), kind = SparseSymmetric (bits 2-3). */
    static final int ATTRIBUTES_SYMMETRIC_LOWER = (1 << 1) | (3 << 2);

    /** SparseFactorization_t value selecting the Cholesky (LL^T) factorization. */
    static final byte FACTORIZATION_CHOLESKY = 0;

    /** SparseStatus_t value reporting success. */
    static final int SPARSE_STATUS_OK = 0;

    /** Default pivot tolerance from {@code _SparseDefaultNumericFactorOptions_Double}. */
    static final double DEFAULT_PIVOT_TOLERANCE = 0.01;

    /** Default zero tolerance from {@code _SparseDefaultNumericFactorOptions_Double}. */
    static final double DEFAULT_ZERO_TOLERANCE = 1.0e-4 * Math.ulp(1.0);

    /** Struct field name shared by the sparse, symbolic, and dense layouts. */
    static final String FIELD_ROW_COUNT = "rowCount";

    /** Struct field name shared by the sparse, symbolic, and dense layouts. */
    static final String FIELD_COLUMN_COUNT = "columnCount";

    /** SparseMatrixStructure field holding the column start offsets. */
    static final String FIELD_COLUMN_STARTS = "columnStarts";

    /** SparseMatrixStructure field holding the row indices. */
    static final String FIELD_ROW_INDICES = "rowIndices";

    /** SparseAttributes_t field name shared by every matrix and factor layout. */
    static final String FIELD_ATTRIBUTES = "attributes";

    /** Block size field of the sparse structure and symbolic factor layouts. */
    static final String FIELD_BLOCK_SIZE = "blockSize";

    /** Value-pointer field of the sparse and dense matrix layouts. */
    static final String FIELD_DATA = "data";

    /** SparseStatus_t field of the symbolic and numeric factor layouts. */
    static final String FIELD_STATUS = "status";

    /** SparseMatrix_Double field embedding the SparseMatrixStructure. */
    static final String FIELD_STRUCTURE = "structure";

    /** Factorization field embedding the SparseOpaqueSymbolicFactorization. */
    static final String FIELD_SYMBOLIC_FACTORIZATION = "symbolicFactorization";

    /** Symbolic-factor field sizing the double-precision refactor workspace. */
    static final String FIELD_WORKSPACE_SIZE_DOUBLE = "workspaceSizeDouble";

    /** Factorization field sizing the per-call solve workspace. */
    static final String FIELD_SOLVE_WORKSPACE_STATIC = "solveWorkspaceRequiredStatic";

    /** Factorization field sizing the per-right-hand-side solve workspace. */
    static final String FIELD_SOLVE_WORKSPACE_PER_RHS = "solveWorkspaceRequiredPerRHS";

    /** Allocator field of the symbolic options; also the libc symbol name. */
    static final String FIELD_MALLOC = "malloc";

    /** Deallocator field of the symbolic options; also the libc symbol name. */
    static final String FIELD_FREE = "free";

    /** Error-reporting callback field of the symbolic options. */
    static final String FIELD_REPORT_ERROR = "reportError";

    /** Pivot tolerance field of the numeric options. */
    static final String FIELD_PIVOT_TOLERANCE = "pivotTolerance";

    /** Zero tolerance field of the numeric options. */
    static final String FIELD_ZERO_TOLERANCE = "zeroTolerance";

    /** Control-flags field shared by the symbolic and numeric options. */
    static final String FIELD_CONTROL = "control";

    /** Column stride field of the dense matrix layout. */
    static final String FIELD_COLUMN_STRIDE = "columnStride";

    /** Layout of SparseMatrixStructure. */
    static final GroupLayout MATRIX_STRUCTURE_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName(FIELD_ROW_COUNT),
            ValueLayout.JAVA_INT.withName(FIELD_COLUMN_COUNT),
            ValueLayout.ADDRESS.withName(FIELD_COLUMN_STARTS),
            ValueLayout.ADDRESS.withName(FIELD_ROW_INDICES),
            ValueLayout.JAVA_INT.withName(FIELD_ATTRIBUTES),
            ValueLayout.JAVA_BYTE.withName(FIELD_BLOCK_SIZE),
            MemoryLayout.paddingLayout(3));

    /** Layout of SparseMatrix_Double. */
    static final GroupLayout SPARSE_MATRIX_LAYOUT = MemoryLayout.structLayout(
            MATRIX_STRUCTURE_LAYOUT.withName(FIELD_STRUCTURE),
            ValueLayout.ADDRESS.withName(FIELD_DATA));

    /** Layout of SparseOpaqueSymbolicFactorization. */
    static final GroupLayout SYMBOLIC_FACTORIZATION_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName(FIELD_STATUS),
            ValueLayout.JAVA_INT.withName(FIELD_ROW_COUNT),
            ValueLayout.JAVA_INT.withName(FIELD_COLUMN_COUNT),
            ValueLayout.JAVA_INT.withName(FIELD_ATTRIBUTES),
            ValueLayout.JAVA_BYTE.withName(FIELD_BLOCK_SIZE),
            ValueLayout.JAVA_BYTE.withName("type"),
            MemoryLayout.paddingLayout(6),
            ValueLayout.ADDRESS.withName("factorization"),
            ValueLayout.JAVA_LONG.withName("workspaceSizeFloat"),
            ValueLayout.JAVA_LONG.withName(FIELD_WORKSPACE_SIZE_DOUBLE),
            ValueLayout.JAVA_LONG.withName("factorSizeFloat"),
            ValueLayout.JAVA_LONG.withName("factorSizeDouble"));

    /** Layout of SparseOpaqueFactorization_Double. */
    static final GroupLayout FACTORIZATION_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName(FIELD_STATUS),
            ValueLayout.JAVA_INT.withName(FIELD_ATTRIBUTES),
            SYMBOLIC_FACTORIZATION_LAYOUT.withName(FIELD_SYMBOLIC_FACTORIZATION),
            ValueLayout.JAVA_BOOLEAN.withName("userFactorStorage"),
            MemoryLayout.paddingLayout(7),
            ValueLayout.ADDRESS.withName("numericFactorization"),
            ValueLayout.JAVA_LONG.withName(FIELD_SOLVE_WORKSPACE_STATIC),
            ValueLayout.JAVA_LONG.withName(FIELD_SOLVE_WORKSPACE_PER_RHS));

    /** Layout of SparseSymbolicFactorOptions. */
    static final GroupLayout SYMBOLIC_OPTIONS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName(FIELD_CONTROL),
            ValueLayout.JAVA_BYTE.withName("orderMethod"),
            MemoryLayout.paddingLayout(3),
            ValueLayout.ADDRESS.withName("order"),
            ValueLayout.ADDRESS.withName("ignoreRowsAndColumns"),
            ValueLayout.ADDRESS.withName(FIELD_MALLOC),
            ValueLayout.ADDRESS.withName(FIELD_FREE),
            ValueLayout.ADDRESS.withName(FIELD_REPORT_ERROR));

    /** Layout of SparseNumericFactorOptions. */
    static final GroupLayout NUMERIC_OPTIONS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName(FIELD_CONTROL),
            ValueLayout.JAVA_BYTE.withName("scalingMethod"),
            MemoryLayout.paddingLayout(3),
            ValueLayout.ADDRESS.withName("scaling"),
            ValueLayout.JAVA_DOUBLE.withName(FIELD_PIVOT_TOLERANCE),
            ValueLayout.JAVA_DOUBLE.withName(FIELD_ZERO_TOLERANCE));

    /** Layout of DenseMatrix_Double. */
    static final GroupLayout DENSE_MATRIX_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName(FIELD_ROW_COUNT),
            ValueLayout.JAVA_INT.withName(FIELD_COLUMN_COUNT),
            ValueLayout.JAVA_INT.withName(FIELD_COLUMN_STRIDE),
            ValueLayout.JAVA_INT.withName(FIELD_ATTRIBUTES),
            ValueLayout.ADDRESS.withName(FIELD_DATA));

    /** Byte offset of {@code status} inside SparseOpaqueFactorization_Double. */
    static final long FACTORIZATION_STATUS_OFFSET = offsetOf(FACTORIZATION_LAYOUT, FIELD_STATUS);

    /** Byte offset of the symbolic factorization's double workspace size. */
    static final long SYMBOLIC_WORKSPACE_DOUBLE_OFFSET = offsetOf(FACTORIZATION_LAYOUT,
            FIELD_SYMBOLIC_FACTORIZATION, FIELD_WORKSPACE_SIZE_DOUBLE);

    /** Byte offset of {@code solveWorkspaceRequiredStatic}. */
    static final long SOLVE_WORKSPACE_STATIC_OFFSET = offsetOf(FACTORIZATION_LAYOUT,
            FIELD_SOLVE_WORKSPACE_STATIC);

    /** Byte offset of {@code solveWorkspaceRequiredPerRHS}. */
    static final long SOLVE_WORKSPACE_PER_RHS_OFFSET = offsetOf(FACTORIZATION_LAYOUT,
            FIELD_SOLVE_WORKSPACE_PER_RHS);

    /** {@code _SparseFactorSymmetric_Double}: symbolic analysis + numeric factor in one call. */
    static final MethodHandle FACTOR_SYMMETRIC;

    /** {@code _SparseSolveOpaque_Double}: dense solve through a stored factor. */
    static final MethodHandle SOLVE_OPAQUE;

    /** {@code _SparseRefactorSymmetric_Double}: numeric refactor reusing the symbolic analysis. */
    static final MethodHandle REFACTOR_SYMMETRIC;

    /** {@code _SparseDestroyOpaqueNumeric_Double}: releases a factorization's native memory. */
    static final MethodHandle DESTROY_OPAQUE_NUMERIC;

    /** libc {@code malloc}, handed to Accelerate through the symbolic options. */
    static final MemorySegment MALLOC;

    /** libc {@code free}, handed to Accelerate through the symbolic options. */
    static final MemorySegment FREE;

    /** Upcall stub logging parameter errors instead of Accelerate's default trap. */
    static final MemorySegment REPORT_ERROR_STUB;

    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup accelerate = SymbolLookup.libraryLookup(FRAMEWORK_PATH, Arena.global());
        FACTOR_SYMMETRIC = linker.downcallHandle(
                symbol(accelerate, "_SparseFactorSymmetric_Double"),
                FunctionDescriptor.of(FACTORIZATION_LAYOUT, ValueLayout.JAVA_BYTE,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        SOLVE_OPAQUE = linker.downcallHandle(
                symbol(accelerate, "_SparseSolveOpaque_Double"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        REFACTOR_SYMMETRIC = linker.downcallHandle(
                symbol(accelerate, "_SparseRefactorSymmetric_Double"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        DESTROY_OPAQUE_NUMERIC = linker.downcallHandle(
                symbol(accelerate, "_SparseDestroyOpaqueNumeric_Double"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        MALLOC = symbol(linker.defaultLookup(), FIELD_MALLOC);
        FREE = symbol(linker.defaultLookup(), FIELD_FREE);
        try {
            REPORT_ERROR_STUB = linker.upcallStub(
                    MethodHandles.lookup().findStatic(AccelerateSparseLibrary.class,
                            "logSparseError",
                            MethodType.methodType(void.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), Arena.global());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Accelerate report-error stub failed", failure);
        }
    }

    private AccelerateSparseLibrary() {
    }

    /**
     * Force class initialization, so callers can probe whether the framework
     * loads and every bound symbol resolves on this machine.
     */
    static void requireLoaded() {
    }

    /**
     * Byte offset of a (possibly nested) named field inside a struct layout.
     *
     * @param layout struct layout to resolve against
     * @param path   group element names from the outermost struct inward
     * @return the field's byte offset from the struct start
     */
    static long offsetOf(GroupLayout layout, String... path) {
        PathElement[] elements = new PathElement[path.length];
        for (int depth = 0; depth < path.length; depth++) {
            elements[depth] = PathElement.groupElement(path[depth]);
        }
        return layout.byteOffset(elements);
    }

    /**
     * Resolve one exported symbol.
     *
     * @param lookup lookup to resolve against
     * @param name   exported symbol name
     * @throws IllegalStateException if the symbol is missing
     * @return the symbol's address
     */
    private static MemorySegment symbol(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(
                () -> new IllegalStateException("Accelerate symbol missing: " + name));
    }

    /**
     * Report-error upcall target: log and return, so Accelerate hands control
     * back with an error status instead of trapping the process.
     *
     * @param message NUL-terminated C string describing the parameter error
     */
    static void logSparseError(MemorySegment message) {
        try {
            Platforms.log("[solver] Accelerate sparse error: "
                    + message.reinterpret(4096).getString(0));
        } catch (RuntimeException ignored) {
            Platforms.log("[solver] Accelerate sparse error (unreadable message)");
        }
    }

    /**
     * Populate a SparseMatrix_Double for a symmetric system given by its lower
     * triangle in compressed-sparse-column storage with unit block size.
     *
     * @param arena        arena owning every referenced segment
     * @param dimension    square dimension of the system
     * @param columnStarts column start offsets, {@code dimension + 1} longs
     * @param rowIndices   row indices, ascending within each column
     * @param values       non-zero values matching {@code rowIndices}
     * @return the filled struct segment
     */
    static MemorySegment newSymmetricLowerMatrix(Arena arena, int dimension,
            MemorySegment columnStarts, MemorySegment rowIndices, MemorySegment values) {
        MemorySegment matrix = arena.allocate(SPARSE_MATRIX_LAYOUT);
        long structureBase = offsetOf(SPARSE_MATRIX_LAYOUT, FIELD_STRUCTURE);
        matrix.set(ValueLayout.JAVA_INT,
                structureBase + offsetOf(MATRIX_STRUCTURE_LAYOUT, FIELD_ROW_COUNT), dimension);
        matrix.set(ValueLayout.JAVA_INT,
                structureBase + offsetOf(MATRIX_STRUCTURE_LAYOUT, FIELD_COLUMN_COUNT), dimension);
        matrix.set(ValueLayout.ADDRESS,
                structureBase + offsetOf(MATRIX_STRUCTURE_LAYOUT, FIELD_COLUMN_STARTS), columnStarts);
        matrix.set(ValueLayout.ADDRESS,
                structureBase + offsetOf(MATRIX_STRUCTURE_LAYOUT, FIELD_ROW_INDICES), rowIndices);
        matrix.set(ValueLayout.JAVA_INT,
                structureBase + offsetOf(MATRIX_STRUCTURE_LAYOUT, FIELD_ATTRIBUTES),
                ATTRIBUTES_SYMMETRIC_LOWER);
        matrix.set(ValueLayout.JAVA_BYTE,
                structureBase + offsetOf(MATRIX_STRUCTURE_LAYOUT, FIELD_BLOCK_SIZE), (byte) 1);
        matrix.set(ValueLayout.ADDRESS, offsetOf(SPARSE_MATRIX_LAYOUT, FIELD_DATA), values);
        return matrix;
    }

    /**
     * Populate a SparseSymbolicFactorOptions with the header defaults: default
     * ordering, libc allocators, and this class's logging error reporter.
     *
     * @param arena arena owning the struct
     * @return the filled struct segment
     */
    static MemorySegment newSymbolicOptions(Arena arena) {
        MemorySegment options = arena.allocate(SYMBOLIC_OPTIONS_LAYOUT);
        options.set(ValueLayout.ADDRESS, offsetOf(SYMBOLIC_OPTIONS_LAYOUT, FIELD_MALLOC), MALLOC);
        options.set(ValueLayout.ADDRESS, offsetOf(SYMBOLIC_OPTIONS_LAYOUT, FIELD_FREE), FREE);
        options.set(ValueLayout.ADDRESS, offsetOf(SYMBOLIC_OPTIONS_LAYOUT, FIELD_REPORT_ERROR),
                REPORT_ERROR_STUB);
        return options;
    }

    /**
     * Populate a SparseNumericFactorOptions with the header's double-precision
     * defaults.
     *
     * @param arena arena owning the struct
     * @return the filled struct segment
     */
    static MemorySegment newNumericOptions(Arena arena) {
        MemorySegment options = arena.allocate(NUMERIC_OPTIONS_LAYOUT);
        options.set(ValueLayout.JAVA_DOUBLE, offsetOf(NUMERIC_OPTIONS_LAYOUT, FIELD_PIVOT_TOLERANCE),
                DEFAULT_PIVOT_TOLERANCE);
        options.set(ValueLayout.JAVA_DOUBLE, offsetOf(NUMERIC_OPTIONS_LAYOUT, FIELD_ZERO_TOLERANCE),
                DEFAULT_ZERO_TOLERANCE);
        return options;
    }

    /**
     * Populate a DenseMatrix_Double viewing {@code data} as one dense column.
     *
     * @param arena     arena owning the struct
     * @param dimension number of rows
     * @param data      column data, {@code dimension} doubles
     * @return the filled struct segment
     */
    static MemorySegment newDenseColumn(Arena arena, int dimension, MemorySegment data) {
        MemorySegment column = arena.allocate(DENSE_MATRIX_LAYOUT);
        column.set(ValueLayout.JAVA_INT, offsetOf(DENSE_MATRIX_LAYOUT, FIELD_ROW_COUNT), dimension);
        column.set(ValueLayout.JAVA_INT, offsetOf(DENSE_MATRIX_LAYOUT, FIELD_COLUMN_COUNT), 1);
        column.set(ValueLayout.JAVA_INT, offsetOf(DENSE_MATRIX_LAYOUT, FIELD_COLUMN_STRIDE),
                dimension);
        column.set(ValueLayout.ADDRESS, offsetOf(DENSE_MATRIX_LAYOUT, FIELD_DATA), data);
        return column;
    }
}
