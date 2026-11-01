package ixdar.geometry.mesh.data.bundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * One set of marked mesh edges, the edge loop a ring or bisector snaps to, plus accessors for the
 * {@link #SLOT} bundle slot of named per-edge mark arrays written by {@code mark_edges}. Arrays are
 * indexed by edge id, not active index; mixing the two silently reads the wrong edges.
 */
public final class EdgeMarks {

    public static final String SLOT = "_edge_marks";

    public static final double FINGERPRINT_ROUND_SCALE = 1.0e5;

    public static final EdgeMarks NONE = new EdgeMarks(new boolean[0], 0);

    public static final String LABEL_SEPARATOR = ";";

    private static final long FINGERPRINT_OFFSET_BASIS = 0xcbf29ce484222325L;

    private static final long FINGERPRINT_PRIME = 0x100000001b3L;

    /** Edge-id-indexed mask, true on every marked mesh edge. */
    public final boolean[] markedByEdgeId;

    /** Ascending mesh edge ids of the marked edges. */
    public final int[] markedEdgeIds;

    /** Mesh edges marked by {@link #markedByEdgeId}. */
    public final int markedEdgeCount;

    /** Gaps the snap that produced the marks could not close. */
    public final int unresolvedGaps;

    /**
     * Wraps a mask without copying it and lists its marked edge ids.
     *
     * @param markedByEdgeId edge-id-indexed mask
     * @param unresolvedGaps gaps the producing snap left open
     */
    public EdgeMarks(boolean[] markedByEdgeId, int unresolvedGaps) {
        this.markedByEdgeId = markedByEdgeId;
        this.unresolvedGaps = unresolvedGaps;
        int count = 0;
        for (boolean marked : markedByEdgeId) {
            count += marked ? 1 : 0;
        }
        markedEdgeCount = count;
        markedEdgeIds = new int[count];
        int next = 0;
        for (int edgeId = 0; edgeId < markedByEdgeId.length; edgeId++) {
            if (markedByEdgeId[edgeId]) {
                markedEdgeIds[next++] = edgeId;
            }
        }
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
     * A statement's list of mark labels, such as a bisector's end walls or the rings a ring is
     * cut along, written {@code "ring_a; ring_b"}.
     *
     * @param text the argument's text, or {@code null}
     * @return the labels, stripped, in order; none for blank text
     */
    public static String[] parseLabels(String text) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }
        String[] labels = text.split(LABEL_SEPARATOR);
        for (int index = 0; index < labels.length; index++) {
            labels[index] = labels[index].strip();
        }
        return labels;
    }

    /**
     * Mark labels as one statement argument, as {@link #parseLabels} reads it back.
     *
     * @param labels the labels, in order
     * @return them joined by {@link #LABEL_SEPARATOR} and a space
     */
    public static String formatLabels(String[] labels) {
        return String.join(LABEL_SEPARATOR + " ", labels);
    }

    /**
     * How many edges at a vertex a mask marks, counting only those another mask marks too when
     * one is given.
     *
     * @param mesh          surface the masks index by edge id
     * @param vertexId      the vertex
     * @param marksByEdgeId edge-id-indexed mask
     * @param alsoMarked    a second mask every counted edge must also be marked in, or
     *                      {@code null} for none
     * @return the count, 0 for none
     */
    public static int markedAt(MeshTopology mesh, int vertexId, boolean[] marksByEdgeId,
            boolean[] alsoMarked) {
        int count = 0;
        for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertexId); spoke++) {
            int edgeId = mesh.vertexEdgeAt(vertexId, spoke);
            count += edgeId < marksByEdgeId.length && marksByEdgeId[edgeId]
                    && (alsoMarked == null || edgeId < alsoMarked.length && alsoMarked[edgeId])
                    ? 1 : 0;
        }
        return count;
    }

    /**
     * The next marked edge of a walk at a vertex: one the mask marks other than the edge the walk
     * came in on, or -1 for a dead end.
     *
     * @param mesh          surface the mask indexes by edge id
     * @param vertexId      the vertex the walk stands on
     * @param cameFrom      edge id the walk arrived by
     * @param marksByEdgeId edge-id-indexed mask
     * @return the first such edge round the vertex, or -1
     */
    public static int nextMarkedEdge(MeshTopology mesh, int vertexId, int cameFrom,
            boolean[] marksByEdgeId) {
        for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertexId); spoke++) {
            int edgeId = mesh.vertexEdgeAt(vertexId, spoke);
            if (edgeId != cameFrom && edgeId < marksByEdgeId.length && marksByEdgeId[edgeId]) {
                return edgeId;
            }
        }
        return -1;
    }

    /**
     * The vertices of a closed loop of marked edges in walking order, or an empty array when the
     * marks do not form exactly one simple cycle.
     *
     * @param mesh          surface the mask indexes by edge id
     * @param marksByEdgeId edge-id-indexed mask
     * @return the loop's vertices, each once
     */
    public static int[] orderedLoop(MeshTopology mesh, boolean[] marksByEdgeId) {
        if (mesh == null || marksByEdgeId == null) {
            return new int[0];
        }
        int marked = 0;
        int firstEdgeId = -1;
        for (int index = 0; index < mesh.edgeCount(); index++) {
            int edgeId = mesh.edgeIdAt(index);
            if (edgeId < marksByEdgeId.length && marksByEdgeId[edgeId]) {
                marked++;
                firstEdgeId = firstEdgeId < 0 ? edgeId : firstEdgeId;
            }
        }
        if (firstEdgeId < 0) {
            return new int[0];
        }
        int[] loop = new int[marked];
        int halfEdge = mesh.edgeHalfEdge(firstEdgeId);
        int start = mesh.halfEdgeVertex(halfEdge);
        int vertexId = mesh.halfEdgeEndVertex(halfEdge);
        int cameFrom = firstEdgeId;
        loop[0] = start;
        int length = 1;
        while (vertexId != start) {
            if (length >= marked) {
                return new int[0];
            }
            loop[length++] = vertexId;
            int next = nextMarkedEdge(mesh, vertexId, cameFrom, marksByEdgeId);
            if (next < 0) {
                return new int[0];
            }
            vertexId = mesh.edgeOtherVertex(next, vertexId);
            cameFrom = next;
        }
        return length == marked ? loop : new int[0];
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
