package ixdar.platform.gl.headless;

import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;

import ixdar.platform.gl.IxBuffer;

/**
 * Simple float buffer for headless testing.
 * Wraps a float[] for IxBuffer interface and provides a FloatBuffer view for LWJGL GL calls.
 */
public class HeadlessBuffer implements IxBuffer {

    private float[] data;
    private int position;
    private int limit;

    public HeadlessBuffer(int capacity) {
        this.data = new float[capacity];
        this.position = 0;
        this.limit = capacity;
    }

    @Override
    public void flip() {
        limit = position;
        position = 0;
    }

    @Override
    public void clear() {
        position = 0;
        limit = data.length;
    }

    @Override
    public int remaining() {
        return limit - position;
    }

    @Override
    public IxBuffer put(float value) {
        if (position < data.length) {
            data[position++] = value;
        }
        return this;
    }

    @Override
    public int capacity() {
        return data.length;
    }

    @Override
    public Float get(int i) {
        if (i >= 0 && i < data.length) {
            return data[i];
        }
        return 0f;
    }

    /**
     * Returns a FloatBuffer view of the data (position 0 to limit).
     * Creates a new direct buffer each call — cache externally if needed per-frame.
     */
    public FloatBuffer getBuffer() {
        int len = limit - 0;  // always from start
        FloatBuffer buf = BufferUtils.createFloatBuffer(len);
        buf.put(data, 0, len).flip();
        return buf;
    }
}
