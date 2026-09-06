package ixdar.geometry.mesh.csg;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import com.cadoodlecad.manifold.ManifoldBindings;

/**
 * FFM downcalls into {@code libmanifoldc} that the vendored {@link ManifoldBindings} leave out:
 * stamping a solid as an original and reading a {@code MeshGL64} with its run and face tables.
 * Symbols resolve through the loader lookup, so the vendored binding must load the natives first.
 */
public final class ManifoldProvenanceBindings {

    /** Bytes in a {@code uint64_t} or {@code double} table entry. */
    public static final long EIGHT_BYTES = 8;

    /** Bytes in a {@code uint32_t} table entry. */
    public static final long FOUR_BYTES = 4;

    /** Bytes one {@code ManifoldManifold} occupies, for arena-owned solids. */
    public final long solidSize;

    /** Bytes one {@code ManifoldMeshGL64} occupies, for arena-owned exports. */
    public final long meshSize;

    /** {@code manifold_as_original}: copy a solid, stamping the copy with a fresh original id. */
    public final MethodHandle asOriginal;

    /** {@code manifold_original_id}: the id a solid was stamped with, or {@code -1}. */
    public final MethodHandle originalId;

    /** {@code manifold_destruct_manifold}: run a solid's destructor without freeing its storage. */
    public final MethodHandle destructSolid;

    /** {@code manifold_get_meshgl64}: read a solid back as a mesh with its tables. */
    public final MethodHandle getMesh;

    /** {@code manifold_destruct_meshgl64}: run a mesh's destructor. */
    public final MethodHandle destructMesh;

    /** {@code manifold_meshgl64_vert_properties_length}: entries in the vertex table. */
    public final MethodHandle vertexPropertiesLength;

    /** {@code manifold_meshgl64_tri_length}: entries in the corner table. */
    public final MethodHandle triangleLength;

    /** {@code manifold_meshgl64_run_index_length}: entries in the run-offset table. */
    public final MethodHandle runIndexLength;

    /** {@code manifold_meshgl64_run_original_id_length}: entries in the run-origin table. */
    public final MethodHandle runOriginalIdLength;

    /** {@code manifold_meshgl64_face_id_length}: entries in the face table. */
    public final MethodHandle faceIdLength;

    /** {@code manifold_meshgl64_vert_properties}: copy the vertex table out. */
    public final MethodHandle vertexProperties;

    /** {@code manifold_meshgl64_tri_verts}: copy the corner table out. */
    public final MethodHandle triangleCorners;

    /** {@code manifold_meshgl64_run_index}: copy the run-offset table out. */
    public final MethodHandle runIndex;

    /** {@code manifold_meshgl64_run_original_id}: copy the run-origin table out. */
    public final MethodHandle runOriginalId;

    /** {@code manifold_meshgl64_face_id}: copy the face table out. */
    public final MethodHandle faceId;

    /**
     * Resolve the symbols against the natives the vendored binding loaded.
     *
     * @param loaded the vendored binding, already constructed so the natives are in this loader
     * @throws Throwable if a symbol is missing or a size query fails
     */
    public ManifoldProvenanceBindings(ManifoldBindings loaded) throws Throwable {
        solidSize = loaded.manifoldSize();
        meshSize = loaded.meshGL64Size();
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.loaderLookup();
        FunctionDescriptor pointerOfTwo = FunctionDescriptor.of(ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        FunctionDescriptor longOfOne = FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS);
        FunctionDescriptor voidOfOne = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
        asOriginal = bind(linker, lookup, "manifold_as_original", pointerOfTwo);
        originalId = bind(linker, lookup, "manifold_original_id",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        destructSolid = bind(linker, lookup, "manifold_destruct_manifold", voidOfOne);
        getMesh = bind(linker, lookup, "manifold_get_meshgl64", pointerOfTwo);
        destructMesh = bind(linker, lookup, "manifold_destruct_meshgl64", voidOfOne);
        vertexPropertiesLength = bind(linker, lookup, "manifold_meshgl64_vert_properties_length",
                longOfOne);
        triangleLength = bind(linker, lookup, "manifold_meshgl64_tri_length", longOfOne);
        runIndexLength = bind(linker, lookup, "manifold_meshgl64_run_index_length", longOfOne);
        runOriginalIdLength = bind(linker, lookup, "manifold_meshgl64_run_original_id_length",
                longOfOne);
        faceIdLength = bind(linker, lookup, "manifold_meshgl64_face_id_length", longOfOne);
        vertexProperties = bind(linker, lookup, "manifold_meshgl64_vert_properties", pointerOfTwo);
        triangleCorners = bind(linker, lookup, "manifold_meshgl64_tri_verts", pointerOfTwo);
        runIndex = bind(linker, lookup, "manifold_meshgl64_run_index", pointerOfTwo);
        runOriginalId = bind(linker, lookup, "manifold_meshgl64_run_original_id", pointerOfTwo);
        faceId = bind(linker, lookup, "manifold_meshgl64_face_id", pointerOfTwo);
    }

    /**
     * Resolve one C symbol into a downcall handle.
     *
     * @param linker the native linker
     * @param lookup symbol lookup over the loaded natives
     * @param symbol C function name
     * @param descriptor the function's signature
     * @return the downcall handle
     */
    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String symbol,
            FunctionDescriptor descriptor) {
        MemorySegment address = lookup.find(symbol).orElseThrow(
                () -> new IllegalStateException("libmanifoldc lacks " + symbol));
        return linker.downcallHandle(address, descriptor);
    }

