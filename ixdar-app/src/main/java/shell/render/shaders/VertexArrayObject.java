
package shell.render.shaders;

import shell.platform.Platforms;
import shell.platform.gl.GL;

public class VertexArrayObject {

    private final int id;

    public VertexArrayObject() {
        GL gl = Platforms.gl();
        id = gl.genVertexArrays();
    }

    public void bind() {
        GL gl = Platforms.gl();
        gl.bindVertexArray(id);
    }

    public void delete() {
        GL gl = Platforms.gl();
        gl.deleteVertexArrays(id);
    }

    public int getID() {
        return id;
    }

}
