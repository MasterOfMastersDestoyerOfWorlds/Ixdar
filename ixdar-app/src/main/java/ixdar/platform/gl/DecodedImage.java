package ixdar.platform.gl;

/**
 * RGBA8 pixels a {@link Platform} image decoder produced, four bytes per pixel in row-major order
 * from the bottom row up (OpenGL texture origin).
 */
public final class DecodedImage {

    /** Bytes per RGBA8 pixel. */
    public static final int BYTES_PER_PIXEL = 4;

    public final byte[] rgba;

    public final int width;

    public final int height;

    /**
     * Wrap decoded pixels; the array is taken as given, not copied.
     *
     * @param rgba RGBA8 bytes, {@code 4 * width * height} long, bottom row first
     * @param width image width in pixels
     * @param height image height in pixels
     * @throws IllegalArgumentException if {@code rgba} is null or its length does not match the
     *         stated dimensions
     */
    public DecodedImage(byte[] rgba, int width, int height) {
        if (rgba == null || rgba.length != BYTES_PER_PIXEL * width * height) {
            throw new IllegalArgumentException(
                    "rgba must hold 4 bytes per pixel for " + width + "x" + height);
        }
        this.rgba = rgba;
        this.width = width;
        this.height = height;
    }
}
