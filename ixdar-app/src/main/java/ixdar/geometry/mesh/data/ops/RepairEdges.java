package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;

import ixdar.geometry.mesh.data.representation.EdgeKey;

/**
 * The undirected edges of a repair's working triangles and the corner slots that use each one,
 * built once and then edited face by face as the stages add, drop and turn faces, so no stage
 * pays for a pass over the whole mesh to see the few faces it changed.
 */
public final class RepairEdges {

    public static final int NO_EDGE = -1;

    private static final int CORNERS = 3;

    private static final int GROWTH = 2;

    private static final int KEY_SHIFT = 32;

    private static final long KEY_MASK = 0xFFFFFFFFL;

    private long[] keys = new long[0];

    private int[] useCounts = new int[0];

    private int[] firstUses = new int[0];

    private int[] secondUses = new int[0];

    /** Uses past the second, sorted, for the few edges more than two faces share. */
    private int[][] moreUses = new int[0][];

    private int edgeCount;

    /** Edges whose uses changed since {@link #clearChanged}, the first {@link #changedCount}. */
    private int[] changedEdges = new int[0];

    private int changedCount;

    /** Per edge, whether it is listed in {@link #changedEdges}. */
    private boolean[] edgeChanged = new boolean[0];

    private int[] edgeOfSlot = new int[0];

    private boolean[] forward = new boolean[0];

    /** Per vertex the first initial edge it is the lower end of; initial edges run sorted. */
    private int[] lowStart = new int[1];

    private int initialVertices;

    /** Per vertex the latest edge added after the initial build with it as the lower end. */
    private int[] appendedHead = new int[0];

    /** Per edge the previous edge added with the same lower end, or {@link #NO_EDGE}. */
    private int[] appendedNext = new int[0];

    /** Per vertex plus a tail, where its faces start in {@link #filedFaces}, as built. */
    private int[] filedStart = new int[1];

    /** The alive faces round each vertex as built, in slot order, filed in the build's own passes. */
    private int[] filedFaces = new int[0];

    /** Per slot as built, one more than its manifold partner's slot, else zero. */
    private int[] partnerAsBuilt = new int[0];

    /** The single-use edges as built, ascending. */
    private int[] openAsBuilt = new int[0];

