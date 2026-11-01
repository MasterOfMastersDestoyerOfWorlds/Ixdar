package ixdar.graphics.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import ixdar.geometry.grid2d.PolylineSimplify;
import ixdar.platform.Platforms;
import ixdar.platform.gl.DecodedImage;

/**
 * The subject of a picture: the largest blob standing out from a background colour, as its outer
 * boundary in picture pixels and the bounding box of that boundary. What the stroke-sculpt tracing
 * commands read out of a reference picture or a capture's cell.
 */
public final class SubjectOutline {

    public static final int BOX_FIELDS = 4;

    public static final int EDGE_BLUR_RADIUS = 2;

    public static final int EDGE_INSET_RADIUS = 3;

    /** The outer boundary, packed x then y in pixel indices, its first point repeated last. */
    public float[] outline;

    /** Bounding box of the boundary: x, y, width, height in pixels, width and height counting both ends. */
    public final int[] box = new int[BOX_FIELDS];

    /**
     * Decode a PNG or JPEG file into a picture.
     *
     * @param file the image file
     * @throws IOException when it cannot be read or decoded
     * @return the picture
     */
    public static PixelImage read(Path file) throws IOException {
        DecodedImage decoded = Platforms.get().decodeImage(Files.readAllBytes(file));
        if (decoded == null) {
            throw new IOException("cannot decode " + file);
        }
        PixelImage picture = new PixelImage(decoded.width, decoded.height);
        for (int pixel = 0; pixel < decoded.width * decoded.height; pixel++) {
            // The decoder flips rows to the OpenGL bottom-left origin; a picture's row 0 is its top.
            int source = (decoded.height - 1 - pixel / decoded.width) * decoded.width + pixel % decoded.width;
            int at = source * DecodedImage.BYTES_PER_PIXEL;
            picture.pixels[pixel] = PixelImage.OPAQUE | (decoded.rgba[at] & PixelImage.CHANNEL_MASK) << PixelImage.RED_SHIFT
                    | (decoded.rgba[at + 1] & PixelImage.CHANNEL_MASK) << PixelImage.CHANNEL_BITS
                    | decoded.rgba[at + 2] & PixelImage.CHANNEL_MASK;
        }
        return picture;
    }

    /**
     * A picture's background colour: per channel, the median of its border pixels.
     *
     * @param picture the picture
     * @return the median, packed as {@link PixelImage} stores a pixel
     */
    public static int borderMedian(PixelImage picture) {
        int border = 2 * (picture.width + picture.height);
        int[][] channels = new int[3][border];
        int count = 0;
        for (int index = 0; index < picture.width; index++) {
            for (int row : new int[] { 0, picture.height - 1 }) {
                int sample = picture.get(index, row);
                for (int channel = 0; channel < 3; channel++) {
                    channels[channel][count] = sample >> (PixelImage.RED_SHIFT - channel * PixelImage.CHANNEL_BITS)
                            & PixelImage.CHANNEL_MASK;
                }
                count++;
            }
        }
        for (int index = 0; index < picture.height; index++) {
            for (int column : new int[] { 0, picture.width - 1 }) {
                int sample = picture.get(column, index);
                for (int channel = 0; channel < 3; channel++) {
                    channels[channel][count] = sample >> (PixelImage.RED_SHIFT - channel * PixelImage.CHANNEL_BITS)
                            & PixelImage.CHANNEL_MASK;
                }
                count++;
            }
        }
        int median = PixelImage.OPAQUE;
        for (int channel = 0; channel < 3; channel++) {
            int[] sorted = Arrays.copyOf(channels[channel], count);
            Arrays.sort(sorted);
            median |= sorted[count / 2] << (PixelImage.RED_SHIFT - channel * PixelImage.CHANNEL_BITS);
        }
        return median;
    }

    /**
     * The largest blob of a picture standing out from a background colour, as its outer boundary
     * simplified by Douglas-Peucker and the box of the unsimplified boundary.
     *
     * @param picture    the picture
     * @param background packed background colour
     * @param tolerance  largest summed red, green and blue difference still counted as background
     * @param simplify   largest distance in pixels a dropped boundary point may lie from the outline
     * @return the subject, or null when nothing stands out
     */
    public static SubjectOutline of(PixelImage picture, int background, int tolerance, float simplify) {
        List<int[]> exact = picture.outlines(background, tolerance, 0f, 1);
        int largest = -1;
        double largestArea = 0.0;
        for (int index = 0; index < exact.size(); index++) {
            int[] boundary = exact.get(index);
            double area = 0.0;
            for (int point = 0; point + 3 < boundary.length; point += 2) {
                area += (double) boundary[point] * boundary[point + 3] - (double) boundary[point + 2] * boundary[point + 1];
            }
            if (Math.abs(area) >= largestArea) {
                largestArea = Math.abs(area);
                largest = index;
            }
        }
        if (largest < 0) {
            return null;
        }
        SubjectOutline subject = new SubjectOutline();
        int[] boundary = exact.get(largest);
        int[] bounds = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        float[] xy = new float[boundary.length];
        for (int point = 0; point < boundary.length; point += 2) {
            bounds[0] = Math.min(bounds[0], boundary[point]);
            bounds[1] = Math.min(bounds[1], boundary[point + 1]);
            bounds[2] = Math.max(bounds[2], boundary[point]);
            bounds[3] = Math.max(bounds[3], boundary[point + 1]);
            xy[point] = boundary[point];
            xy[point + 1] = boundary[point + 1];
        }
        subject.box[0] = bounds[0];
        subject.box[1] = bounds[1];
        subject.box[2] = bounds[2] - bounds[0] + 1;
        subject.box[3] = bounds[3] - bounds[1] + 1;
        subject.outline = simplify > 0f ? PolylineSimplify.douglasPeucker(xy, simplify) : xy;
        return subject;
    }

