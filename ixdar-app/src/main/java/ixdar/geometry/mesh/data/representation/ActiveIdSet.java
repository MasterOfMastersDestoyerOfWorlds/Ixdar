package ixdar.geometry.mesh.data.representation;

import java.util.Arrays;

/**
 * A dense set of live element ids over {@code [0, size)}, with an inverse index-by-id map so
 * that removal is a lookup rather than a scan.
 *
 * <p><b>Order is not preserved.</b> Removal moves the last id into the hole, so a position is
 * valid only until the next removal.
 */
public final class ActiveIdSet {

    /** Smallest backing array this will allocate. */
    private static final int MINIMUM_CAPACITY = 4;

    /** Marks an id that is not in the set. */
    private static final int ABSENT = -1;

    /** Live ids, dense over {@code [0, size)}. */
    private int[] ids;

    /** Position of each id within {@link #ids}, or {@link #ABSENT}. */
    private int[] indexById;

    /** Number of live ids. */
    private int size;

    /**
     * Creates an empty set sized for an expected id count.
     *
     * @param initialCapacity expected number of ids, clamped to a safe minimum
     */
    public ActiveIdSet(int initialCapacity) {
        int capacity = Math.max(MINIMUM_CAPACITY, initialCapacity);
        this.ids = new int[capacity];
        this.indexById = new int[capacity];
        Arrays.fill(this.indexById, ABSENT);
        this.size = 0;
    }

    /**
     * The number of live ids.
     *
     * @return count of ids in the set
     */
    public int size() {
        return size;
    }

    /**
     * Whether the set holds no ids.
     *
     * @return true when the size is zero
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * The id at a dense position.
     *
     * @param index zero-based position in {@code [0, size())}
     * @throws IndexOutOfBoundsException when the index is negative or not below the size
     * @return the id stored at that position
     */
    public int get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for size " + size);
        }
        return ids[index];
    }

    /**
     * Whether an id is live, answered by lookup rather than by scanning.
     *
     * @param id id to test
     * @return true when the set holds the id
     */
    public boolean contains(int id) {
        return id >= 0 && id < indexById.length && indexById[id] != ABSENT;
    }

    /**
     * Adds an id, growing the backing arrays as needed. Adding an id the set already holds does
     * nothing, so the dense array never lists an id twice.
     *
     * @param id id to add
     */
    public void add(int id) {
        if (contains(id)) {
            return;
        }
        ensureIdCapacity(id + 1);
        ensureDenseCapacity(size + 1);
        ids[size] = id;
        indexById[id] = size;
        size++;
    }

    /**
     * Empties the set, keeping the backing arrays so a scratch set can be reused without
     * reallocating its id-space index. Costs one pass over the live ids, not over the id space.
     */
    public void clear() {
        for (int index = 0; index < size; index++) {
            indexById[ids[index]] = ABSENT;
        }
        size = 0;
    }

    /**
     * Removes an id by moving the last id into its slot.
     *
     * @param id id to remove
     * @return true when the id was live and has been removed
     */
    public boolean remove(int id) {
        if (!contains(id)) {
            return false;
        }
        int index = indexById[id];
        int lastId = ids[size - 1];
        ids[index] = lastId;
        indexById[lastId] = index;
        indexById[id] = ABSENT;
        size--;
        return true;
    }

    /**
     * Grows the inverse map so it can address an id.
     *
     * @param requiredIdBound smallest id count the map must address
     */
    private void ensureIdCapacity(int requiredIdBound) {
        if (requiredIdBound <= indexById.length) {
            return;
        }
        int previousLength = indexById.length;
        int nextCapacity = Math.max(requiredIdBound, previousLength * 2);
        indexById = Arrays.copyOf(indexById, nextCapacity);
        Arrays.fill(indexById, previousLength, nextCapacity, ABSENT);
    }

    /**
     * Grows the dense array so it can hold another id.
     *
     * @param requiredSize smallest number of ids the dense array must hold
     */
    private void ensureDenseCapacity(int requiredSize) {
        if (requiredSize <= ids.length) {
            return;
        }
        ids = Arrays.copyOf(ids, Math.max(requiredSize, ids.length * 2));
    }
}
