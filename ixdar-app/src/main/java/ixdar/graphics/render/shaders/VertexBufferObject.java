
package ixdar.graphics.render.shaders;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.gl.IxBuffer;

public class VertexBufferObject {

    private final int id;

    /**
     * TODO: document {@code VertexBufferObject}.
     */
    public VertexBufferObject() {
        GL gl = Platforms.gl();
        id = gl.genBuffers();
    }

    /**
     * TODO: document {@code bind}.
     *
     * @param target TODO: describe
     */
    public void bind(int target) {
        GL gl = Platforms.gl();
        gl.bindBuffer(target, id);
    }

    /**
     * TODO: document {@code uploadData}.
     *
     * @param target TODO: describe
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    public void uploadData(int target, IxBuffer data, int usage) {
        GL gl = Platforms.gl();
        gl.bufferData(target, data, usage);
    }

    /**
     * TODO: document {@code uploadData}.
     *
     * @param target TODO: describe
     * @param data TODO: describe
     * @param usage TODO: describe
     */
    public void uploadData(int target, float[] data, int usage) {
        GL gl = Platforms.gl();
        gl.bufferData(target, data, usage);
    }

    /**
     * TODO: document {@code uploadData}.
     *
     * @param target TODO: describe
     * @param size TODO: describe
     * @param usage TODO: describe
     */
    public void uploadData(int target, long size, int usage) {
        GL gl = Platforms.gl();
        gl.bufferData(target, size, usage);
    }

    /**
     * TODO: document {@code uploadSubData}.
     *
     * @param target TODO: describe
     * @param offset TODO: describe
     * @param data TODO: describe
     */
    public void uploadSubData(int target, long offset, IxBuffer data) {
        GL gl = Platforms.gl();
        gl.bufferSubData(target, offset, data);
    }

    /**
     * TODO: document {@code delete}.
     */
    public void delete() {
        GL gl = Platforms.gl();
        gl.deleteBuffers(id);
    }

    /**
     * TODO: document {@code getID}.
     *
     * @return TODO: describe
     */
    public int getID() {
        return id;
    }

}
