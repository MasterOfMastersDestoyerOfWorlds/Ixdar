
package ixdar.graphics.render.shaders;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

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
