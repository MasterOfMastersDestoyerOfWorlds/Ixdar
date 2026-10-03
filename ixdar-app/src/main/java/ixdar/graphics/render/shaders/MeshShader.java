package ixdar.graphics.render.shaders;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class MeshShader extends ShaderProgram {
    public static final int VERTEX_STRIDE_FLOATS = 8;
    public static final int UV_OFFSET_FLOATS = 6;

    /**
     * Build a textured mesh shader with a position+normal+uv (8-float)
     * vertex layout.
     *
     * @param vertexShaderLocation vertex GLSL resource path
     * @param fragmentShaderLocation fragment GLSL resource path
     * @throws UnsupportedEncodingException on shader source encoding error
     * @throws IOException on shader source I/O error
     */
    public MeshShader(String vertexShaderLocation, String fragmentShaderLocation)
            throws UnsupportedEncodingException, IOException {
        super(vertexShaderLocation, fragmentShaderLocation, new VertexArrayObject(), new VertexBufferObject(),
                VERTEX_STRIDE_FLOATS, true);
    }

    /**
     * Bind the VAO/VBO, configure position/normal/uv attributes, and bind
     * the {@code albedoTex} sampler to texture unit 0.
     */
    @Override
    public void init() {
        super.init();
        GL gl = Platforms.gl();
        vao.bind();
        vbo.bind(gl.ARRAY_BUFFER());

        gl.vertexAttribPointer(0, 3, gl.FLOAT(), false, VERTEX_STRIDE_FLOATS * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(1, 3, gl.FLOAT(), false, VERTEX_STRIDE_FLOATS * Float.BYTES, 3 * Float.BYTES);
        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(2, 2, gl.FLOAT(), false, VERTEX_STRIDE_FLOATS * Float.BYTES, UV_OFFSET_FLOATS * Float.BYTES);
        gl.enableVertexAttribArray(2);

        use();
        setInt("albedoTex", 0);
    }

    /**
     * No-op: the model scene sets projection per-frame from {@code Camera3D}.
     *
     * @param framebufferWidth viewport width in pixels (unused)
     * @param framebufferHeight viewport height in pixels (unused)
     * @param scale DPI scale hint (unused)
     */
    @Override
    public void updateProjectionMatrix(int framebufferWidth, int framebufferHeight, float scale) {
        // The model scene sets projection per-frame from Camera3D.
    }
}
