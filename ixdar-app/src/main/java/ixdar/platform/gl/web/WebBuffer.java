package ixdar.platform.gl.web;

import org.teavm.jso.typedarrays.Float32Array;

import ixdar.platform.gl.IxBuffer;

public class WebBuffer implements IxBuffer {

    Float32Array fb;
    private int capacity;
    private int remaining;

    /**
     * Allocate a JS {@code Float32Array} of {@code capacity} floats.
     *
     * @param capacity number of floats
     */
    public WebBuffer(int capacity) {
        this.capacity = capacity;
        fb = Float32Array.create(capacity);
        remaining = capacity;
    }

    /**
     * No-op: the WebGL upload paths read the entire {@code Float32Array}, so flipping is
     * unnecessary.
     */
    @Override
    public void flip() {
    }

    /**
     * Reset {@link #remaining} to the full capacity (next {@link #put} writes at index 0).
     */
    @Override
    public void clear() {
        remaining = capacity;
    }

    /**
     * @return floats not yet written via {@link #put}
     */
    @Override
    public int remaining() {
        return remaining;
    }

    /**
     * Append at index {@code capacity - remaining} and decrement {@link #remaining}.
     *
     * @param x1 float to write
     * @return this buffer
     */
    @Override
    public IxBuffer put(float x1) {

        fb.set(capacity-remaining, x1);
        remaining--;
        return this;
    }

    /**
     * @return capacity passed to the constructor
     */
    @Override
    public int capacity() {
        return capacity;
    }

    /**
     * Direct access to the underlying typed array; pass to WebGL calls.
     *
     * @return wrapped {@link Float32Array}
     */
    public Float32Array getFloatBuffer() {
        return fb;
    }

    /**
     * Absolute read from the typed array.
     *
     * @param i index
     * @return {@code fb.get(i)}
     */
    @Override
    public Float get(int i) {
        return fb.get(i);
    }
}
