
package shell.render.shaders;

import java.nio.FloatBuffer;

import shell.platform.Platforms;
import shell.platform.gl.GL;

public class VertexBufferObject {

    private final int id;
    private static GL gl = Platforms.gl();

    public VertexBufferObject() {
        id = gl.genBuffers();
    }

    public void bind(int target) {
        gl.bindBuffer(target, id);
    }

    public void uploadData(int target, FloatBuffer data, int usage) {
        gl.bufferData(target, data, usage);
    }

    public void uploadData(int target, float[] data, int usage) {
        gl.bufferData(target, data, usage);
    }

    public void uploadData(int target, long size, int usage) {
        gl.bufferData(target, size, usage);
    }

    public void uploadSubData(int target, long offset, FloatBuffer data) {
        gl.bufferSubData(target, offset, data);
    }

    public void delete() {
        gl.deleteBuffers(id);
    }

    public int getID() {
        return id;
    }

}
