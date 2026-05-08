
package ixdar.graphics.render.shaders;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.IxBuffer;

public class VertexBufferObject {

    private final int id;

    /**
     * Allocate a new GL buffer name.
     */
    public VertexBufferObject() {
        GL gl = Platforms.gl();
        id = gl.genBuffers();
    }

    /**
     * Bind this VBO to a GL buffer target.
     *
     * @param target buffer binding point (e.g. {@code GL_ARRAY_BUFFER})
     */
    public void bind(int target) {
        GL gl = Platforms.gl();
        gl.bindBuffer(target, id);
    }

    /**
     * Replace the buffer's contents from a direct buffer.
     *
     * @param target buffer binding point
     * @param data source vertex data (already flipped)
     * @param usage GL usage hint (e.g. {@code GL_STATIC_DRAW})
     */
    public void uploadData(int target, IxBuffer data, int usage) {
        GL gl = Platforms.gl();
        gl.bufferData(target, data, usage);
    }

    /**
     * Replace the buffer's contents from a Java float array.
     *
     * @param target buffer binding point
     * @param data source vertex data
     * @param usage GL usage hint
     */
    public void uploadData(int target, float[] data, int usage) {
        GL gl = Platforms.gl();
        gl.bufferData(target, data, usage);
    }

    /**
     * Allocate the buffer with uninitialized contents of the given size.
     *
     * @param target buffer binding point
     * @param size byte size to reserve
     * @param usage GL usage hint
     */
    public void uploadData(int target, long size, int usage) {
        GL gl = Platforms.gl();
        gl.bufferData(target, size, usage);
    }

    /**
     * Update a sub-range of the buffer at a byte offset.
     *
     * @param target buffer binding point
     * @param offset byte offset into the buffer
     * @param data source vertex data (already flipped)
     */
    public void uploadSubData(int target, long offset, IxBuffer data) {
        GL gl = Platforms.gl();
        gl.bufferSubData(target, offset, data);
    }

    /**
     * Delete this VBO from GL state.
     */
    public void delete() {
        GL gl = Platforms.gl();
        gl.deleteBuffers(id);
    }

    /**
     * The GL name for this VBO.
     *
     * @return GL-assigned buffer id
     */
    public int getID() {
        return id;
    }

}
