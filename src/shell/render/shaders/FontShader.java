package shell.render.shaders;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL20.*;

import org.joml.Matrix4f;

import shell.render.VertexArrayObject;
import shell.render.VertexBufferObject;

public class FontShader extends ShaderProgram {

    public FontShader(String vertexShaderLocation, String fragmentShaderLocation, VertexArrayObject vao,
            VertexBufferObject vbo, int framebufferWidth, int framebufferHeight) {
        super(vertexShaderLocation, fragmentShaderLocation, vao, vbo);
        /* Specify Vertex Pointer */
        int posAttrib = getAttributeLocation("position");
        glEnableVertexAttribArray(posAttrib);
        glVertexAttribPointer(posAttrib, 2, GL_FLOAT, false, 8 * Float.BYTES, 0);

        /* Specify Color Pointer */
        int colAttrib = getAttributeLocation("color");
        glEnableVertexAttribArray(colAttrib);
        glVertexAttribPointer(colAttrib, 4, GL_FLOAT, false, 8 * Float.BYTES, 2 * Float.BYTES);

        /* Specify Texture Pointer */
        int texAttrib = getAttributeLocation("texcoord");
        glEnableVertexAttribArray(texAttrib);
        glVertexAttribPointer(texAttrib, 2, GL_FLOAT, false, 8 * Float.BYTES, 6 * Float.BYTES);

        bindFragmentDataLocation(0, "fragColor");
        use();

        /* Set texture uniform */
        setInt("texImage", 0);

        /* Set model matrix to identity matrix */
        Matrix4f model = new Matrix4f();
        setMat4("model", model);

        /* Set view matrix to identity matrix */
        Matrix4f view = new Matrix4f();
        setMat4("view", view);

        /* Set projection matrix to an orthographic projection */
        Matrix4f projection = new Matrix4f().ortho(0f, framebufferWidth, 0f, framebufferHeight, -1f, 1f);
        setMat4("projection", projection);
    }

}
