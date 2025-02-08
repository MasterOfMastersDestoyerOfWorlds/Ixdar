package shell.render.shaders;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL20.*;

public class DiffuseShader extends ShaderProgram {

    public DiffuseShader(VertexArrayObject vao,
            VertexBufferObject vbo) {
        super("shader.vs", "shader.fs", vao, vbo, false);
    }

    @Override
    public void init() {
        super.init();
        vao.bind();
        vbo.bind(GL_ARRAY_BUFFER);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 8 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 8 * Float.BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, 8 * Float.BYTES, 6 * Float.BYTES);
        glEnableVertexAttribArray(2);

    }

    @Override
    public void updateProjectionMatrix(int framebufferWidth, int framebufferHeight, float scale) {

    }

}
