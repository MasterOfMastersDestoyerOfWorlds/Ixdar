package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;

/**
 * Accessors for the {@link #SLOT} bundle slot: named per-edge mark arrays written by
 * {@code mark_edges}. Arrays are indexed by edge id, not active index; mixing the two silently
 * reads the wrong edges.
 */
public final class EdgeMarks {

    /** Bundle slot holding {@code Map<String, boolean[] | int[] | float[]>} keyed by label. */
    public static final String SLOT = "_edge_marks";

    /** Coordinate rounding a fingerprint keys marked edges by, matching the mesh fingerprint. */
    public static final double FINGERPRINT_ROUND_SCALE = 1.0e5;

    private static final long FINGERPRINT_OFFSET_BASIS = 0xcbf29ce484222325L;

    private static final long FINGERPRINT_PRIME = 0x100000001b3L;

    private EdgeMarks() {
    }

    /**
     * Geometry-keyed digest of a per-edge mask: the rounded midpoints of every marked edge,
     * sorted, so the same marks on the same surface hash alike whoever produced them.
     *
     * @param mesh          mesh the mask indexes by edge id
     * @param marksByEdgeId edge-id-indexed mask, or {@code null} for none
     * @return a 16-digit hex fingerprint of the marked edge set
     */
    public static String fingerprint(MeshTopology mesh, boolean[] marksByEdgeId) {
        List<String> keys = new ArrayList<>();
        Vector3f tailPosition = new Vector3f();
        Vector3f headPosition = new Vector3f();
        for (int index = 0; marksByEdgeId != null && index < mesh.edgeCount(); index++) {
            int edgeId = mesh.edgeIdAt(index);
            if (edgeId >= marksByEdgeId.length || !marksByEdgeId[edgeId]) {
                continue;
            }
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), tailPosition);
            mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), headPosition);
            keys.add(String.format(Locale.ROOT, "%d,%d,%d",
                    Math.round(FINGERPRINT_ROUND_SCALE * 0.5 * (tailPosition.x + headPosition.x)),
                    Math.round(FINGERPRINT_ROUND_SCALE * 0.5 * (tailPosition.y + headPosition.y)),
                    Math.round(FINGERPRINT_ROUND_SCALE * 0.5
                            * (tailPosition.z + headPosition.z))));
        }
        keys.sort(String::compareTo);
        long hash = FINGERPRINT_OFFSET_BASIS;
        for (String key : keys) {
            for (int character = 0; character < key.length(); character++) {
                hash = (hash ^ key.charAt(character)) * FINGERPRINT_PRIME;
            }
            hash = (hash ^ ';') * FINGERPRINT_PRIME;
        }
        return String.format(Locale.ROOT, "%016x", hash);
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
