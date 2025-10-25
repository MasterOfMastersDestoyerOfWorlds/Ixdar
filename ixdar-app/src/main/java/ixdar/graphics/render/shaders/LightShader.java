package ixdar.graphics.render.shaders;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class LightShader extends ShaderProgram {


    public LightShader(VertexArrayObject vao,
            VertexBufferObject vbo) throws UnsupportedEncodingException, IOException {
        super("light_shader.vs", "light_shader.fs", vao, vbo, 8, false);
    }

    @Override
    public void init() {
        GL gl = Platforms.gl();
        super.init();
        vao.bind();
        vbo.bind(gl.ARRAY_BUFFER());
        gl.vertexAttribPointer(0, 3, gl.FLOAT(), false, 8 * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
    }

    @Override
    public void updateProjectionMatrix(int framebufferWidth, int framebufferHeight, float scale) {
    }

}
