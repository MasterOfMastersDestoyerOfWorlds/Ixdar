package ixdar.platform.gl.web;

import org.teavm.jso.typedarrays.Float32Array;

import ixdar.platform.gl.IxBuffer;

public class WebBuffer implements IxBuffer {

    Float32Array fb;
    private int capacity;
    private int remaining;

    /**
     * TODO: document {@code WebBuffer}.
     *
     * @param capacity TODO: describe
     */
    public WebBuffer(int capacity) {
        this.capacity = capacity;
        fb = Float32Array.create(capacity);
        remaining = capacity;
    }

    /**
     * TODO: document {@code flip}.
     */
    @Override
    public void flip() {
    }

    /**
     * TODO: document {@code clear}.
     */
    @Override
    public void clear() {
        remaining = capacity;
    }

    /**
     * TODO: document {@code remaining}.
     *
     * @return TODO: describe
     */
    @Override
    public int remaining() {
        return remaining;
    }

    /**
     * TODO: document {@code put}.
     *
     * @param x1 TODO: describe
     * @return TODO: describe
     */
    @Override
    public IxBuffer put(float x1) {

        fb.set(capacity-remaining, x1);
        remaining--;
        return this;
    }

    /**
     * TODO: document {@code capacity}.
     *
     * @return TODO: describe
     */
    @Override
    public int capacity() {
        return capacity;
    }

    /**
     * TODO: document {@code getFloatBuffer}.
     *
     * @return TODO: describe
     */
    public Float32Array getFloatBuffer() {
        return fb;
    }

    /**
     * TODO: document {@code get}.
     *
     * @param i TODO: describe
     * @return TODO: describe
     */
    @Override
    public Float get(int i) {
        return fb.get(i);
    }
}