    /**
     * Builds the edges of the alive faces in (lower, higher) vertex order, each edge's uses in
     * slot order: one pass counts, one files every use under its lower vertex and every face
     * round its corners, and one sorts each lower vertex's few uses and lays out its edges.
     *
     * @param corners     three vertices per face slot
     * @param alive       per face, whether it counts
     * @param faceCount   face slots in use
     * @param vertexCount vertices in use
     * @return the edges
     */
    public static RepairEdges build(int[] corners, boolean[] alive, int faceCount, int vertexCount) {
        RepairEdges edges = new RepairEdges();
        int slots = faceCount * CORNERS;
        edges.ensureSlots(slots);
        edges.ensureVertices(vertexCount);
        int[] lowStart = new int[vertexCount + 1];
        int[] aroundStart = new int[vertexCount + 1];
        int useTotal = 0;
        for (int slot = 0; slot < slots; slot++) {
            if (alive[slot / CORNERS]) {
                int from = corners[slot];
                int to = corners[next(slot)];
                edges.forward[slot] = from < to;
                lowStart[Math.min(from, to) + 1]++;
                aroundStart[from + 1]++;
                useTotal++;
            }
        }
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            lowStart[vertex + 1] += lowStart[vertex];
            aroundStart[vertex + 1] += aroundStart[vertex];
        }
        // Each use filed under its lower vertex as (higher, slot), in slot order, and each face
        // round its corners in the same walk.
        long[] byLow = new long[useTotal];
        int[] around = new int[useTotal];
        int[] cursor = Arrays.copyOf(lowStart, vertexCount);
        int[] aroundCursor = Arrays.copyOf(aroundStart, vertexCount);
        for (int slot = 0; slot < slots; slot++) {
            if (alive[slot / CORNERS]) {
                int from = corners[slot];
                int to = corners[next(slot)];
                byLow[cursor[Math.min(from, to)]++] = ((long) Math.max(from, to) << KEY_SHIFT) | slot;
                around[aroundCursor[from]++] = slot / CORNERS;
            }
        }
        edges.filedStart = aroundStart;
        edges.filedFaces = around;
        // A vertex's uses, sorted by (higher, slot), are its edges in order, each one's uses in
        // slot order: the order a stable sort by higher and then by lower vertex leaves.
        edges.ensureEdges(useTotal);
        int[] partner = new int[slots];
        int[] open = new int[CORNERS];
        int openCount = 0;
        for (int low = 0; low < vertexCount; low++) {
            int first = lowStart[low];
            int end = lowStart[low + 1];
            for (int use = first + 1; use < end; use++) {
                long packed = byLow[use];
                int at = use;
                while (at > first && byLow[at - 1] > packed) {
                    byLow[at] = byLow[at - 1];
                    at--;
                }
                byLow[at] = packed;
            }
            edges.lowStart[low] = edges.edgeCount;
            int firstEdge = edges.edgeCount;
            for (int use = first; use < end; use++) {
                int high = (int) (byLow[use] >>> KEY_SHIFT);
                int slot = (int) (byLow[use] & KEY_MASK);
                if (use == first || high != (int) (byLow[use - 1] >>> KEY_SHIFT)) {
                    edges.keys[edges.edgeCount++] = EdgeKey.undirected(low, high);
                }
                edges.appendUse(edges.edgeCount - 1, slot);
            }
            // This vertex's edges are whole: none of their uses is filed under another vertex.
            for (int edge = firstEdge; edge < edges.edgeCount; edge++) {
                if (edges.manifold(edge)) {
                    partner[edges.firstUses[edge]] = edges.secondUses[edge] + 1;
                    partner[edges.secondUses[edge]] = edges.firstUses[edge] + 1;
                } else if (edges.useCounts[edge] == 1) {
                    if (openCount == open.length) {
                        open = Arrays.copyOf(open, openCount * GROWTH);
                    }
                    open[openCount++] = edge;
                }
            }
        }
        edges.lowStart[vertexCount] = edges.edgeCount;
        edges.initialVertices = vertexCount;
        edges.partnerAsBuilt = partner;
        edges.openAsBuilt = Arrays.copyOf(open, openCount);
        return edges;
    }

    /**
     * Per slot when the edges were built, one more than the slot across its side when that side
     * is manifold, else zero.
     *
     * @return the partners, one more than each slot
     */
    public int[] partnersAsBuilt() {
        return partnerAsBuilt;
    }

    /**
     * The edges with a single use when the edges were built, ascending.
     *
     * @return the open edges
     */
    public int[] openEdgesAsBuilt() {
        return openAsBuilt;
    }

    /**
     * Per vertex plus a tail, where the faces round it start in {@link #facesAroundAsBuilt}.
     *
     * @return the starts, for the faces as they stood when the edges were built
     */
    public int[] facesAroundStartAsBuilt() {
        return filedStart;
    }

    /**
     * The alive faces round each vertex when the edges were built, each vertex's in slot order.
     *
     * @return the faces, row by row
     */
    public int[] facesAroundAsBuilt() {
        return filedFaces;
    }

    /**
     * Number of edges, including any every face has since left; those report no uses.
     *
     * @return the edge count
     */
    public int edgeCount() {
        return edgeCount;
    }

    /**
     * The packed undirected key of an edge.
     *
     * @param edge edge index
     * @return the {@link EdgeKey} key
     */
    public long key(int edge) {
        return keys[edge];
    }

    /**
     * How many corner slots use an edge.
     *
     * @param edge edge index
     * @return the use count, zero for an edge every face has left
     */
    public int useCount(int edge) {
        return useCounts[edge];
    }

    /**
     * One use of an edge; uses run in slot order.
     *
     * @param edge  edge index
     * @param index use index below {@link #useCount}
     * @return the corner slot, face times three plus corner, whose outgoing side is the edge
     */
    public int use(int edge, int index) {
        return index == 0 ? firstUses[edge] : index == 1 ? secondUses[edge] : moreUses[edge][index - 2];
    }

    /**
     * Whether a slot runs from the lower to the higher vertex of its edge.
     *
     * @param slot corner slot
     * @return true when it runs upward
     */
    public boolean forward(int slot) {
        return forward[slot];
    }

    /**
     * The edge a slot's outgoing side lies on.
     *
     * @param slot corner slot
     * @return the edge, or {@link #NO_EDGE} for a slot of a face not in the table
     */
    public int edgeOfSlot(int slot) {
        return edgeOfSlot[slot];
    }

    /**
     * Whether an edge carries exactly two faces running it in opposite directions.
     *
     * @param edge edge index
     * @return true when manifold
     */
    public boolean manifold(int edge) {
        return useCounts[edge] == 2 && forward[firstUses[edge]] != forward[secondUses[edge]];
    }

    /**
     * The edge between two vertices, if any face has used it.
     *
     * @param first  one vertex
     * @param second the other
     * @return the edge, or {@link #NO_EDGE}
     */
    public int find(int first, int second) {
        int low = Math.min(first, second);
        long key = EdgeKey.undirected(low, Math.max(first, second));
        if (low < initialVertices) {
            for (int edge = lowStart[low]; edge < lowStart[low + 1]; edge++) {
                if (keys[edge] == key) {
                    return edge;
                }
            }
        }
        for (int edge = low < appendedHead.length ? appendedHead[low] : NO_EDGE; edge != NO_EDGE;
                edge = appendedNext[edge]) {
            if (keys[edge] == key) {
                return edge;
            }
        }
        return NO_EDGE;
    }

    /**
     * Files a face's three slots under their edges, adding edges no face had used.
     *
     * @param face        face slot
     * @param corners     three vertices per face slot, the face's already set
     * @param vertexCount vertices in use, so the per-vertex chains can grow
     */
    public void addFace(int face, int[] corners, int vertexCount) {
        ensureSlots((face + 1) * CORNERS);
        ensureVertices(vertexCount);
        for (int slot = face * CORNERS; slot < (face + 1) * CORNERS; slot++) {
            int from = corners[slot];
            int to = corners[next(slot)];
            int edge = find(from, to);
            if (edge == NO_EDGE) {
                ensureEdges(edgeCount + 1);
                edge = edgeCount++;
                int low = Math.min(from, to);
                keys[edge] = EdgeKey.undirected(low, Math.max(from, to));
                appendedNext[edge] = appendedHead[low];
                appendedHead[low] = edge;
            }
            forward[slot] = from < to;
            edgeOfSlot[slot] = edge;
            insertUse(edge, slot);
        }
    }

    /**
     * Takes a face's three slots off their edges; an edge left with no use stays, reporting none.
     *
     * @param face face slot
     */
    public void removeFace(int face) {
        for (int slot = face * CORNERS; slot < Math.min((face + 1) * CORNERS, edgeOfSlot.length); slot++) {
            int edge = edgeOfSlot[slot];
            if (edge == NO_EDGE) {
                continue;
            }
            removeUse(edge, slot);
            edgeOfSlot[slot] = NO_EDGE;
        }
    }

    /**
     * Re-files a face whose second and third corners were just swapped, in place: it keeps its
     * three edges, the slot on its first side moves to its third corner and back, and every
     * direction turns. The table ends as {@link #removeFace} then {@link #addFace} would leave it.
     *
     * @param face    face slot, filed before the swap
     * @param corners three vertices per face slot, the face's already swapped
     */
    public void flipFace(int face, int[] corners) {
        int first = face * CORNERS;
        int firstEdge = edgeOfSlot[first];
        int lastEdge = edgeOfSlot[first + 2];
        replaceUse(firstEdge, first, first + 2);
        replaceUse(lastEdge, first + 2, first);
        noteChanged(edgeOfSlot[first + 1]);
        edgeOfSlot[first] = lastEdge;
        edgeOfSlot[first + 2] = firstEdge;
        for (int slot = first; slot < first + CORNERS; slot++) {
            forward[slot] = corners[slot] < corners[next(slot)];
        }
    }

    /**
     * Swaps one use of an edge for another slot of the same face, which keeps the slot order.
     *
     * @param edge edge index
     * @param from the slot leaving
     * @param to   the slot taking its place
     */
    private void replaceUse(int edge, int from, int to) {
        noteChanged(edge);
        for (int index = 0; index < useCounts[edge]; index++) {
            if (use(edge, index) == from) {
                setUse(edge, index, to);
                return;
            }
        }
    }

    private void appendUse(int edge, int slot) {
        int count = useCounts[edge]++;
        edgeOfSlot[slot] = edge;
        if (count == 0) {
            firstUses[edge] = slot;
        } else if (count == 1) {
            secondUses[edge] = slot;
        } else {
            int[] more = moreUses[edge] == null ? new int[CORNERS] : moreUses[edge];
            if (count - 2 == more.length) {
                more = Arrays.copyOf(more, more.length * GROWTH);
            }
            more[count - 2] = slot;
            moreUses[edge] = more;
        }
    }

    /**
     * How many edges' uses changed since {@link #clearChanged}.
     *
     * @return the count
     */
    public int changedEdgeCount() {
        return changedCount;
    }

    /**
     * One edge whose uses changed since {@link #clearChanged}, in the order they first changed.
     *
     * @param index below {@link #changedEdgeCount}
     * @return the edge
     */
    public int changedEdge(int index) {
        return changedEdges[index];
    }

    /** Forgets which edges changed, so the next edits are listed afresh. */
    public void clearChanged() {
        for (int index = 0; index < changedCount; index++) {
            edgeChanged[changedEdges[index]] = false;
        }
        changedCount = 0;
    }

    private void noteChanged(int edge) {
        if (edgeChanged.length <= edge) {
            edgeChanged = Arrays.copyOf(edgeChanged, Math.max(edge + 1, edgeChanged.length * GROWTH));
        }
        if (edgeChanged[edge]) {
            return;
        }
        edgeChanged[edge] = true;
        if (changedCount == changedEdges.length) {
            changedEdges = Arrays.copyOf(changedEdges, Math.max(CORNERS, changedCount * GROWTH));
        }
        changedEdges[changedCount++] = edge;
    }

    private void insertUse(int edge, int slot) {
        noteChanged(edge);
        appendUse(edge, slot);
        // Keep slot order: walk the new use down past larger ones.
        for (int index = useCounts[edge] - 1; index > 0 && use(edge, index - 1) > slot; index--) {
            setUse(edge, index, use(edge, index - 1));
            setUse(edge, index - 1, slot);
        }
    }

    private void removeUse(int edge, int slot) {
        noteChanged(edge);
        int count = useCounts[edge];
        int index = 0;
        while (index < count && use(edge, index) != slot) {
            index++;
        }
        for (; index < count - 1; index++) {
            setUse(edge, index, use(edge, index + 1));
        }
        useCounts[edge] = count - 1;
    }

    private void setUse(int edge, int index, int slot) {
        if (index == 0) {
            firstUses[edge] = slot;
        } else if (index == 1) {
            secondUses[edge] = slot;
        } else {
            moreUses[edge][index - 2] = slot;
        }
    }

    private void ensureSlots(int slots) {
        if (edgeOfSlot.length < slots) {
            int capacity = Math.max(slots, edgeOfSlot.length * GROWTH);
            int old = edgeOfSlot.length;
            edgeOfSlot = Arrays.copyOf(edgeOfSlot, capacity);
            Arrays.fill(edgeOfSlot, old, capacity, NO_EDGE);
            forward = Arrays.copyOf(forward, capacity);
        }
    }

    private void ensureVertices(int vertices) {
        if (appendedHead.length < vertices) {
            int capacity = Math.max(vertices, appendedHead.length * GROWTH);
            int old = appendedHead.length;
            appendedHead = Arrays.copyOf(appendedHead, capacity);
            Arrays.fill(appendedHead, old, capacity, NO_EDGE);
            if (lowStart.length < vertices + 1) {
                lowStart = Arrays.copyOf(lowStart, vertices + 1);
            }
        }
    }

    private void ensureEdges(int edges) {
        if (keys.length < edges) {
            int capacity = Math.max(edges, keys.length * GROWTH);
            keys = Arrays.copyOf(keys, capacity);
            useCounts = Arrays.copyOf(useCounts, capacity);
            firstUses = Arrays.copyOf(firstUses, capacity);
            secondUses = Arrays.copyOf(secondUses, capacity);
            moreUses = Arrays.copyOf(moreUses, capacity);
            int old = appendedNext.length;
            appendedNext = Arrays.copyOf(appendedNext, capacity);
            Arrays.fill(appendedNext, old, capacity, NO_EDGE);
        }
    }

    private static int next(int slot) {
        return slot % CORNERS == 2 ? slot - 2 : slot + 1;
    }
}
