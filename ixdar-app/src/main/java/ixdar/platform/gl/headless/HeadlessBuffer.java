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

    /**
     * Allocate a heap-backed buffer of {@code capacity} floats; position starts at 0, limit at
     * {@code capacity}.
     *
     * @param capacity number of floats
     */
    public HeadlessBuffer(int capacity) {
        this.data = new float[capacity];
        this.position = 0;
        this.limit = capacity;
    }

    /**
     * Set limit to the current write position and rewind position to 0.
     */
    @Override
    public void flip() {
        limit = position;
        position = 0;
    }

    /**
     * Reset position to 0 and limit to the full capacity.
     */
    @Override
    public void clear() {
        position = 0;
        limit = data.length;
    }

    /**
     * {@inheritDoc}.
     *
     * @return floats between position and limit
     */
    @Override
    public int remaining() {
        return limit - position;
    }

    /**
     * Write {@code value} at the current position and advance; silently discards writes past
     * the end of the backing array.
     *
     * @param value float to append
     * @return this buffer
     */
    @Override
    public IxBuffer put(float value) {
        if (position < data.length) {
            data[position++] = value;
        }
        return this;
    }

    /**
     * {@inheritDoc}.
     *
     * @return backing array length
     */
    @Override
    public int capacity() {
        return data.length;
    }

    /**
     * Absolute read; out-of-range indices return {@code 0f} rather than throwing.
     *
     * @param i absolute index
     * @return value at {@code i}, or {@code 0f} if out of range
     */
    @Override
    public Float get(int i) {
        if (i >= 0 && i < data.length) {
            return data[i];
        }
        return 0f;
    }

    /**
     * Returns a FloatBuffer view of the data (position 0 to limit).
     * Creates a new direct buffer each call; cache externally if needed per-frame.
     *
     * @return new direct {@link FloatBuffer} containing {@code data[0..limit)}, flipped for reading
     */
    public FloatBuffer getBuffer() {
        int len = limit - 0;  // always from start
        FloatBuffer buf = BufferUtils.createFloatBuffer(len);
        buf.put(data, 0, len).flip();
        return buf;
    }
}
