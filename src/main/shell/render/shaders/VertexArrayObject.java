
package shell.render.shaders;

import shell.platform.Platforms;
import shell.platform.gl.GL;

public class VertexArrayObject {

    private final int id;
    private static GL gl = Platforms.gl();

    public VertexArrayObject() {
        id = gl.genVertexArrays();
    }

    public void bind() {
        gl.bindVertexArray(id);
    }

    public void delete() {
        gl.deleteVertexArrays(id);
    }

    public int getID() {
        return id;
    }

}
