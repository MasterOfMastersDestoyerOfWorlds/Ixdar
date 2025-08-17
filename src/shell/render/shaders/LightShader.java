package shell.render.shaders;

import shell.platform.Platforms;
import shell.platform.gl.GL;

public class LightShader extends ShaderProgram {

    
    private static GL gl = Platforms.gl();

    public LightShader(VertexArrayObject vao,
            VertexBufferObject vbo) {
        super("light_shader.vs", "light_shader.fs", vao, vbo, false);
    }

    @Override
    public void init() {
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
