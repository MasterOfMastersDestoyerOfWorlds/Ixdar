package ixdar.platform.gl.lwjgl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import ixdar.platform.gl.IxBuffer;

public class DefaultBuffer implements IxBuffer {

    FloatBuffer fb;

    /**
     * Allocate a direct, native-byte-order {@link FloatBuffer} of {@code capacity} floats —
     * the layout LWJGL GL calls expect.
     *
     * @param capacity number of floats
     */
    public DefaultBuffer(int capacity) {
        ByteBuffer bb = ByteBuffer.allocateDirect(capacity * Float.BYTES).order(ByteOrder.nativeOrder());
        fb = bb.asFloatBuffer();
    }

    /**
     * Delegates to {@link FloatBuffer#flip()}.
     */
    @Override
    public void flip() {
        fb.flip();
    }

    /**
     * Delegates to {@link FloatBuffer#clear()}.
     */
    @Override
    public void clear() {
        fb.clear();
    }

    /**
     * {@inheritDoc}.
     *
     * @return {@link FloatBuffer#remaining()}
     */
    @Override
    public int remaining() {
        return fb.remaining();
    }

    /**
     * Append a float at the current position.
     *
     * @param x1 value to write
     * @return this buffer
     */
    @Override
    public IxBuffer put(float x1) {
        fb.put(x1);
        return this;
    }

    /**
     * {@inheritDoc}.
     *
     * @return {@link FloatBuffer#capacity()}
     */
    @Override
    public int capacity() {
        return fb.capacity();
    }

    /**
     * Direct access to the underlying NIO buffer — pass to LWJGL GL calls.
     *
     * @return wrapped {@link FloatBuffer}
     */
    public FloatBuffer getFloatBuffer() {
        return fb;
    }

    /**
     * Absolute read.
     *
     * @param i index
     * @return {@link FloatBuffer#get(int)} at {@code i}
     */
    @Override
    public Float get(int i) {
        return fb.get(i);
    }
}