    /**
     * Edges inside the subject, for viewing: pixels whose blurred luminance gradient (Sobel) passes
     * the high threshold, grown along neighbours above the low one (Canny's hysteresis, without
     * thinning), more than a few pixels inside the subject's outline.
     *
     * @param picture the picture
     * @param inside  the subject's interior per pixel, row by row
     * @param low     gradient magnitude an edge continues through
     * @param high    gradient magnitude an edge starts at
     * @return the edge pixels as a black-on-white picture, for {@link PixelImage#outlines}
     */
    public static PixelImage interiorEdges(PixelImage picture, boolean[] inside, float low, float high) {
        int width = picture.width;
        int height = picture.height;
        float[] luminance = new float[width * height];
        for (int pixel = 0; pixel < luminance.length; pixel++) {
            int sample = picture.pixels[pixel];
            luminance[pixel] = ((sample >> PixelImage.RED_SHIFT & PixelImage.CHANNEL_MASK)
                    + (sample >> PixelImage.CHANNEL_BITS & PixelImage.CHANNEL_MASK) + (sample & PixelImage.CHANNEL_MASK)) / 3f;
        }
        float[] blurred = new float[luminance.length];
        for (int pixel = 0; pixel < luminance.length; pixel++) {
            float sum = 0f;
            int samples = 0;
            for (int dy = -EDGE_BLUR_RADIUS; dy <= EDGE_BLUR_RADIUS; dy++) {
                for (int dx = -EDGE_BLUR_RADIUS; dx <= EDGE_BLUR_RADIUS; dx++) {
                    int x = Math.min(width - 1, Math.max(0, pixel % width + dx));
                    int y = Math.min(height - 1, Math.max(0, pixel / width + dy));
                    sum += luminance[y * width + x];
                    samples++;
                }
            }
            blurred[pixel] = sum / samples;
        }
        float[] magnitude = new float[luminance.length];
        boolean[] deepInside = new boolean[luminance.length];
        for (int pixel = 0; pixel < luminance.length; pixel++) {
            int x = pixel % width;
            int y = pixel / width;
            boolean interior = x >= EDGE_INSET_RADIUS && y >= EDGE_INSET_RADIUS && x < width - EDGE_INSET_RADIUS
                    && y < height - EDGE_INSET_RADIUS;
            for (int dy = -EDGE_INSET_RADIUS; dy <= EDGE_INSET_RADIUS && interior; dy++) {
                for (int dx = -EDGE_INSET_RADIUS; dx <= EDGE_INSET_RADIUS && interior; dx++) {
                    interior = inside[(y + dy) * width + x + dx];
                }
            }
            deepInside[pixel] = interior;
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                continue;
            }
            float gradientX = blurred[pixel - width + 1] + 2f * blurred[pixel + 1] + blurred[pixel + width + 1]
                    - blurred[pixel - width - 1] - 2f * blurred[pixel - 1] - blurred[pixel + width - 1];
            float gradientY = blurred[pixel + width - 1] + 2f * blurred[pixel + width] + blurred[pixel + width + 1]
                    - blurred[pixel - width - 1] - 2f * blurred[pixel - width] - blurred[pixel - width + 1];
            magnitude[pixel] = (float) Math.hypot(gradientX, gradientY);
        }
        boolean[] edge = new boolean[luminance.length];
        int[] frontier = new int[luminance.length];
        int top = 0;
        for (int pixel = 0; pixel < luminance.length; pixel++) {
            if (deepInside[pixel] && magnitude[pixel] >= high) {
                edge[pixel] = true;
                frontier[top++] = pixel;
            }
        }
        while (top > 0) {
            int pixel = frontier[--top];
            for (int direction = 0; direction < PixelImage.NEIGHBOUR_X.length; direction++) {
                int x = pixel % width + PixelImage.NEIGHBOUR_X[direction];
                int y = pixel / width + PixelImage.NEIGHBOUR_Y[direction];
                int neighbour = y * width + x;
                if (x >= 0 && y >= 0 && x < width && y < height && !edge[neighbour] && deepInside[neighbour]
                        && magnitude[neighbour] >= low) {
                    edge[neighbour] = true;
                    frontier[top++] = neighbour;
                }
            }
        }
        PixelImage edges = new PixelImage(width, height);
        for (int pixel = 0; pixel < edge.length; pixel++) {
            edges.pixels[pixel] = edge[pixel] ? PixelImage.OPAQUE : PixelImage.OPAQUE | PixelImage.RGB_MASK;
        }
        return edges;
    }
}
