package ixdar.platform.gl.lwjgl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import ixdar.platform.gl.IxBuffer;

public class DefaultBuffer implements IxBuffer {

    FloatBuffer fb;

    /**
     * TODO: document {@code DefaultBuffer}.
     *
     * @param capacity TODO: describe
     */
    public DefaultBuffer(int capacity) {
        ByteBuffer bb = ByteBuffer.allocateDirect(capacity * Float.BYTES).order(ByteOrder.nativeOrder());
        fb = bb.asFloatBuffer();
    }

    /**
     * TODO: document {@code flip}.
     */
    @Override
    public void flip() {
        fb.flip();
    }

    /**
     * TODO: document {@code clear}.
     */
    @Override
    public void clear() {
        fb.clear();
    }

    /**
     * TODO: document {@code remaining}.
     *
     * @return TODO: describe
     */
    @Override
    public int remaining() {
        return fb.remaining();
    }

    /**
     * TODO: document {@code put}.
     *
     * @param x1 TODO: describe
     * @return TODO: describe
     */
    @Override
    public IxBuffer put(float x1) {
        fb.put(x1);
        return this;
    }

    /**
     * TODO: document {@code capacity}.
     *
     * @return TODO: describe
     */
    @Override
    public int capacity() {
        return fb.capacity();
    }

    /**
     * TODO: document {@code getFloatBuffer}.
     *
     * @return TODO: describe
     */
    public FloatBuffer getFloatBuffer() {
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
