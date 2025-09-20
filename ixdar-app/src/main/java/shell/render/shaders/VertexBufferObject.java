
package shell.render.shaders;

import java.nio.FloatBuffer;

import shell.platform.Platforms;
import shell.platform.gl.GL;

public class VertexBufferObject {

    private final int id;

    public VertexBufferObject() {
        GL gl = Platforms.gl();
        id = gl.genBuffers();
    }

    public void bind(int target) {
        GL gl = Platforms.gl();
        gl.bindBuffer(target, id);
    }

    public void uploadData(int target, FloatBuffer data, int usage) {
        GL gl = Platforms.gl();
        gl.bufferData(target, data, usage);
    }

    public void uploadData(int target, float[] data, int usage) {
        GL gl = Platforms.gl();
        gl.bufferData(target, data, usage);
    }

    public void uploadData(int target, long size, int usage) {
        GL gl = Platforms.gl();
        gl.bufferData(target, size, usage);
    }

    public void uploadSubData(int target, long offset, FloatBuffer data) {
        GL gl = Platforms.gl();
        gl.bufferSubData(target, offset, data);
    }

    public void delete() {
        GL gl = Platforms.gl();
        gl.deleteBuffers(id);
    }

    public int getID() {
        return id;
    }

}
