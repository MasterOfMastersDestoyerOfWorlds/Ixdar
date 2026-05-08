
package ixdar.graphics.render;

import java.nio.ByteBuffer;

import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class Texture {

    public int id = -1;

    public int width;

    public int height;

    public boolean initialized;
    String resourceName;

    private ByteBuffer image;

    /**
     * Build a placeholder texture identified by a resource name; pixels and
     * dimensions are filled in later by the platform loader.
     *
     * @param resourceName resource path used by the platform loader
     */
    public Texture(String resourceName) {
        this.resourceName = resourceName;
        this.initialized = false;
    }

    /**
     * Wrap an already-uploaded GL texture handle.
     *
     * @param resourceName resource path (purely descriptive)
     * @param texture GL texture id
     * @param width2 width in pixels
     * @param height2 height in pixels
     */
    public Texture(String resourceName, int texture, int width2, int height2) {
        this.resourceName = resourceName;
        this.initialized = true;
        this.id = texture;
        this.width = width2;
        this.height = height2;
    }

    /**
     * Stage a CPU-decoded image for later GL upload via {@link #initGL()}.
     *
     * @param resourceName resource path (purely descriptive)
     * @param image RGBA pixel buffer
     * @param width width in pixels
     * @param height height in pixels
     */
    public Texture(String resourceName, ByteBuffer image, int width, int height) {
        this.resourceName = resourceName;
        this.initialized = false;
        this.image = image;
        this.width = width;
        this.height = height;
    }

    /**
     * Build an uninitialized texture with known dimensions but no pixels yet.
     *
     * @param resourceName resource path (purely descriptive)
     * @param width width in pixels
     * @param height height in pixels
     */
    public Texture(String resourceName, int width, int height) {
        this.resourceName = resourceName;
        this.initialized = false;
        this.width = width;
        this.height = height;
    }

    /**
     * Bind this texture to the GL_TEXTURE_2D target.
     */
    public void bind() {
        GL gl = Platforms.gl();
        gl.bindTexture2D(id);
    }

    /**
     * Upload pixel data using RGBA8 / RGBA format defaults.
     *
     * @param width width in pixels
     * @param height height in pixels
     * @param data RGBA pixel buffer
     */
    public void uploadData(int width, int height, ByteBuffer data) {
        GL gl = Platforms.gl();
        uploadData(gl.RGBA8(), width, height, gl.RGBA(), data);
    }

    /**
     * Upload pixel data with explicit internal format and source format.
     *
     * @param internalFormat GL internal storage format (e.g. RGBA8)
     * @param width width in pixels
     * @param height height in pixels
     * @param format GL pixel format of {@code data} (e.g. RGBA)
     * @param data pixel buffer
     */
    public void uploadData(int internalFormat, int width, int height, int format, ByteBuffer data) {
        GL gl = Platforms.gl();
        gl.texImage2D(gl.TEXTURE_2D(), 0, internalFormat, width,
                height, 0, format, gl.UNSIGNED_BYTE(), data);
    }

    /**
     * Free the underlying GL texture and mark this object uninitialized;
     * no-op if the texture was never uploaded.
     */
    public void delete() {
        if (id >= 0) {
            Platforms.gl().deleteTexture(id);
            id = -1;
            initialized = false;
        }
    }

    /**
     * Width in pixels.
     *
     * @return texture width
     */
    public float getWidth() {
        return width;
    }

    /**
     * Set the texture width (ignored when {@code width} is non-positive).
     *
     * @param width new width in pixels
     */
    public void setWidth(int width) {
        if (width > 0) {
            this.width = width;
        }
    }

    /**
     * Height in pixels.
     *
     * @return texture height
     */
    public float getHeight() {
        return height;
    }

    /**
     * Set the texture height (ignored when {@code height} is non-positive).
     *
     * @param height new height in pixels
     */
    public void setHeight(int height) {
        if (height > 0) {
            this.height = height;
        }
    }


    /**
     * Stage RGBA pixel data and dimensions for a deferred GL upload via
     * {@link #initGL()}.
     *
     * @param width width in pixels
     * @param height height in pixels
     * @param image RGBA pixel buffer
     */
    public void setImage(int width, int height, ByteBuffer image) {
        this.width = width;
        this.height = height;
        this.image = image;
    }

    /**
     * Upload the staged image to GL: generate a texture id, set repeat
     * wrapping with linear filtering, allocate storage, generate mipmaps,
     * and enable standard alpha blending. No-op if no image was staged.
     */
    public void initGL() {
        if (image == null) {
            return;
        }
        GL gl = Platforms.gl();
        initialized = true;
        id = gl.genTexture();
        gl.bindTexture2D(id);
        gl.texParameteri(gl.TEXTURE_2D(),
                gl.TEXTURE_WRAP_S(), gl.REPEAT());
        gl.texParameteri(gl.TEXTURE_2D(),
                gl.TEXTURE_WRAP_T(), gl.REPEAT());
        gl.texParameteri(gl.TEXTURE_2D(),
                gl.TEXTURE_MIN_FILTER(), gl.LINEAR());
        gl.texParameteri(gl.TEXTURE_2D(),
                gl.TEXTURE_MAG_FILTER(), gl.LINEAR());
        gl.texImage2D(gl.TEXTURE_2D(), 0,
                gl.RGBA(), width, height, 0, gl.RGBA(),
                gl.UNSIGNED_BYTE(), image);
        gl.generateMipmap(gl.TEXTURE_2D());
        gl.blendFunc(gl.SRC_ALPHA(), gl.ONE_MINUS_SRC_ALPHA());
        gl.enable(gl.BLEND());
    }

}
