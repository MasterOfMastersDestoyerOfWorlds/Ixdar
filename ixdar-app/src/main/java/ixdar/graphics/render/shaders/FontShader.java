package ixdar.graphics.render.shaders;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.joml.Matrix4f;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class FontShader extends ShaderProgram {
    public static final int NUM_9 = 9;
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final int NUM_7 = 7;
    public static final float NUM_1 = 1f;
    public static final float NUM_0 = 0f;
    public static final float NUM_2 = 2f;

    /**
     * TODO: document {@code FontShader}.
     *
     * @param framebufferWidth TODO: describe
     * @param framebufferHeight TODO: describe
     * @throws UnsupportedEncodingException TODO: describe
     * @throws IOException TODO: describe
     */
    public FontShader(int framebufferWidth, int framebufferHeight) throws UnsupportedEncodingException, IOException {
        super("font.vs", "font.fs", new VertexArrayObject(), new VertexBufferObject(), NUM_9, true);
    }

    /**
     * TODO: document {@code init}.
     */
    @Override
    public void init() {
        super.init();
        GL gl = Platforms.gl();
        /* Specify Vertex Pointer */
        int posAttrib = getAttributeLocation("position");
        gl.enableVertexAttribArray(posAttrib);
        gl.vertexAttribPointer(posAttrib, NUM_3, gl.FLOAT(), false, NUM_9 * Float.BYTES, 0);

        /* Specify Color Pointer */
        int colAttrib = getAttributeLocation("color");
        gl.enableVertexAttribArray(colAttrib);
        gl.vertexAttribPointer(colAttrib, NUM_4, gl.FLOAT(), false, NUM_9 * Float.BYTES, NUM_3 * Float.BYTES);
        /* Specify Color Pointer */
        int texCoordAttrib = getAttributeLocation("texCoord");
        gl.enableVertexAttribArray(texCoordAttrib);
        gl.vertexAttribPointer(texCoordAttrib, 2, gl.FLOAT(), false, NUM_9 * Float.BYTES, NUM_7 * Float.BYTES);

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

        updateProjectionMatrix(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(), NUM_1);
    }

    /**
     * TODO: document {@code updateProjectionMatrix}.
     *
     * @param framebufferWidth TODO: describe
     * @param framebufferHeight TODO: describe
     * @param scale TODO: describe
     */
    @Override
    public void updateProjectionMatrix(int framebufferWidth, int framebufferHeight, float scale) {
        use();
        Matrix4f projection = new Matrix4f();
        // Build ortho without triggering JOML Unsafe paths
        float left = NUM_0, right = framebufferWidth, bottom = NUM_0, top = framebufferHeight;
        float near = ORTHO_NEAR, far = ORTHO_FAR;
        projection.m00(NUM_2 / (right - left));
        projection.m11(NUM_2 / (top - bottom));
        projection.m22(-NUM_2 / (far - near));
        projection.m33(NUM_1);
        projection.m30(-(right + left) / (right - left));
        projection.m31(-(top + bottom) / (top - bottom));
        projection.m32(-(far + near) / (far - near));
        setMat4("projection", projection);
    }

}
