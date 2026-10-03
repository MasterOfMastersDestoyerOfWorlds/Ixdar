package ixdar.graphics.render.shaders;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.joml.Matrix4f;
import org.joml.Vector2f;

import ixdar.platform.Platforms;

/**
 * The overlay line shader: discards line fragments on surface facing away from the camera and
 * writes each fragment the depth of the face it lies on, so it needs the framebuffer size to rebuild
 * the pixel ray.
 */
public class MeshLineShader extends MeshShader {
    public static final String VIEWPORT_SIZE = "viewportSize";

    private final Vector2f viewportSize = new Vector2f();

    /**
     * Build the line shader on the mesh vertex layout.
     *
     * @param vertexShaderLocation   vertex GLSL resource path
     * @param fragmentShaderLocation fragment GLSL resource path
     * @throws UnsupportedEncodingException on shader source encoding error
     * @throws IOException                  on shader source I/O error
     */
    public MeshLineShader(String vertexShaderLocation, String fragmentShaderLocation)
            throws UnsupportedEncodingException, IOException {
        super(vertexShaderLocation, fragmentShaderLocation);
    }

    /**
     * Bind the shader with the matrices and framebuffer size its depth and facing tests read.
     *
     * @param view       camera view matrix
     * @param projection projection matrix
     * @param model      model matrix of the surface the lines lie on
     */
    public void use(Matrix4f view, Matrix4f projection, Matrix4f model) {
        use();
        setMat4("model", model);
        setMat4("view", view);
        setMat4("projection", projection);
        setVec2(VIEWPORT_SIZE, viewportSize.set(Platforms.get().getFrameBufferWidth(),
                Platforms.get().getFrameBufferHeight()));
    }
}
