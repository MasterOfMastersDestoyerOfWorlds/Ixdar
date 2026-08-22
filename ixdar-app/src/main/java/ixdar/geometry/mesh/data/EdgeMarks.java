package ixdar.geometry.mesh.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accessors for the {@link #SLOT} bundle slot: named per-edge mark arrays written by
 * {@code mark_edges}. Arrays are indexed by edge id, not active index; mixing the two silently
 * reads the wrong edges.
 */
public final class EdgeMarks {

    /** Bundle slot holding {@code Map<String, boolean[] | int[] | float[]>} keyed by label. */
    public static final String SLOT = "_edge_marks";

    private EdgeMarks() {
    }

    /**
     * The float marks under {@code label}.
     *
     * @param bundle geometry bundle to read
     * @param label mark label, e.g. {@code "crease"}
     * @return edge-id-indexed weights, or {@code null} when the label is absent
     * @throws IllegalStateException if the label holds marks of another type
     */
    public static float[] floats(GeometryBundle bundle, String label) {
        Object marks = get(bundle, label);
        if (marks == null || marks instanceof float[]) {
            return (float[]) marks;
        }
        throw typeMismatch(label, "float", marks);
    }

    /**
     * The int marks under {@code label}.
     *
     * @param bundle geometry bundle to read
     * @param label mark label, e.g. a quantized arc length
     * @return edge-id-indexed values, or {@code null} when the label is absent
     * @throws IllegalStateException if the label holds marks of another type
     */
    public static int[] ints(GeometryBundle bundle, String label) {
        Object marks = get(bundle, label);
        if (marks == null || marks instanceof int[]) {
            return (int[]) marks;
        }
        throw typeMismatch(label, "int", marks);
    }

    /**
     * The boolean marks under {@code label}.
     *
     * @param bundle geometry bundle to read
     * @param label mark label, e.g. a T-mesh arc mark
     * @return edge-id-indexed mask, or {@code null} when the label is absent
     * @throws IllegalStateException if the label holds marks of another type
     */
    public static boolean[] bools(GeometryBundle bundle, String label) {
        Object marks = get(bundle, label);
        if (marks == null || marks instanceof boolean[]) {
            return (boolean[]) marks;
        }
        throw typeMismatch(label, "boolean", marks);
    }

    /**
     * A copy of {@code bundle} with {@code label} set to {@code marks}, preserving other labels.
     *
     * @param bundle geometry bundle to copy
     * @param label mark label to write
     * @param marks edge-id-indexed {@code boolean[]}, {@code int[]} or {@code float[]}
     * @return the bundle copy carrying the updated marks map
     */
    public static GeometryBundle with(GeometryBundle bundle, String label, Object marks) {
        Map<String, Object> all = new LinkedHashMap<>();
        Object existing = bundle.slots().get(SLOT);
        if (existing instanceof Map<?, ?> prev) {
            for (Map.Entry<?, ?> entry : prev.entrySet()) {
                all.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        all.put(label, marks);
        return bundle.withSlot(SLOT, all);
    }

    private static Object get(GeometryBundle bundle, String label) {
        Object slot = bundle.slots().get(SLOT);
        return slot instanceof Map<?, ?> marks ? marks.get(label) : null;
    }

    private static IllegalStateException typeMismatch(String label, String expected, Object got) {
        return new IllegalStateException("Edge marks '" + label + "' expected " + expected
                + "[] but hold " + got.getClass().getSimpleName());
    }
}
