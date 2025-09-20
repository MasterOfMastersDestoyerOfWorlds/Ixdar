package shell.render.shaders;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import shell.platform.Platforms;
import shell.platform.gl.GL;

public class DiffuseShader extends ShaderProgram {

    public DiffuseShader(VertexArrayObject vao,
            VertexBufferObject vbo) throws UnsupportedEncodingException, IOException {
        super("shader.vs", "shader.fs", vao, vbo, false);
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

    }

    @Override
    public void updateProjectionMatrix(int framebufferWidth, int framebufferHeight, float scale) {

    }

}
