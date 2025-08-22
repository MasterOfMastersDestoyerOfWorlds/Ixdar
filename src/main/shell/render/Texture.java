
package shell.render;

import java.nio.ByteBuffer;

import shell.platform.Platforms;
import shell.platform.gl.GL;

public class Texture {

    public int id = -1;

    public int width;

    public int height;

    private ByteBuffer image;

    public boolean initialized;
    String resourceName;

    private static GL gl = Platforms.gl();

    public Texture(String resourceName) {
        this.resourceName = resourceName;
        this.initialized = false;
    }

    public Texture(String resourceName, int texture, int width2, int height2) {
        this.resourceName = resourceName;
        this.initialized = true;
        this.id = texture;
        this.width = width2;
        this.height = height2;
    }

    public Texture(String resourceName, ByteBuffer image, int width, int height) {
        this.resourceName = resourceName;
        this.initialized = false;
        this.image = image;
        this.width = width;
        this.height = height;
    }

    public Texture(String resourceName, int width, int height) {
        this.resourceName = resourceName;
        this.initialized = false;
        this.width = width;
        this.height = height;
    }

    public void bind() {
        gl.bindTexture2D(id);
    }

    public void uploadData(int width, int height, ByteBuffer data) {
        uploadData(gl.RGBA8(), width, height, gl.RGBA(), data);
    }

    public void uploadData(int internalFormat, int width, int height, int format, ByteBuffer data) {
        gl.texImage2D(gl.TEXTURE_2D(), 0, internalFormat, width,
                height, 0, format, gl.UNSIGNED_BYTE(), data);
    }

    public void delete() {
        // TODO: platform delete
    }

    public float getWidth() {
        return width;
    }

    public void setWidth(int width) {
        if (width > 0) {
            this.width = width;
        }
    }

    public float getHeight() {
        return height;
    }

    public void setHeight(int height) {
        if (height > 0) {
            this.height = height;
        }
    }

    public static Texture createTexture(String fontName, int width, int height, ByteBuffer data) {
        Texture texture = new Texture(fontName);
        texture.setWidth(width);
        texture.setHeight(height);
        texture.initialized = true;
        texture.bind();

        gl.texParameteri(gl.TEXTURE_2D(),
                gl.TEXTURE_WRAP_S(), gl.REPEAT());
        gl.texParameteri(gl.TEXTURE_2D(),
                gl.TEXTURE_WRAP_T(), gl.REPEAT());
        gl.texParameteri(gl.TEXTURE_2D(),
                gl.TEXTURE_MIN_FILTER(), gl.LINEAR());
        gl.texParameteri(gl.TEXTURE_2D(),
                gl.TEXTURE_MAG_FILTER(), gl.LINEAR());

        texture.uploadData(gl.RGBA8(), width, height, gl.RGBA(),
                data);

        return texture;
    }

    public void setImage(int width, int height, ByteBuffer image) {
        this.width = width;
        this.height = height;
        this.image = image;
    }

    public void initGL() {
        if (image == null) {
            return;
        }
        initialized = true;
        id = gl.genTexture();
        gl.texParameteri(gl.TEXTURE_2D(),
                gl.TEXTURE_WRAP_S(), gl.REPEAT());
        gl.texParameteri(gl.TEXTURE_2D(),
                gl.TEXTURE_WRAP_T(), gl.REPEAT());
        gl.texParameteri(gl.TEXTURE_2D(),
                gl.TEXTURE_MIN_FILTER(), gl.LINEAR());
        gl.texParameteri(gl.TEXTURE_2D(),
                gl.TEXTURE_MAG_FILTER(), gl.LINEAR());

        gl.bindTexture2D(id);
        gl.texImage2D(gl.TEXTURE_2D(), 0,
                gl.RGBA(), width, height, 0, gl.RGBA(),
                gl.UNSIGNED_BYTE(), image);
        gl.generateMipmap(gl.TEXTURE_2D());
        gl.blendFunc(gl.SRC_ALPHA(), gl.ONE_MINUS_SRC_ALPHA());
        gl.enable(gl.BLEND());
        // image buffer owned by platform loader; no direct free here
    }

}
