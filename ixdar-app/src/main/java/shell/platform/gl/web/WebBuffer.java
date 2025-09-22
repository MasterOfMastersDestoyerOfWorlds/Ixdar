package shell.platform.gl.web;

import org.teavm.jso.typedarrays.Float32Array;

import shell.platform.gl.IxBuffer;

public class WebBuffer implements IxBuffer {

    Float32Array fb;
    private int capacity;
    private int remaining;

    public WebBuffer(int capacity) {
        this.capacity = capacity;
        fb = Float32Array.create(capacity);
        remaining = capacity;
    }

    @Override
    public void flip() {
    }

    @Override
    public void clear() {
        remaining = capacity;
    }

    @Override
    public int remaining() {
        return remaining;
    }

    @Override
    public IxBuffer put(float x1) {

        fb.set(capacity-remaining, x1);
        remaining--;
        return this;
    }

    @Override
    public int capacity() {
        return capacity;
    }

    public Float32Array getFloatBuffer() {
        return fb;
    }
}
