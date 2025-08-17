package shell.platform.buffers;

import java.nio.FloatBuffer;

public interface Buffers {
    FloatBuffer allocateFloats(int capacity);
}
