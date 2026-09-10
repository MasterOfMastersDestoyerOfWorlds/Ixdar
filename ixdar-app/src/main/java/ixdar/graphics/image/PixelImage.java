package ixdar.graphics.image;

import java.util.Arrays;

/**
 * A CPU-side ARGB8888 image, one packed int per pixel in row-major order from the top row down.
 * Pixels are stored non-premultiplied, alpha in the high byte.
 */
public final class PixelImage {

    /** Fully opaque alpha, ready to be OR-ed onto a packed RGB triple. */
    public static final int OPAQUE = 0xFF000000;

    /** Mask isolating the colour channels of a packed pixel. */
    public static final int RGB_MASK = 0x00FFFFFF;

    public final int[] argb;

    public final int width;

    public final int height;

    /**
     * Allocate a transparent image of the given size.
     *
     * @param width image width in pixels, at least one
     * @param height image height in pixels, at least one
     * @throws IllegalArgumentException if either dimension is not positive
     */
    public PixelImage(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("PixelImage needs positive dimensions, got "
                    + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.argb = new int[width * height];
    }

    /**
     * Read one pixel.
     *
     * @param x column, measured from the left edge
     * @param y row, measured from the top edge
     * @return the packed ARGB value at that position
     * @throws IndexOutOfBoundsException if the position lies outside the image
     */
    public int get(int x, int y) {
        return argb[y * width + x];
    }

    /**
     * Overwrite one pixel, ignoring positions outside the image so callers can clip loosely.
     *
     * @param x column, measured from the left edge
     * @param y row, measured from the top edge
     * @param packed the packed ARGB value to store
     */
    public void set(int x, int y, int packed) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return;
        }
        argb[y * width + x] = packed;
    }

    /**
     * Set every pixel to one colour.
     *
     * @param packed the packed ARGB value to store everywhere
     */
    public void fill(int packed) {
        Arrays.fill(argb, packed);
    }

    /**
     * Copy a rectangle out of this image. The rectangle is clamped to the image bounds, so a
     * request reaching past an edge yields the part that exists rather than failing.
     *
     * @param x left edge of the wanted rectangle
     * @param y top edge of the wanted rectangle
     * @param regionWidth wanted width in pixels
     * @param regionHeight wanted height in pixels
     * @return a new image holding the clamped rectangle
     */
    public PixelImage region(int x, int y, int regionWidth, int regionHeight) {
        int left = Math.max(0, Math.min(x, width - 1));
        int top = Math.max(0, Math.min(y, height - 1));
        int clampedWidth = Math.max(1, Math.min(regionWidth, width - left));
        int clampedHeight = Math.max(1, Math.min(regionHeight, height - top));
        PixelImage out = new PixelImage(clampedWidth, clampedHeight);
        for (int row = 0; row < clampedHeight; row++) {
            System.arraycopy(argb, (top + row) * width + left, out.argb, row * clampedWidth,
                    clampedWidth);
        }
        return out;
    }

    /**
     * Copy another image in at an offset, overwriting the pixels it lands on and clipping
     * whatever falls outside this image.
     *
     * @param source the image to copy in
     * @param destinationX left edge the copy lands on
     * @param destinationY top edge the copy lands on
     */
    public void blit(PixelImage source, int destinationX, int destinationY) {
        int right = Math.min(width, destinationX + source.width);
        int bottom = Math.min(height, destinationY + source.height);
        for (int row = Math.max(0, destinationY); row < bottom; row++) {
            int sourceStart = (row - destinationY) * source.width
                    + Math.max(0, destinationX) - destinationX;
            int targetStart = row * width + Math.max(0, destinationX);
            int span = right - Math.max(0, destinationX);
            if (span > 0) {
                System.arraycopy(source.argb, sourceStart, argb, targetStart, span);
            }
        }
    }

    /**
     * Enlarge by an integer factor with nearest-neighbour sampling, so a one-pixel line stays a
     * crisp block rather than blurring.
     *
     * @param factor enlargement factor; anything below two returns this image unchanged
     * @return the enlarged image, or this image when no enlargement was asked for
     */
    public PixelImage scaled(int factor) {
        if (factor <= 1) {
            return this;
        }
        PixelImage out = new PixelImage(width * factor, height * factor);
        for (int y = 0; y < out.height; y++) {
            int sourceRow = (y / factor) * width;
            int targetRow = y * out.width;
            for (int x = 0; x < out.width; x++) {
                out.argb[targetRow + x] = argb[sourceRow + x / factor];
            }
        }
        return out;
    }

    /**
     * Whether every pixel carries the same colour, which is what an empty framebuffer or a render
     * that never reached the scene looks like. Alpha is ignored.
     *
     * @return true when the image holds a single colour
     */
    public boolean isUniform() {
        int first = argb[0] & RGB_MASK;
        for (int pixel : argb) {
            if ((pixel & RGB_MASK) != first) {
                return false;
            }
        }
        return true;
    }
}
