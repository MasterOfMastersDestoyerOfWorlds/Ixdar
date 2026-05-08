package ixdar.graphics.render.shaders;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class LightShader extends ShaderProgram {
    public static final int NUM_8 = 8;
    public static final int NUM_3 = 3;


    /**
     * TODO: document {@code LightShader}.
     *
     * @param vao TODO: describe
     * @param vbo TODO: describe
     * @throws UnsupportedEncodingException TODO: describe
     * @throws IOException TODO: describe
     */
    public LightShader(VertexArrayObject vao,
            VertexBufferObject vbo) throws UnsupportedEncodingException, IOException {
        super("light_shader.vs", "light_shader.fs", vao, vbo, NUM_8, false);
    }

    /**
     * TODO: document {@code init}.
     */
    @Override
    public void init() {
        GL gl = Platforms.gl();
        super.init();
        vao.bind();
        vbo.bind(gl.ARRAY_BUFFER());
        gl.vertexAttribPointer(0, NUM_3, gl.FLOAT(), false, NUM_8 * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
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
