package ixdar.graphics.render.shaders;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class LightShader extends ShaderProgram {
    public static final int NUM_8 = 8;
    public static final int NUM_3 = 3;


    /**
     * Build the light-source shader (light_shader.vs/.fs) sharing the mesh
     * 8-float vertex layout but consuming only the position attribute.
     *
     * @param vao vertex array object to bind attributes on
     * @param vbo vertex buffer object backing the geometry
     * @throws UnsupportedEncodingException on shader source encoding error
     * @throws IOException on shader source I/O error
     */
    public LightShader(VertexArrayObject vao,
            VertexBufferObject vbo) throws UnsupportedEncodingException, IOException {
        super("light_shader.vs", "light_shader.fs", vao, vbo, NUM_8, false);
    }

    /**
     * Bind the VAO/VBO and enable only the position (vec3) attribute on
     * location 0; lighting math runs entirely in the fragment shader.
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
     * No-op: the 3D scene supplies the projection from {@code Camera3D}.
     *
     * @param framebufferWidth viewport width in pixels (unused)
     * @param framebufferHeight viewport height in pixels (unused)
     * @param scale DPI scale hint (unused)
     */
    @Override
    public void updateProjectionMatrix(int framebufferWidth, int framebufferHeight, float scale) {
    }

}
