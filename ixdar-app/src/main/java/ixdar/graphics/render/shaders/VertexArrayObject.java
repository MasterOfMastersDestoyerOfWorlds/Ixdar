
package ixdar.graphics.render.shaders;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class VertexArrayObject {

    private final int id;

    /**
     * TODO: document {@code VertexArrayObject}.
     */
    public VertexArrayObject() {
        GL gl = Platforms.gl();
        id = gl.genVertexArrays();
    }

    /**
     * TODO: document {@code bind}.
     */
    public void bind() {
        GL gl = Platforms.gl();
        gl.bindVertexArray(id);
    }

    /**
     * TODO: document {@code delete}.
     */
    public void delete() {
        GL gl = Platforms.gl();
        gl.deleteVertexArrays(id);
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
