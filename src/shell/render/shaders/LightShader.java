package shell.render.shaders;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL20.*;

import shell.render.VertexArrayObject;
import shell.render.VertexBufferObject;

public class LightShader extends ShaderProgram {

    public LightShader(String vertexShaderLocation, String fragmentShaderLocation, VertexArrayObject vao,
            VertexBufferObject vbo) {
        super(vertexShaderLocation, fragmentShaderLocation, vao, vbo);
        vao.bind();
        vbo.bind(GL_ARRAY_BUFFER);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 8 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
    }

}
