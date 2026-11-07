package ixdar.graphics.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.platform.Platforms;
import ixdar.platform.gl.DecodedImage;

/**
 * The subject of a picture: the largest blob standing out from a background colour, as its outer
 * boundary in picture pixels and the bounding box of that boundary. What the stroke-sculpt tracing
 * commands read out of a reference picture or a capture's cell.
 */
public final class SubjectOutline {

    public static final int BOX_FIELDS = 4;

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
        subject.outline = simplify > 0f ? CurveGeometry.planarLoop(xy).simplified(simplify).drawnPlanar(0) : xy;
        return subject;
    }
}
