package ixdar.geometry.mesh.data.representation;

import java.util.Arrays;

public final class IntIdList {
    public static final int NUM_4 = 4;
    private int[] values;
    private int size;

    IntIdList() {
        this(NUM_4);
    }

    IntIdList(int initialCapacity) {
        this.values = new int[Math.max(1, initialCapacity)];
        this.size = 0;
    }

    int size() {
        return size;
    }

    /**
     * Whether the list holds no elements.
     *
     * @return true if size is zero
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns the id stored at the given position.
     *
     * @param index zero-based position into the list
     * @throws IndexOutOfBoundsException if {@code index} is negative or {@code >= size()}
     * @return id at that position
     */
    public int get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for size " + size);
        }
        return values[index];
    }

    boolean contains(int value) {
        for (int i = 0; i < size; i++) {
            if (values[i] == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Appends an id to the tail, growing the backing array if needed.
     *
     * @param value id to append
     */
    public void add(int value) {
        ensureCapacity(size + 1);
        values[size++] = value;
    }

    /**
     * Appends an id only if it is not already present (linear scan).
     *
     * @param value id to append when absent
     */
    public void addUnique(int value) {
        if (!contains(value)) {
            add(value);
        }
    }

    /**
     * Removes the first occurrence of an id, shifting tail entries left to keep order.
     *
     * @param value id to remove
     * @return true if the id was found and removed
     */
    public boolean removeValue(int value) {
        for (int i = 0; i < size; i++) {
            if (values[i] == value) {
                int tail = size - i - 1;
                if (tail > 0) {
                    System.arraycopy(values, i + 1, values, i, tail);
                }
                size--;
                return true;
            }
        }
        return false;
    }

    /**
     * Resets the size to zero without releasing the backing array.
     */
    public void clear() {
        size = 0;
    }

    /**
     * Returns a fresh int[] copy of the live elements (length equals {@link #size()}).
     *
     * @return defensive copy of the contents
     */
    public int[] toArray() {
        return Arrays.copyOf(values, size);
    }

    private void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= values.length) {
            return;
        }
        int nextCapacity = Math.max(requiredCapacity, values.length * 2);
        values = Arrays.copyOf(values, nextCapacity);
    }
}
