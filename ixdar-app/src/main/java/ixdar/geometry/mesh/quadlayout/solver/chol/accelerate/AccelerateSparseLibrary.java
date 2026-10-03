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

    static final String FRAMEWORK_PATH = "/System/Library/Frameworks/Accelerate.framework/Accelerate";

    static final int ATTRIBUTES_SYMMETRIC_LOWER = (1 << 1) | (3 << 2);

    static final byte FACTORIZATION_CHOLESKY = 0;

    static final int SPARSE_STATUS_OK = 0;

    static final double DEFAULT_PIVOT_TOLERANCE = 0.01;

    static final double DEFAULT_ZERO_TOLERANCE = 1.0e-4 * Math.ulp(1.0);

    static final String FIELD_ROW_COUNT = "rowCount";

    static final String FIELD_COLUMN_COUNT = "columnCount";

    static final String FIELD_COLUMN_STARTS = "columnStarts";

    static final String FIELD_ROW_INDICES = "rowIndices";

    static final String FIELD_ATTRIBUTES = "attributes";

    static final String FIELD_BLOCK_SIZE = "blockSize";

    static final String FIELD_DATA = "data";

    static final String FIELD_STATUS = "status";

    static final String FIELD_STRUCTURE = "structure";

    static final String FIELD_SYMBOLIC_FACTORIZATION = "symbolicFactorization";

    static final String FIELD_WORKSPACE_SIZE_DOUBLE = "workspaceSizeDouble";

    static final String FIELD_SOLVE_WORKSPACE_STATIC = "solveWorkspaceRequiredStatic";

    static final String FIELD_SOLVE_WORKSPACE_PER_RHS = "solveWorkspaceRequiredPerRHS";

    static final String FIELD_MALLOC = "malloc";

    static final String FIELD_FREE = "free";

    static final String FIELD_REPORT_ERROR = "reportError";

    static final String FIELD_PIVOT_TOLERANCE = "pivotTolerance";

    static final String FIELD_ZERO_TOLERANCE = "zeroTolerance";

    static final String FIELD_CONTROL = "control";

    static final String FIELD_COLUMN_STRIDE = "columnStride";

    static final GroupLayout MATRIX_STRUCTURE_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName(FIELD_ROW_COUNT),
            ValueLayout.JAVA_INT.withName(FIELD_COLUMN_COUNT),
            ValueLayout.ADDRESS.withName(FIELD_COLUMN_STARTS),
            ValueLayout.ADDRESS.withName(FIELD_ROW_INDICES),
            ValueLayout.JAVA_INT.withName(FIELD_ATTRIBUTES),
            ValueLayout.JAVA_BYTE.withName(FIELD_BLOCK_SIZE),
            MemoryLayout.paddingLayout(3));

    static final GroupLayout SPARSE_MATRIX_LAYOUT = MemoryLayout.structLayout(
            MATRIX_STRUCTURE_LAYOUT.withName(FIELD_STRUCTURE),
            ValueLayout.ADDRESS.withName(FIELD_DATA));

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

    static final GroupLayout FACTORIZATION_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName(FIELD_STATUS),
            ValueLayout.JAVA_INT.withName(FIELD_ATTRIBUTES),
            SYMBOLIC_FACTORIZATION_LAYOUT.withName(FIELD_SYMBOLIC_FACTORIZATION),
            ValueLayout.JAVA_BOOLEAN.withName("userFactorStorage"),
            MemoryLayout.paddingLayout(7),
            ValueLayout.ADDRESS.withName("numericFactorization"),
            ValueLayout.JAVA_LONG.withName(FIELD_SOLVE_WORKSPACE_STATIC),
            ValueLayout.JAVA_LONG.withName(FIELD_SOLVE_WORKSPACE_PER_RHS));

    static final GroupLayout SYMBOLIC_OPTIONS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName(FIELD_CONTROL),
            ValueLayout.JAVA_BYTE.withName("orderMethod"),
            MemoryLayout.paddingLayout(3),
            ValueLayout.ADDRESS.withName("order"),
            ValueLayout.ADDRESS.withName("ignoreRowsAndColumns"),
            ValueLayout.ADDRESS.withName(FIELD_MALLOC),
            ValueLayout.ADDRESS.withName(FIELD_FREE),
            ValueLayout.ADDRESS.withName(FIELD_REPORT_ERROR));

    static final GroupLayout NUMERIC_OPTIONS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName(FIELD_CONTROL),
            ValueLayout.JAVA_BYTE.withName("scalingMethod"),
            MemoryLayout.paddingLayout(3),
            ValueLayout.ADDRESS.withName("scaling"),
            ValueLayout.JAVA_DOUBLE.withName(FIELD_PIVOT_TOLERANCE),
            ValueLayout.JAVA_DOUBLE.withName(FIELD_ZERO_TOLERANCE));

    static final GroupLayout DENSE_MATRIX_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName(FIELD_ROW_COUNT),
            ValueLayout.JAVA_INT.withName(FIELD_COLUMN_COUNT),
            ValueLayout.JAVA_INT.withName(FIELD_COLUMN_STRIDE),
            ValueLayout.JAVA_INT.withName(FIELD_ATTRIBUTES),
            ValueLayout.ADDRESS.withName(FIELD_DATA));

    static final long FACTORIZATION_STATUS_OFFSET = offsetOf(FACTORIZATION_LAYOUT, FIELD_STATUS);

    static final long SYMBOLIC_WORKSPACE_DOUBLE_OFFSET = offsetOf(FACTORIZATION_LAYOUT,
            FIELD_SYMBOLIC_FACTORIZATION, FIELD_WORKSPACE_SIZE_DOUBLE);

    static final long SOLVE_WORKSPACE_STATIC_OFFSET = offsetOf(FACTORIZATION_LAYOUT,
            FIELD_SOLVE_WORKSPACE_STATIC);

    static final long SOLVE_WORKSPACE_PER_RHS_OFFSET = offsetOf(FACTORIZATION_LAYOUT,
            FIELD_SOLVE_WORKSPACE_PER_RHS);

    static final MethodHandle FACTOR_SYMMETRIC;

    static final MethodHandle SOLVE_OPAQUE;

    static final MethodHandle REFACTOR_SYMMETRIC;

    static final MethodHandle DESTROY_OPAQUE_NUMERIC;

    static final MemorySegment MALLOC;

    static final MemorySegment FREE;

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
