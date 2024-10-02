package shell.render.shaders;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL20.*;

public class LightShader extends ShaderProgram {

    public LightShader(VertexArrayObject vao,
            VertexBufferObject vbo) {
        super("light_shader.vs", "light_shader.fs", vao, vbo, false);
        vao.bind();
        vbo.bind(GL_ARRAY_BUFFER);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 8 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
    }

    @Override
    public void updateProjectionMatrix(int framebufferWidth, int framebufferHeight) {
    }

}
