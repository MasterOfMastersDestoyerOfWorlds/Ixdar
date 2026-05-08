package ixdar.graphics.render.shaders;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.joml.Matrix4f;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class SDFShader extends ShaderProgram {
    public static final int NUM_9 = 9;
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final int NUM_7 = 7;
    public static final float NUM_1 = 1f;
    public static final float NUM_0 = 0f;
    public static final float NUM_2 = 2f;

    /**
     * Build a signed-distance-field shader program with a 9-float vertex
     * layout (3 position + 4 color + 2 uv).
     *
     * @param vertexShaderLocation vertex GLSL resource path
     * @param fragmentShaderLocation fragment GLSL resource path
     * @throws UnsupportedEncodingException on shader source encoding error
     * @throws IOException on shader source I/O error
     */
    public SDFShader(String vertexShaderLocation, String fragmentShaderLocation)
            throws UnsupportedEncodingException, IOException {
        super(vertexShaderLocation, fragmentShaderLocation, new VertexArrayObject(), new VertexBufferObject(),
                NUM_9, true);
    }

    /**
     * Wire position/color/texCoord attributes, bind {@code fragColor} and
     * the {@code texImage} sampler (unit 0), set identity model/view
     * matrices, and update the orthographic projection.
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

        updateProjectionMatrix(Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight(), NUM_1);
    }

    /**
     * Build an orthographic projection sized to the framebuffer and upload
     * it as the {@code projection} uniform. Skipped for non-positive
     * framebuffer dimensions.
     *
     * @param framebufferWidth viewport width in pixels
     * @param framebufferHeight viewport height in pixels
     * @param scale DPI scale hint (unused)
     */
    @Override
    public void updateProjectionMatrix(int framebufferWidth, int framebufferHeight, float scale) {
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            return;
        }
        use();
        Matrix4f projection = new Matrix4f();
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
