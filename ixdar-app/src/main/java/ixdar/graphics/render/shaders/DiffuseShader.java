package ixdar.graphics.render.shaders;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class DiffuseShader extends ShaderProgram {
    public static final int NUM_8 = 8;
    public static final int NUM_3 = 3;
    public static final int NUM_6 = 6;

    /**
     * Build the diffuse-lit mesh shader (shader.vs/shader.fs) with a
     * position+normal+uv (8-float) vertex layout.
     *
     * @param vao vertex array object to bind attributes on
     * @param vbo vertex buffer object backing the geometry
     * @throws UnsupportedEncodingException on shader source encoding error
     * @throws IOException on shader source I/O error
     */
    public DiffuseShader(VertexArrayObject vao,
            VertexBufferObject vbo) throws UnsupportedEncodingException, IOException {
        super("shader.vs", "shader.fs", vao, vbo, NUM_8, false);
    }

    /**
     * Bind the VAO/VBO and configure position (vec3), normal (vec3) and uv
     * (vec2) attribute pointers at the standard locations.
     */
    @Override
    public void init() {
        super.init();
        GL gl = Platforms.gl();
        vao.bind();
        vbo.bind(gl.ARRAY_BUFFER());

        gl.vertexAttribPointer(0, NUM_3, gl.FLOAT(), false, NUM_8 * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(1, NUM_3, gl.FLOAT(), false, NUM_8 * Float.BYTES, NUM_3 * Float.BYTES);
        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(2, 2, gl.FLOAT(), false, NUM_8 * Float.BYTES, NUM_6 * Float.BYTES);
        gl.enableVertexAttribArray(2);

    }

    /**
     * No-op: the 3D scene supplies its own per-frame projection from
     * {@code Camera3D}.
     *
     * @param framebufferWidth viewport width in pixels (unused)
     * @param framebufferHeight viewport height in pixels (unused)
     * @param scale DPI scale hint (unused)
     */
    @Override
    public void updateProjectionMatrix(int framebufferWidth, int framebufferHeight, float scale) {

    }

}
