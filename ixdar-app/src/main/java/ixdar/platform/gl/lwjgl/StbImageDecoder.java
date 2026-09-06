package ixdar.platform.gl.lwjgl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;

import ixdar.platform.gl.DecodedImage;

/**
 * Desktop image decode: {@code stbi_load_from_memory} to RGBA8 with the rows flipped to the OpenGL
 * bottom-left origin. Both desktop platforms delegate here so the native call has one home.
 */
public final class StbImageDecoder {

    /** Channel count requested from stb, forcing RGBA8 output whatever the source has. */
    public static final int RGBA_CHANNELS = 4;

    private StbImageDecoder() {
    }

    /**
     * Decode compressed image bytes into a heap {@link DecodedImage}.
     *
     * @param encoded PNG/JPEG bytes; null or empty yields null
     * @return decoded RGBA8 pixels, or {@code null} when stb rejects the bytes
     */
    public static DecodedImage decode(byte[] encoded) {
        if (encoded == null || encoded.length == 0) {
            return null;
        }
        ByteBuffer encodedBuffer = BufferUtils.createByteBuffer(encoded.length);
        encodedBuffer.put(encoded).flip();
        STBImage.stbi_set_flip_vertically_on_load(true);
        IntBuffer width = BufferUtils.createIntBuffer(1);
        IntBuffer height = BufferUtils.createIntBuffer(1);
        IntBuffer channels = BufferUtils.createIntBuffer(1);
        ByteBuffer pixels = STBImage.stbi_load_from_memory(encodedBuffer, width, height, channels, RGBA_CHANNELS);
        if (pixels == null) {
            System.out.println("stb could not decode the image: " + STBImage.stbi_failure_reason());
            return null;
        }
        byte[] rgba = new byte[pixels.remaining()];
        pixels.get(rgba);
        // stbi_image_free frees memAddress(pixels), which is base + position: rewind first or the
        // read above shifts the pointer and glibc aborts on the mismatched free.
        pixels.rewind();
        STBImage.stbi_image_free(pixels);
        return new DecodedImage(rgba, width.get(0), height.get(0));
    }
}
