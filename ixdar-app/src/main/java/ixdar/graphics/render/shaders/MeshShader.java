package ixdar.graphics.render.shaders;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class MeshShader extends ShaderProgram {
    public static final int NUM_8 = 8;
    public static final int NUM_3 = 3;
    public static final int NUM_6 = 6;

    /**
     * TODO: document {@code MeshShader}.
     *
     * @param vertexShaderLocation TODO: describe
     * @param fragmentShaderLocation TODO: describe
     * @throws UnsupportedEncodingException TODO: describe
     * @throws IOException TODO: describe
     */
    public MeshShader(String vertexShaderLocation, String fragmentShaderLocation)
            throws UnsupportedEncodingException, IOException {
        super(vertexShaderLocation, fragmentShaderLocation, new VertexArrayObject(), new VertexBufferObject(),
                NUM_8, true);
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

        use();
        setInt("albedoTex", 0);
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
        // The model scene sets projection per-frame from Camera3D.
    }
}
