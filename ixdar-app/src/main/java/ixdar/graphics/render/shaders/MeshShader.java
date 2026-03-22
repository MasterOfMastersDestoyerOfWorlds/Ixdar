package ixdar.graphics.render.shaders;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class MeshShader extends ShaderProgram {

    public MeshShader(String vertexShaderLocation, String fragmentShaderLocation)
            throws UnsupportedEncodingException, IOException {
        super(vertexShaderLocation, fragmentShaderLocation, new VertexArrayObject(), new VertexBufferObject(),
                8, true);
    }

    @Override
    public void init() {
        super.init();
        GL gl = Platforms.gl();
        vao.bind();
        vbo.bind(gl.ARRAY_BUFFER());

        gl.vertexAttribPointer(0, 3, gl.FLOAT(), false, 8 * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(1, 3, gl.FLOAT(), false, 8 * Float.BYTES, 3 * Float.BYTES);
        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(2, 2, gl.FLOAT(), false, 8 * Float.BYTES, 6 * Float.BYTES);
        gl.enableVertexAttribArray(2);

        use();
        setInt("albedoTex", 0);
    }

    @Override
    public void updateProjectionMatrix(int framebufferWidth, int framebufferHeight, float scale) {
        // The model scene sets projection per-frame from Camera3D.
    }
}
