package shell.render.shaders;

import static org.lwjgl.opengl.GL20.*;

import shell.render.VertexArrayObject;
import shell.render.VertexBufferObject;

public class DiffuseShader extends ShaderProgram {

    public DiffuseShader(String vertexShaderLocation, String fragmentShaderLocation, VertexArrayObject vao,
            VertexBufferObject vbo) {
        super(vertexShaderLocation, fragmentShaderLocation, vao, vbo);

        vao.bind();
        vbo.bind(GL_ARRAY_BUFFER);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 8 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 8 * Float.BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, 8 * Float.BYTES, 6 * Float.BYTES);
        glEnableVertexAttribArray(2);

    }

}
