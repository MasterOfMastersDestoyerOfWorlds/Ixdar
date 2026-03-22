package ixdar.graphics.render.shaders;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.joml.Matrix4f;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class ColorShader extends ShaderProgram {

    public ColorShader(String vertexShaderLocation, String fragmentShaderLocation)
            throws UnsupportedEncodingException, IOException {
        super(vertexShaderLocation, fragmentShaderLocation, new VertexArrayObject(), new VertexBufferObject(),
                7, true);
    }

    @Override
    public void init() {
        super.init();
        GL gl = Platforms.gl();
        /* Specify Vertex Pointer */
        int posAttrib = getAttributeLocation("position");
        gl.enableVertexAttribArray(posAttrib);
        gl.vertexAttribPointer(posAttrib, 3, gl.FLOAT(), false, 7 * Float.BYTES, 0);

        /* Specify Color Pointer */
        int colAttrib = getAttributeLocation("color");
        gl.enableVertexAttribArray(colAttrib);
        gl.vertexAttribPointer(colAttrib, 4, gl.FLOAT(), false, 7 * Float.BYTES, 3 * Float.BYTES);

        use();
        bindFragmentDataLocation(0, "fragColor");

        /* Set model matrix to identity matrix */
        Matrix4f model = new Matrix4f();
        setMat4("model", model);

        /* Set view matrix to identity matrix */
        Matrix4f view = new Matrix4f();
        setMat4("view", view);

        updateProjectionMatrix(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(), 1f);
    }

    @Override
    public void updateProjectionMatrix(int framebufferWidth, int framebufferHeight, float scale) {
        use();
        Matrix4f projection = new Matrix4f();
        float left = 0f, right = framebufferWidth, bottom = 0f, top = framebufferHeight;
        float near = ORTHO_NEAR, far = ORTHO_FAR;
        projection.m00(2f / (right - left));
        projection.m11(2f / (top - bottom));
        projection.m22(-2f / (far - near));
        projection.m33(1f);
        projection.m30(-(right + left) / (right - left));
        projection.m31(-(top + bottom) / (top - bottom));
        projection.m32(-(far + near) / (far - near));
        setMat4("projection", projection);
    }

}
