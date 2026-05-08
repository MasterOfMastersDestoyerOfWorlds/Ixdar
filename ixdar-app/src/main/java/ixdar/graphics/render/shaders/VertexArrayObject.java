
package ixdar.graphics.render.shaders;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class VertexArrayObject {

    private final int id;

    /**
     * Allocate a new GL vertex array object name.
     */
    public VertexArrayObject() {
        GL gl = Platforms.gl();
        id = gl.genVertexArrays();
    }

    /**
     * Bind this VAO as the current vertex array.
     */
    public void bind() {
        GL gl = Platforms.gl();
        gl.bindVertexArray(id);
    }

    /**
     * Delete this VAO from GL state.
     */
    public void delete() {
        GL gl = Platforms.gl();
        gl.deleteVertexArrays(id);
    }

    /**
     * The GL name for this VAO.
     *
     * @return GL-assigned vertex array id
     */
    public int getID() {
        return id;
    }

}
