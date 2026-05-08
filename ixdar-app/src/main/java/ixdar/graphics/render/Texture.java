
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
     * TODO: document {@code Texture}.
     *
     * @param resourceName TODO: describe
     */
    public Texture(String resourceName) {
        this.resourceName = resourceName;
        this.initialized = false;
    }

    /**
     * TODO: document {@code Texture}.
     *
     * @param resourceName TODO: describe
     * @param texture TODO: describe
     * @param width2 TODO: describe
     * @param height2 TODO: describe
     */
    public Texture(String resourceName, int texture, int width2, int height2) {
        this.resourceName = resourceName;
        this.initialized = true;
        this.id = texture;
        this.width = width2;
        this.height = height2;
    }

    /**
     * TODO: document {@code Texture}.
     *
     * @param resourceName TODO: describe
     * @param image TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     */
    public Texture(String resourceName, ByteBuffer image, int width, int height) {
        this.resourceName = resourceName;
        this.initialized = false;
        this.image = image;
        this.width = width;
        this.height = height;
    }

    /**
     * TODO: document {@code Texture}.
     *
     * @param resourceName TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     */
    public Texture(String resourceName, int width, int height) {
        this.resourceName = resourceName;
        this.initialized = false;
        this.width = width;
        this.height = height;
    }

    /**
     * TODO: document {@code bind}.
     */
    public void bind() {
        GL gl = Platforms.gl();
        gl.bindTexture2D(id);
    }

    /**
     * TODO: document {@code uploadData}.
     *
     * @param width TODO: describe
     * @param height TODO: describe
     * @param data TODO: describe
     */
    public void uploadData(int width, int height, ByteBuffer data) {
        GL gl = Platforms.gl();
        uploadData(gl.RGBA8(), width, height, gl.RGBA(), data);
    }

    /**
     * TODO: document {@code uploadData}.
     *
     * @param internalFormat TODO: describe
     * @param width TODO: describe
     * @param height TODO: describe
     * @param format TODO: describe
     * @param data TODO: describe
     */
    public void uploadData(int internalFormat, int width, int height, int format, ByteBuffer data) {
        GL gl = Platforms.gl();
        gl.texImage2D(gl.TEXTURE_2D(), 0, internalFormat, width,
                height, 0, format, gl.UNSIGNED_BYTE(), data);
    }

    /**
     * TODO: document {@code delete}.
     */
    public void delete() {
        if (id >= 0) {
            Platforms.gl().deleteTexture(id);
            id = -1;
            initialized = false;
        }
    }

    /**
     * TODO: document {@code getWidth}.
     *
     * @return TODO: describe
     */
    public float getWidth() {
        return width;
    }

    /**
     * TODO: document {@code setWidth}.
     *
     * @param width TODO: describe
     */
    public void setWidth(int width) {
        if (width > 0) {
            this.width = width;
        }
    }

    /**
     * TODO: document {@code getHeight}.
     *
     * @return TODO: describe
     */
    public float getHeight() {
        return height;
    }

    /**
     * TODO: document {@code setHeight}.
     *
     * @param height TODO: describe
     */
    public void setHeight(int height) {
        if (height > 0) {
            this.height = height;
        }
    }


    /**
     * TODO: document {@code setImage}.
     *
     * @param width TODO: describe
     * @param height TODO: describe
     * @param image TODO: describe
     */
    public void setImage(int width, int height, ByteBuffer image) {
        this.width = width;
        this.height = height;
        this.image = image;
    }

    /**
     * TODO: document {@code initGL}.
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
        // image buffer owned by platform loader; no direct free here
    }

}
