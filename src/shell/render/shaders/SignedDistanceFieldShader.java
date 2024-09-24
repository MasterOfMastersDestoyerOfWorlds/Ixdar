package shell.render.shaders;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL20.*;

import org.joml.Matrix4f;

import shell.render.VertexArrayObject;
import shell.render.VertexBufferObject;

public class SignedDistanceFieldShader extends ShaderProgram {

    public SignedDistanceFieldShader(int framebufferWidth, int framebufferHeight) {
        super("font.vs", "sdf.fs", new VertexArrayObject(), new VertexBufferObject(), true);
        /* Specify Vertex Pointer */
        int posAttrib = getAttributeLocation("position");
        glEnableVertexAttribArray(posAttrib);
        glVertexAttribPointer(posAttrib, 3, GL_FLOAT, false, 9 * Float.BYTES, 0);

        /* Specify Color Pointer */
        int colAttrib = getAttributeLocation("color");
        glEnableVertexAttribArray(colAttrib);
        glVertexAttribPointer(colAttrib, 4, GL_FLOAT, false, 9 * Float.BYTES, 3 * Float.BYTES);
        /* Specify Color Pointer */
        int texCoordAttrib = getAttributeLocation("texCoord");
        glEnableVertexAttribArray(texCoordAttrib);
        glVertexAttribPointer(texCoordAttrib, 2, GL_FLOAT, false, 9 * Float.BYTES, 7 * Float.BYTES);

        use();
        bindFragmentDataLocation(0, "fragColor");

        /* Set texture uniform */
        setInt("texImage", 0);

        /* Set model matrix to identity matrix */
        Matrix4f model = new Matrix4f();
        setMat4("model", model);

        /* Set view matrix to identity matrix */
        Matrix4f view = new Matrix4f();
        setMat4("view", view);

        updateProjectionMatrix(framebufferWidth, framebufferHeight);
    }

    @Override
    public void updateProjectionMatrix(int framebufferWidth, int framebufferHeight) {
        use();
        Matrix4f projection = new Matrix4f().ortho(0f, framebufferWidth, 0f, framebufferHeight, -1.0f, 1.0f);
        setMat4("projection", projection);
    }

}
