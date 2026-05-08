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
     * TODO: document {@code DiffuseShader}.
     *
     * @param vao TODO: describe
     * @param vbo TODO: describe
     * @throws UnsupportedEncodingException TODO: describe
     * @throws IOException TODO: describe
     */
    public DiffuseShader(VertexArrayObject vao,
            VertexBufferObject vbo) throws UnsupportedEncodingException, IOException {
        super("shader.vs", "shader.fs", vao, vbo, NUM_8, false);
    }

    /**
     * TODO: document {@code init}.
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
     * TODO: document {@code updateProjectionMatrix}.
     *
     * @param framebufferWidth TODO: describe
     * @param framebufferHeight TODO: describe
     * @param scale TODO: describe
     */
    @Override
    public void updateProjectionMatrix(int framebufferWidth, int framebufferHeight, float scale) {

    }

}