    /**
     * Copy a solid, stamping the copy with a fresh original id so the boolean's run table can name
     * it, and initialising the face ids the kernel carries through the boolean.
     *
     * @param solid the solid to stamp
     * @param arena arena that owns the copy; release it with {@link #destructSolid(MemorySegment)}
     * @return the stamped copy
     * @throws Throwable if the native call fails
     */
    public MemorySegment asOriginal(MemorySegment solid, Arena arena) throws Throwable {
        return (MemorySegment) asOriginal.invoke(arena.allocate(solidSize), solid);
    }

    /**
     * The original id a solid was stamped with by {@link #asOriginal}.
     *
     * @param solid a stamped solid
     * @return its original id, or {@code -1} when the solid is not an original
     * @throws Throwable if the native call fails
     */
    public int originalId(MemorySegment solid) throws Throwable {
        return (int) originalId.invoke(solid);
    }

    /**
     * Run the destructor of an arena-owned solid, before its arena closes.
     *
     * @param solid solid created by {@link #asOriginal}
     * @throws Throwable if the native call fails
     */
    public void destructSolid(MemorySegment solid) throws Throwable {
        destructSolid.invoke(solid);
    }

    /**
     * Read a solid back as a {@code MeshGL64} with its run and face tables.
     *
     * @param solid the solid to read
     * @return the geometry and tables, copied into Java arrays
     * @throws Throwable if a native call fails
     */
    public ManifoldMeshExport export(MemorySegment solid) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mesh = (MemorySegment) getMesh.invoke(arena.allocate(meshSize), solid);
            try {
                double[] positions = readDoubles(arena, mesh, vertexPropertiesLength,
                        vertexProperties);
                long[] corners = readLongs(arena, mesh, triangleLength, triangleCorners);
                long[] runs = readLongs(arena, mesh, runIndexLength, runIndex);
                int[] originals = readInts(arena, mesh, runOriginalIdLength, runOriginalId);
                long[] faces = readLongs(arena, mesh, faceIdLength, faceId);
                return new ManifoldMeshExport(positions, corners, runs, originals, faces);
            } finally {
                destructMesh.invoke(mesh);
            }
        }
    }

    /**
     * Copy one {@code double} table out of a mesh.
     *
     * @param arena arena for the native scratch buffer
     * @param mesh the mesh being read
     * @param lengthQuery handle returning the table's entry count
     * @param tableCopy handle copying the table into a caller buffer
     * @return the table as a Java array
     * @throws Throwable if a native call fails
     */
    private static double[] readDoubles(Arena arena, MemorySegment mesh, MethodHandle lengthQuery,
            MethodHandle tableCopy) throws Throwable {
        long length = (long) lengthQuery.invoke(mesh);
        double[] values = new double[(int) length];
        if (length == 0) {
            return values;
        }
        MemorySegment buffer = arena.allocate(length * EIGHT_BYTES);
        tableCopy.invoke(buffer, mesh);
        MemorySegment.copy(buffer, ValueLayout.JAVA_DOUBLE, 0, values, 0, (int) length);
        return values;
    }

    /**
     * Copy one {@code uint64_t} table out of a mesh.
     *
     * @param arena arena for the native scratch buffer
     * @param mesh the mesh being read
     * @param lengthQuery handle returning the table's entry count
     * @param tableCopy handle copying the table into a caller buffer
     * @return the table as a Java array
     * @throws Throwable if a native call fails
     */
    private static long[] readLongs(Arena arena, MemorySegment mesh, MethodHandle lengthQuery,
            MethodHandle tableCopy) throws Throwable {
        long length = (long) lengthQuery.invoke(mesh);
        long[] values = new long[(int) length];
        if (length == 0) {
            return values;
        }
        MemorySegment buffer = arena.allocate(length * EIGHT_BYTES);
        tableCopy.invoke(buffer, mesh);
        MemorySegment.copy(buffer, ValueLayout.JAVA_LONG, 0, values, 0, (int) length);
        return values;
    }

    /**
     * Copy one {@code uint32_t} table out of a mesh.
     *
     * @param arena arena for the native scratch buffer
     * @param mesh the mesh being read
     * @param lengthQuery handle returning the table's entry count
     * @param tableCopy handle copying the table into a caller buffer
     * @return the table as a Java array
     * @throws Throwable if a native call fails
     */
    private static int[] readInts(Arena arena, MemorySegment mesh, MethodHandle lengthQuery,
            MethodHandle tableCopy) throws Throwable {
        long length = (long) lengthQuery.invoke(mesh);
        int[] values = new int[(int) length];
        if (length == 0) {
            return values;
        }
        MemorySegment buffer = arena.allocate(length * FOUR_BYTES);
        tableCopy.invoke(buffer, mesh);
        MemorySegment.copy(buffer, ValueLayout.JAVA_INT, 0, values, 0, (int) length);
        return values;
    }
}
