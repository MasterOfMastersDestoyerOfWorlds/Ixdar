package shell.platform.buffers;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class DefaultBuffers implements Buffers {
    @Override
    public FloatBuffer allocateFloats(int capacity) {
        ByteBuffer bb = ByteBuffer.allocateDirect(capacity * Float.BYTES).order(ByteOrder.nativeOrder());
        return bb.asFloatBuffer();
    }
}
