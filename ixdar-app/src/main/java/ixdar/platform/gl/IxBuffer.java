package ixdar.platform.gl;


/**
 * Cross-platform float buffer abstraction. Backed by an LWJGL {@link java.nio.FloatBuffer}
 * on desktop / headless and a JS {@code Float32Array} on web — semantics mirror NIO buffers
 * (position / limit / flip).
 */
public interface IxBuffer {

    /**
     * Switch from writing to reading: set limit to current position and rewind position to 0
     * (NIO buffer convention).
     */
    void flip();

    /**
     * Reset position to 0 and limit to capacity, leaving the underlying data alone.
     */
    void clear();

    /**
     * @return number of floats between position and limit
     */
    int remaining();

    /**
     * Write {@code x1} at the current position and advance.
     *
     * @param x1 float to append
     * @return this buffer for chaining
     */
    IxBuffer put(float x1);

    /**
     * @return the maximum number of floats this buffer can hold
     */
    int capacity();

    /**
     * Random-access read by absolute index (does not affect position).
     *
     * @param i absolute index, {@code 0 <= i < capacity()}
     * @return the float stored at {@code i}
     */
    Float get(int i);
}
