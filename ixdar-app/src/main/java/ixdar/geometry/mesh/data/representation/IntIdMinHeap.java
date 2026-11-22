package ixdar.geometry.mesh.data.representation;

import java.util.Arrays;

/**
 * Indexed min-heap of int ids by double priority on primitive arrays, each id held once so the top
 * is never stale; ties pop in id order.
 */
public final class IntIdMinHeap {

    private static final int ABSENT = -1;

    /** Whether equal priorities pop the larger id first. */
    public final boolean largerIdFirstOnTies;

    private int[] heapIds;

    private double[] heapPriorities;

    private int size;

    /** Heap slot per id, {@link #ABSENT} for an id not held. */
    private int[] slotById;

    /**
     * Creates an empty heap whose ties pop the smaller id first.
     *
     * @param idBound expected one-past-largest id; larger ids grow the heap
     */
    public IntIdMinHeap(int idBound) {
        this(idBound, false);
    }

    /**
     * Creates an empty heap.
     *
     * @param idBound             expected one-past-largest id; larger ids grow the heap
     * @param largerIdFirstOnTies whether equal priorities pop the larger id first
     */
    public IntIdMinHeap(int idBound, boolean largerIdFirstOnTies) {
        this.largerIdFirstOnTies = largerIdFirstOnTies;
        int capacity = Math.max(1, idBound);
        this.heapIds = new int[capacity];
        this.heapPriorities = new double[capacity];
        this.slotById = new int[capacity];
        Arrays.fill(slotById, ABSENT);
    }

    /**
     * The number of ids held.
     *
     * @return count of ids
     */
    public int size() {
        return size;
    }

    /**
     * Whether the heap holds no ids.
     *
     * @return true if size is zero
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Whether an id is held.
     *
     * @param id non-negative id
     * @return true while the id is in the heap
     */
    public boolean contains(int id) {
        return id < slotById.length && slotById[id] != ABSENT;
    }

    /**
     * Adds an id at a priority, or moves it there when it is already held.
     *
     * @param id       non-negative id
     * @param priority key the id pops in order of
     */
    public void insertOrUpdate(int id, double priority) {
        if (id >= slotById.length) {
            int grown = Math.max(id + 1, slotById.length * 2);
            int previous = slotById.length;
            slotById = Arrays.copyOf(slotById, grown);
            Arrays.fill(slotById, previous, grown, ABSENT);
        }
        int slot = slotById[id];
        if (slot == ABSENT) {
            if (size == heapIds.length) {
                heapIds = Arrays.copyOf(heapIds, size * 2);
                heapPriorities = Arrays.copyOf(heapPriorities, size * 2);
            }
            slot = size++;
        }
        place(slot, id, priority);
    }

    /**
     * Removes an id if held.
     *
     * @param id non-negative id
     */
    public void remove(int id) {
        if (!contains(id)) {
            return;
        }
        int slot = slotById[id];
        slotById[id] = ABSENT;
        size--;
        if (slot < size) {
            place(slot, heapIds[size], heapPriorities[size]);
        }
    }

    /**
     * The id that pops next, left in place.
     *
     * @throws IllegalStateException when the heap is empty
     * @return the id of least priority
     */
    public int peekId() {
        requireNonEmpty();
        return heapIds[0];
    }

    /**
     * The priority of the id that pops next.
     *
     * @throws IllegalStateException when the heap is empty
     * @return the least priority held
     */
    public double peekPriority() {
        requireNonEmpty();
        return heapPriorities[0];
    }

    /**
     * Removes and returns the id of least priority.
     *
     * @throws IllegalStateException when the heap is empty
     * @return that id
     */
    public int pollId() {
        int id = peekId();
        remove(id);
        return id;
    }

    /** Empties the heap, keeping its storage. */
    public void clear() {
        for (int slot = 0; slot < size; slot++) {
            slotById[heapIds[slot]] = ABSENT;
        }
        size = 0;
    }

    private void requireNonEmpty() {
        if (size == 0) {
            throw new IllegalStateException("the heap is empty");
        }
    }

    /**
     * Puts an id at a slot, then sifts it up or down to where its priority belongs.
     *
     * @param slot     slot being filled
     * @param id       id to place
     * @param priority its priority
     */
    private void place(int slot, int id, double priority) {
        while (slot > 0) {
            int parent = (slot - 1) >>> 1;
            if (!before(id, priority, heapIds[parent], heapPriorities[parent])) {
                break;
            }
            store(slot, heapIds[parent], heapPriorities[parent]);
            slot = parent;
        }
        int half = size >>> 1;
        while (slot < half) {
            int child = 2 * slot + 1;
            int right = child + 1;
            if (right < size && before(heapIds[right], heapPriorities[right], heapIds[child],
                    heapPriorities[child])) {
                child = right;
            }
            if (!before(heapIds[child], heapPriorities[child], id, priority)) {
                break;
            }
            store(slot, heapIds[child], heapPriorities[child]);
            slot = child;
        }
        store(slot, id, priority);
    }

    private void store(int slot, int id, double priority) {
        heapIds[slot] = id;
        heapPriorities[slot] = priority;
        slotById[id] = slot;
    }

    private boolean before(int id, double priority, int otherId, double otherPriority) {
        int byPriority = Double.compare(priority, otherPriority);
        if (byPriority != 0) {
            return byPriority < 0;
        }
        return largerIdFirstOnTies ? id > otherId : id < otherId;
    }
}
