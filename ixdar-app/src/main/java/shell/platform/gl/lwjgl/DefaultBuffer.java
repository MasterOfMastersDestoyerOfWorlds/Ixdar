package shell.platform.gl.lwjgl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import shell.platform.gl.IxBuffer;

public class DefaultBuffer implements IxBuffer {

    FloatBuffer fb;

    public DefaultBuffer(int capacity) {
        ByteBuffer bb = ByteBuffer.allocateDirect(capacity * Float.BYTES).order(ByteOrder.nativeOrder());
        fb = bb.asFloatBuffer();
    }

    @Override
    public void flip() {
        fb.flip();
    }

    @Override
    public void clear() {
        fb.clear();
    }

    @Override
    public int remaining() {
        return fb.remaining();
    }

    @Override
    public IxBuffer put(float x1) {
        fb.put(x1);
        return this;
    }

    @Override
    public int capacity() {
        return fb.capacity();
    }

    public FloatBuffer getFloatBuffer() {
        return fb;
    }
}
