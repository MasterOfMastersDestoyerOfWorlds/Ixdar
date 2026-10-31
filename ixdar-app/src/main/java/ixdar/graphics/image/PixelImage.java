package ixdar.graphics.image;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A CPU-side ARGB8888 image, one packed int per pixel in row-major order from the top row down.
 * Pixels are stored non-premultiplied, alpha in the high byte.
 */
public final class PixelImage {

    public static final int OPAQUE = 0xFF000000;

    public static final int RGB_MASK = 0x00FFFFFF;

    public static final int CHANNEL_MASK = 0xFF;

    public static final int CHANNEL_BITS = 8;

    public static final int RED_SHIFT = 16;

    public static final int MINIMUM_BOUNDARY_CAPACITY = 64;

    public static final int[] NEIGHBOUR_X = { -1, -1, 0, 1, 1, 1, 0, -1 };

    public static final int[] NEIGHBOUR_Y = { 0, -1, -1, -1, 0, 1, 1, 1 };

    public static final int[] DIRECTION_BY_OFFSET = { 1, 2, 3, 0, -1, 4, 7, 6, 5 };

    public final int[] pixels;

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
        this.pixels = new int[width * height];
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
        return pixels[y * width + x];
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
        pixels[y * width + x] = packed;
    }

    /**
     * Set every pixel to one colour.
     *
     * @param packed the packed ARGB value to store everywhere
     */
    public void fill(int packed) {
        Arrays.fill(pixels, packed);
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
            System.arraycopy(pixels, (top + row) * width + left, out.pixels, row * clampedWidth,
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
                System.arraycopy(source.pixels, sourceStart, pixels, targetStart, span);
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
                out.pixels[targetRow + x] = pixels[sourceRow + x / factor];
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
        int first = pixels[0] & RGB_MASK;
        for (int pixel : pixels) {
            if ((pixel & RGB_MASK) != first) {
                return false;
            }
        }
        return true;
    }

    /**
     * Trace the outer boundary of every 8-connected blob of pixels that stand out from a background
     * colour (Moore-neighbour tracing with Jacob's stopping rule), each simplified by
     * Douglas-Peucker; holes inside a blob are not traced.
     *
     * @param background packed colour the blobs stand out from; alpha is ignored
     * @param colourTolerance largest summed red, green and blue difference still counted as background
     * @param simplifyPixels largest distance a dropped boundary pixel may lie from the kept outline
     * @param minimumArea smallest blob, in pixels, that is traced
     * @return one closed outline per blob, x then y of boundary pixels, its first point repeated
     *         last, blobs in raster order of their first pixel
     */
    public List<int[]> outlines(int background, int colourTolerance, float simplifyPixels, int minimumArea) {
        int count = width * height;
        // NEIGHBOUR_X and NEIGHBOUR_Y run clockwise on screen (y down) from west; DIRECTION_BY_OFFSET
        // maps an offset back to that index at (dy + 1) * 3 + dx + 1.
        // 0 background, -1 foreground not yet reached, otherwise the blob's number.
        int[] blob = new int[count];
        for (int index = 0; index < count; index++) {
            int difference = 0;
            for (int shift = 0; shift <= RED_SHIFT; shift += CHANNEL_BITS) {
                difference += Math.abs(((pixels[index] >> shift) & CHANNEL_MASK)
                        - ((background >> shift) & CHANNEL_MASK));
            }
            blob[index] = difference > colourTolerance ? -1 : 0;
        }
        int[] frontier = new int[count];
        List<int[]> outlines = new ArrayList<>();
        int blobs = 0;
        for (int start = 0; start < count; start++) {
            if (blob[start] != -1) {
                continue;
            }
            blobs++;
            int area = 0;
            int top = 0;
            frontier[top++] = start;
            blob[start] = blobs;
            while (top > 0) {
                int pixel = frontier[--top];
                area++;
                for (int direction = 0; direction < NEIGHBOUR_X.length; direction++) {
                    int neighbourX = pixel % width + NEIGHBOUR_X[direction];
                    int neighbourY = pixel / width + NEIGHBOUR_Y[direction];
                    if (neighbourX >= 0 && neighbourY >= 0 && neighbourX < width && neighbourY < height
                            && blob[neighbourY * width + neighbourX] == -1) {
                        blob[neighbourY * width + neighbourX] = blobs;
                        frontier[top++] = neighbourY * width + neighbourX;
                    }
                }
            }
            if (area < minimumArea) {
                continue;
            }
            // The first pixel in raster order has no blob pixel to its west, which starts the
            // backtrack; each step searches clockwise from the backtrack for the next boundary pixel.
            int[] boundary = new int[2 * MINIMUM_BOUNDARY_CAPACITY];
            int length = 0;
            boundary[length++] = start % width;
            boundary[length++] = start / width;
            int current = start;
            int back = 0;
            for (int step = 0; step < 2 * area + MINIMUM_BOUNDARY_CAPACITY; step++) {
                int found = -1;
                int next = -1;
                for (int turn = 1; turn <= NEIGHBOUR_X.length && found < 0; turn++) {
                    int direction = (back + turn) % NEIGHBOUR_X.length;
                    int neighbourX = current % width + NEIGHBOUR_X[direction];
                    int neighbourY = current / width + NEIGHBOUR_Y[direction];
                    if (neighbourX >= 0 && neighbourY >= 0 && neighbourX < width && neighbourY < height
                            && blob[neighbourY * width + neighbourX] == blobs) {
                        found = direction;
                        next = neighbourY * width + neighbourX;
                    }
                }
                if (found < 0) {
                    break;
                }
                int previous = (found + NEIGHBOUR_X.length - 1) % NEIGHBOUR_X.length;
                int backX = current % width + NEIGHBOUR_X[previous] - next % width;
                int backY = current / width + NEIGHBOUR_Y[previous] - next / width;
                back = DIRECTION_BY_OFFSET[(backY + 1) * 3 + backX + 1];
                current = next;
                if (current == start && back == 0) {
                    break;
                }
                if (length + 2 > boundary.length) {
                    boundary = Arrays.copyOf(boundary, boundary.length * 2);
                }
                boundary[length++] = current % width;
                boundary[length++] = current / width;
            }
            boundary = Arrays.copyOf(boundary, length + 2);
            boundary[length] = boundary[0];
            boundary[length + 1] = boundary[1];
            int points = boundary.length / 2;
            boolean[] keep = new boolean[points];
            keep[0] = true;
            keep[points - 1] = true;
            int[] ranges = new int[2 * points];
            int pending = 0;
            ranges[pending++] = 0;
            ranges[pending++] = points - 1;
            while (pending > 0) {
                int last = ranges[--pending];
                int first = ranges[--pending];
                float chordX = boundary[last * 2] - boundary[first * 2];
                float chordY = boundary[last * 2 + 1] - boundary[first * 2 + 1];
                float chordSquared = chordX * chordX + chordY * chordY;
                float farthest = 0f;
                int farthestPoint = -1;
                for (int point = first + 1; point < last; point++) {
                    float offsetX = boundary[point * 2] - boundary[first * 2];
                    float offsetY = boundary[point * 2 + 1] - boundary[first * 2 + 1];
                    float along = chordSquared == 0f ? 0f
                            : Math.max(0f, Math.min(1f, (offsetX * chordX + offsetY * chordY) / chordSquared));
                    float distance = (float) Math.hypot(offsetX - along * chordX, offsetY - along * chordY);
                    if (distance > farthest) {
                        farthest = distance;
                        farthestPoint = point;
                    }
                }
                if (farthestPoint >= 0 && farthest > simplifyPixels) {
                    keep[farthestPoint] = true;
                    ranges[pending++] = first;
                    ranges[pending++] = farthestPoint;
                    ranges[pending++] = farthestPoint;
                    ranges[pending++] = last;
                }
            }
            int kept = 0;
            for (boolean point : keep) {
                kept += point ? 1 : 0;
            }
            int[] outline = new int[kept * 2];
            for (int point = 0, written = 0; point < points; point++) {
                if (keep[point]) {
                    outline[written++] = boundary[point * 2];
                    outline[written++] = boundary[point * 2 + 1];
                }
            }
            outlines.add(outline);
        }
        return outlines;
    }
}
