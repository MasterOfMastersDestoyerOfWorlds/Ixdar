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

    public boolean isEmpty() {
        return size == 0;
    }

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

    public void add(int value) {
        ensureCapacity(size + 1);
        values[size++] = value;
    }

    public void addUnique(int value) {
        if (!contains(value)) {
            add(value);
        }
    }

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

    public void clear() {
        size = 0;
    }

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
