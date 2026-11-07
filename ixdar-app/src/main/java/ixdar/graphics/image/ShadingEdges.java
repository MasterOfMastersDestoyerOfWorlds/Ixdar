package ixdar.graphics.image;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ixdar.geometry.mesh.data.bundle.CurveGeometry;

/**
 * The shading of a picture's subject read as sculpt marks: every luminance edge inside it as one
 * thin polyline with the side that is lit, its width and its contrast, and every dark region as an
 * outline with its darkness. Reference-strokes turns these into ridge, valley and fill strokes.
 */
public final class ShadingEdges {

    public static final float BLUR_SIGMA = 1.2f;

    public static final float EDGE_HIGH = 5f;

    public static final float EDGE_LOW = 2f;

    public static final int OUTLINE_MARGIN = 6;

    public static final int MINIMUM_LINE_PIXELS = 6;

    public static final float SIMPLIFY_PIXELS = 1f;

    public static final float PROFILE_STEP = 0.5f;

    public static final float PROFILE_REACH = 16f;

    public static final float PROFILE_FLAT_FRACTION = 0.25f;

    public static final float LIT_PERCENTILE = 0.95f;

    public static final float CONTOUR_INSET = 4f;

    public static final float DARK_FRACTION = 0.45f;

    public static final int MINIMUM_REGION_PIXELS = 24;

    public static final float REGION_SIMPLIFY_PIXELS = 1.5f;

    public static final float LIGHT_MINIMUM = 0.1f;

    public static final float SIGN_MINIMUM_COSINE = 0.3f;

    public static final float SOBEL_NORMALISER = 8f;

    public static final float BAND_REACH = 1.5f;

    public static final float BAND_RETURN = 0.75f;

    public static final int BLOB_GRID_ROWS = 200;

    public static final int BLOB_SCALES = 16;

    public static final int BLOB_SURROUND_SCALES = 5;

    public static final float BLOB_SCALE_RATIO = 1.25f;

    public static final float BLOB_SMALLEST_SHARE = 0.012f;

    public static final float BLOB_SOLID_SHARE = 0.5f;

    public static final float BLOB_CONTRAST = 0.06f;

    public static final float BLOB_REGION_SHARE = 0.3f;

    public static final float BLOB_REACH = 3f;

    public static final float BLOB_RIM_REACH = 5f;

    public static final float BLOB_RIM_GROWTH = 3f;

    public static final float LUMINANCE_CHANNELS = 3f;

    /** Each edge as a polyline in picture pixels, packed xy, along its pixels. */
    public final List<float[]> lines = new ArrayList<>();

    /** Each line's luminance transition width across it in pixels, the median along it. */
    public float[] widthByLine = new float[0];

    /** Each line's luminance step across it over the lit luminance, the median along it. */
    public float[] contrastByLine = new float[0];

    /** Whether each line is a ridge: its brighter side faces the light. A valley otherwise. */
    public boolean[] ridgeByLine = new boolean[0];

    /** Each dark region's outer boundary in picture pixels, packed xy, closed. */
    public final List<float[]> regions = new ArrayList<>();

    /**
     * Each region's darkness over the lit luminance: a dark patch's mean, one minus its luminance; a
     * blob's, how much darker its centre is than the surround its scale measures.
     */
    public float[] darknessByRegion = new float[0];

    /** Each region's pixel count. */
    public int[] areaByRegion = new int[0];

    /** The pixels the dark blobs' fills cover, row by row. */
    public boolean[] hollow = new boolean[0];

    /** Whether each region is a dark blob of the luminance's scale space rather than a dark patch. */
    public boolean[] blobByRegion = new boolean[0];

    /** The light's direction across the picture (x right, y down) over the lit luminance; 0 when it shines along the view. */
    public final float[] light = new float[2];

    /** The subject's lit luminance, the {@link #LIT_PERCENTILE} of its blurred luminance. */
    public float litLuminance;

    /** The blurred luminance, row by row. */
    public float[] luminance = new float[0];

    /** The picture's width. */
    public int width;

    /** The picture's height. */
    public int height;

    /**
     * Read a picture's shading inside its subject: thin edges linked into lines, each signed by the
     * light estimated from the outline's rim, and the dark regions.
     *
     * @param picture the picture
     * @param inside  the subject's interior per pixel, row by row
     * @return the edges and regions
     */
    public static ShadingEdges of(PixelImage picture, boolean[] inside) {
        ShadingEdges shading = new ShadingEdges();
        int width = picture.width;
        int height = picture.height;
        shading.width = width;
        shading.height = height;
        float[] raw = new float[width * height];
        for (int pixel = 0; pixel < raw.length; pixel++) {
            int sample = picture.pixels[pixel];
            raw[pixel] = ((sample >> PixelImage.RED_SHIFT & PixelImage.CHANNEL_MASK)
                    + (sample >> PixelImage.CHANNEL_BITS & PixelImage.CHANNEL_MASK) + (sample & PixelImage.CHANNEL_MASK))
                    / LUMINANCE_CHANNELS;
        }
        // A Gaussian blur, one pass across and one down; then the pixels well inside the outline,
        // shrunk the same way, since the outline's own step is the silhouette, not shading.
        int reach = (int) Math.ceil(BLUR_SIGMA * 3f);
        float[] kernel = new float[reach * 2 + 1];
        float total = 0f;
        for (int offset = -reach; offset <= reach; offset++) {
            kernel[offset + reach] = (float) Math.exp(-offset * offset / (2.0 * BLUR_SIGMA * BLUR_SIGMA));
            total += kernel[offset + reach];
        }
        float[] across = new float[raw.length];
        float[] luminance = new float[raw.length];
        boolean[] rows = new boolean[raw.length];
        boolean[] interior = new boolean[raw.length];
        for (int pass = 0; pass < 2; pass++) {
            for (int pixel = 0; pixel < raw.length; pixel++) {
                int x = pixel % width;
                int y = pixel / width;
                float sum = 0f;
                boolean kept = true;
                for (int offset = -Math.max(reach, OUTLINE_MARGIN); offset <= Math.max(reach, OUTLINE_MARGIN); offset++) {
                    int column = pass == 0 ? x + offset : x;
                    int row = pass == 0 ? y : y + offset;
                    boolean within = column >= 0 && row >= 0 && column < width && row < height;
                    int at = Math.max(0, Math.min(height - 1, row)) * width + Math.max(0, Math.min(width - 1, column));
                    sum += Math.abs(offset) <= reach ? (pass == 0 ? raw : across)[at] * kernel[offset + reach] : 0f;
                    kept &= Math.abs(offset) > OUTLINE_MARGIN || within && (pass == 0 ? inside : rows)[at];
                }
                (pass == 0 ? across : luminance)[pixel] = sum / total;
                (pass == 0 ? rows : interior)[pixel] = kept;
            }
        }
        shading.luminance = luminance;
        float[] litSamples = new float[raw.length];
        int litCount = 0;
        for (int pixel = 0; pixel < raw.length; pixel++) {
            litSamples[litCount] = luminance[pixel];
            litCount += interior[pixel] ? 1 : 0;
        }
        Arrays.sort(litSamples, 0, litCount);
        shading.litLuminance = litCount == 0 ? 1f : Math.max(1f, litSamples[(int) (LIT_PERCENTILE * (litCount - 1))]);
        shading.estimateLight(inside);
        float[] gradientX = new float[raw.length];
        float[] gradientY = new float[raw.length];
        float[] magnitude = new float[raw.length];
        for (int pixel = 0; pixel < raw.length; pixel++) {
            int x = pixel % width;
            int y = pixel / width;
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                continue;
            }
            gradientX[pixel] = (luminance[pixel - width + 1] + 2f * luminance[pixel + 1] + luminance[pixel + width + 1]
                    - luminance[pixel - width - 1] - 2f * luminance[pixel - 1] - luminance[pixel + width - 1])
                    / SOBEL_NORMALISER;
            gradientY[pixel] = (luminance[pixel + width - 1] + 2f * luminance[pixel + width] + luminance[pixel + width + 1]
                    - luminance[pixel - width - 1] - 2f * luminance[pixel - width] - luminance[pixel - width + 1])
                    / SOBEL_NORMALISER;
            magnitude[pixel] = (float) Math.hypot(gradientX[pixel], gradientY[pixel]);
        }
        // Canny: keep only the crest of each edge across it, so an edge is one pixel wide and links
        // into one line, not a band whose boundary runs out along one side and back along the other.
        boolean[] crest = new boolean[raw.length];
        for (int pixel = 0; pixel < raw.length; pixel++) {
            if (!interior[pixel] || magnitude[pixel] < EDGE_LOW) {
                continue;
            }
            float stepX = gradientX[pixel] / magnitude[pixel];
            float stepY = gradientY[pixel] / magnitude[pixel];
            float x = pixel % width;
            float y = pixel / width;
            crest[pixel] = magnitude[pixel] >= sample(magnitude, width, height, x + stepX, y + stepY)
                    && magnitude[pixel] > sample(magnitude, width, height, x - stepX, y - stepY);
        }
        boolean[] edge = new boolean[raw.length];
        int[] frontier = new int[raw.length];
        int top = 0;
        for (int pixel = 0; pixel < raw.length; pixel++) {
            if (crest[pixel] && magnitude[pixel] >= EDGE_HIGH) {
                edge[pixel] = true;
                frontier[top++] = pixel;
            }
        }
        while (top > 0) {
            int pixel = frontier[--top];
            for (int direction = 0; direction < PixelImage.NEIGHBOUR_X.length; direction++) {
                int x = pixel % width + PixelImage.NEIGHBOUR_X[direction];
                int y = pixel / width + PixelImage.NEIGHBOUR_Y[direction];
                if (x >= 0 && y >= 0 && x < width && y < height && crest[y * width + x] && !edge[y * width + x]) {
                    edge[y * width + x] = true;
                    frontier[top++] = y * width + x;
                }
            }
        }
        shading.linkLines(edge, gradientX, gradientY, magnitude);
        shading.hollow = new boolean[raw.length];
        shading.findDarkBlobs(interior, edge);
        shading.findDarkRegions(interior);
        return shading;
    }

    /**
     * The light's direction across the picture from the subject's rim, where the surface faces
     * out along the outline: the rim's luminance fitted by least squares to a + b . outward normal.
     *
     * @param inside the subject's interior per pixel
     */
    private void estimateLight(boolean[] inside) {
        double[][] normal = new double[3][3];
        double[] right = new double[3];
        for (int pixel = 0; pixel < inside.length; pixel++) {
            int x = pixel % width;
            int y = pixel / width;
            if (!inside[pixel] || x == 0 || y == 0 || x == width - 1 || y == height - 1
                    || inside[pixel - 1] && inside[pixel + 1] && inside[pixel - width] && inside[pixel + width]) {
                continue;
            }
            // The outward normal from the mask's own gradient over a small window.
            float outX = 0f;
            float outY = 0f;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int column = Math.max(0, Math.min(width - 1, x + dx));
                    int row = Math.max(0, Math.min(height - 1, y + dy));
                    outX -= inside[row * width + column] ? dx : 0;
                    outY -= inside[row * width + column] ? dy : 0;
                }
            }
            float length = (float) Math.hypot(outX, outY);
            if (length == 0f) {
                continue;
            }
            outX /= length;
            outY /= length;
            float rim = sample(luminance, width, height, x - outX * CONTOUR_INSET, y - outY * CONTOUR_INSET);
            double[] row = { 1.0, outX, outY };
            for (int first = 0; first < 3; first++) {
                for (int second = 0; second < 3; second++) {
                    normal[first][second] += row[first] * row[second];
                }
                right[first] += row[first] * rim;
            }
        }
        for (int pivot = 0; pivot < 3; pivot++) {
            if (Math.abs(normal[pivot][pivot]) < Double.MIN_NORMAL) {
                return;
            }
            for (int other = 0; other < 3; other++) {
                double factor = other == pivot ? 0.0 : normal[other][pivot] / normal[pivot][pivot];
                for (int column = 0; column < 3; column++) {
                    normal[other][column] -= factor * normal[pivot][column];
                }
                right[other] -= factor * right[pivot];
            }
        }
        light[0] = (float) (right[1] / normal[1][1]) / litLuminance;
        light[1] = (float) (right[2] / normal[2][2]) / litLuminance;
    }

    /**
     * Link the edge pixels into lines, endpoints first so a line runs end to end, then what is left
     * (closed loops), each line taking the median width and contrast of its pixels and the sign its
     * pixels' lit sides vote for.
     *
     * @param edge      edge pixels, one pixel wide
     * @param gradientX luminance gradient across x
     * @param gradientY luminance gradient across y
     * @param magnitude the gradient's length
     */
    private void linkLines(boolean[] edge, float[] gradientX, float[] gradientY, float[] magnitude) {
        boolean[] used = new boolean[edge.length];
        int[] path = new int[edge.length];
        List<float[]> widths = new ArrayList<>();
        List<float[]> contrasts = new ArrayList<>();
        List<Float> votes = new ArrayList<>();
        float lightLength = (float) Math.hypot(light[0], light[1]);
        for (int pass = 0; pass < 2; pass++) {
            for (int start = 0; start < edge.length; start++) {
                int neighbours = 0;
                for (int direction = 0; direction < PixelImage.NEIGHBOUR_X.length && pass == 0; direction++) {
                    int x = start % width + PixelImage.NEIGHBOUR_X[direction];
                    int y = start / width + PixelImage.NEIGHBOUR_Y[direction];
                    neighbours += x >= 0 && y >= 0 && x < width && y < height && edge[y * width + x] ? 1 : 0;
                }
                if (!edge[start] || used[start] || pass == 0 && neighbours != 1) {
                    continue;
                }
                int length = 0;
                int current = start;
                int previous = -1;
                while (current >= 0) {
                    used[current] = true;
                    path[length++] = current;
                    int next = -1;
                    float straightest = -Float.MAX_VALUE;
                    for (int direction = 0; direction < PixelImage.NEIGHBOUR_X.length; direction++) {
                        int x = current % width + PixelImage.NEIGHBOUR_X[direction];
                        int y = current / width + PixelImage.NEIGHBOUR_Y[direction];
                        int neighbour = y * width + x;
                        if (x < 0 || y < 0 || x >= width || y >= height || !edge[neighbour] || used[neighbour]) {
                            continue;
                        }
                        // Continue the way the line was heading; a 4-neighbour before a diagonal.
                        float heading = previous < 0 ? 0f
                                : (current % width - previous % width) * PixelImage.NEIGHBOUR_X[direction]
                                        + (current / width - previous / width) * PixelImage.NEIGHBOUR_Y[direction];
                        heading -= Math.abs(PixelImage.NEIGHBOUR_X[direction] * PixelImage.NEIGHBOUR_Y[direction])
                                * PROFILE_STEP;
                        if (heading > straightest) {
                            straightest = heading;
                            next = neighbour;
                        }
                    }
                    previous = current;
                    current = next;
                }
                if (length < MINIMUM_LINE_PIXELS) {
                    continue;
                }
                float[] xy = new float[length * 2];
                float[] lineWidths = new float[length];
                float[] lineContrasts = new float[length];
                float vote = 0f;
                for (int index = 0; index < length; index++) {
                    int pixel = path[index];
                    xy[index * 2] = pixel % width;
                    xy[index * 2 + 1] = pixel / width;
                    float stepX = gradientX[pixel] / magnitude[pixel];
                    float stepY = gradientY[pixel] / magnitude[pixel];
                    // Walk across the edge each way until the gradient falls flat: the transition's
                    // width, and the luminance step between its ends.
                    float[] ends = new float[2];
                    for (int side = 0; side < 2; side++) {
                        float sign = side == 0 ? 1f : -1f;
                        float reach = PROFILE_STEP;
                        while (reach < PROFILE_REACH && sample(magnitude, width, height, xy[index * 2] + sign * stepX * reach,
                                xy[index * 2 + 1] + sign * stepY * reach) > PROFILE_FLAT_FRACTION * magnitude[pixel]) {
                            reach += PROFILE_STEP;
                        }
                        ends[side] = reach;
                    }
                    lineWidths[index] = ends[0] + ends[1];
                    lineContrasts[index] = (sample(luminance, width, height, xy[index * 2] + stepX * ends[0],
                            xy[index * 2 + 1] + stepY * ends[0]) - sample(luminance, width, height,
                                    xy[index * 2] - stepX * ends[1], xy[index * 2 + 1] - stepY * ends[1])) / litLuminance;
                    // The bright side toward a light across the picture is a convex turn. With the
                    // light along the view a thin dark band is a crease, a thin bright band a crest,
                    // and a plain step a convex shoulder rolling away from the eye.
                    float bright = sample(luminance, width, height, xy[index * 2] + stepX * ends[0],
                            xy[index * 2 + 1] + stepY * ends[0]);
                    float dark = sample(luminance, width, height, xy[index * 2] - stepX * ends[1],
                            xy[index * 2 + 1] - stepY * ends[1]);
                    float band = BAND_REACH * (ends[0] + ends[1]);
                    boolean brightBand = sample(luminance, width, height, xy[index * 2] + stepX * (ends[0] + band),
                            xy[index * 2 + 1] + stepY * (ends[0] + band)) < bright - BAND_RETURN * (bright - dark);
                    boolean darkBand = sample(luminance, width, height, xy[index * 2] - stepX * (ends[1] + band),
                            xy[index * 2 + 1] - stepY * (ends[1] + band)) > dark + BAND_RETURN * (bright - dark);
                    float facing = (stepX * light[0] + stepY * light[1]) / Math.max(LIGHT_MINIMUM, lightLength);
                    vote += lightLength >= LIGHT_MINIMUM ? (Math.abs(facing) < SIGN_MINIMUM_COSINE ? 0f : facing)
                            : darkBand && !brightBand ? -1f : 1f;
                }
                lines.add(CurveGeometry.planar(xy, false).simplified(SIMPLIFY_PIXELS).drawnPlanar(0));
                widths.add(lineWidths);
                contrasts.add(lineContrasts);
                votes.add(vote);
            }
        }
        widthByLine = new float[lines.size()];
        contrastByLine = new float[lines.size()];
        ridgeByLine = new boolean[lines.size()];
        for (int line = 0; line < lines.size(); line++) {
            float[] lineWidths = widths.get(line);
            float[] lineContrasts = contrasts.get(line);
            Arrays.sort(lineWidths);
            Arrays.sort(lineContrasts);
            widthByLine[line] = lineWidths[lineWidths.length / 2];
            contrastByLine[line] = lineContrasts[lineContrasts.length / 2];
            ridgeByLine[line] = votes.get(line) > 0f;
        }
    }

    /**
     * The dark regions inside the subject: 8-connected pixels darker than {@link #DARK_FRACTION} of
     * the lit luminance, each traced as an outline with its mean darkness and area.
     *
     * @param interior pixels well inside the outline
     */
    private void findDarkRegions(boolean[] interior) {
        PixelImage dark = new PixelImage(width, height);
        boolean[] isDark = new boolean[luminance.length];
        for (int pixel = 0; pixel < luminance.length; pixel++) {
            isDark[pixel] = interior[pixel] && luminance[pixel] < DARK_FRACTION * litLuminance;
            dark.pixels[pixel] = isDark[pixel] ? PixelImage.OPAQUE : PixelImage.OPAQUE | PixelImage.RGB_MASK;
        }
        // PixelImage.outlines traces blobs in raster order of their first pixel, as this labelling
        // visits them, so blob i here is outline i there.
        List<int[]> outlines = dark.outlines(PixelImage.OPAQUE | PixelImage.RGB_MASK, 0, REGION_SIMPLIFY_PIXELS, 1);
        int[] label = new int[luminance.length];
        int[] frontier = new int[luminance.length];
        int blobs = 0;
        for (int start = 0; start < luminance.length; start++) {
            if (!isDark[start] || label[start] != 0) {
                continue;
            }
            label[start] = ++blobs;
            int top = 0;
            frontier[top++] = start;
            int area = 0;
            int hollowed = 0;
            double sum = 0.0;
            // A dark patch reaching the outline is a side turned from the light, not a hollow: a
            // hollow is ringed by lit surface. One mostly inside a blob (a socket's shadowed roof) is
            // part of that blob's fill, not a second one.
            boolean turnedAway = false;
            while (top > 0) {
                int pixel = frontier[--top];
                area++;
                hollowed += hollow[pixel] ? 1 : 0;
                sum += 1.0 - luminance[pixel] / litLuminance;
                for (int direction = 0; direction < PixelImage.NEIGHBOUR_X.length; direction++) {
                    int x = pixel % width + PixelImage.NEIGHBOUR_X[direction];
                    int y = pixel / width + PixelImage.NEIGHBOUR_Y[direction];
                    turnedAway |= x < 0 || y < 0 || x >= width || y >= height || !interior[y * width + x];
                    if (x >= 0 && y >= 0 && x < width && y < height && isDark[y * width + x] && label[y * width + x] == 0) {
                        label[y * width + x] = blobs;
                        frontier[top++] = y * width + x;
                    }
                }
            }
            int[] outline = outlines.get(blobs - 1);
            if (!turnedAway && 2 * hollowed < area && area >= MINIMUM_REGION_PIXELS && outline.length >= 2 * 3) {
                addRegion(outline, 1f, 0f, 0f, (float) (sum / area), area, false);
            }
        }
    }

    /**
     * Append a region: its outline in some grid's cells, mapped to picture pixels, and its measures.
     *
     * @param outline   the outline in grid cells, packed xy
     * @param step      picture pixels per grid cell
     * @param offsetX   the picture x of grid cell 0's centre
     * @param offsetY   the picture y of grid cell 0's centre
     * @param darkness  the region's mean darkness
     * @param area      its picture pixel count
     * @param blob      whether it is a scale-space blob
     */
    private void addRegion(int[] outline, float step, float offsetX, float offsetY, float darkness, int area,
            boolean blob) {
        float[] xy = new float[outline.length];
        for (int index = 0; index < outline.length; index += 2) {
            xy[index] = offsetX + outline[index] * step;
            xy[index + 1] = offsetY + outline[index + 1] * step;
        }
        regions.add(xy);
        darknessByRegion = Arrays.copyOf(darknessByRegion, regions.size());
        areaByRegion = Arrays.copyOf(areaByRegion, regions.size());
        blobByRegion = Arrays.copyOf(blobByRegion, regions.size());
        darknessByRegion[regions.size() - 1] = darkness;
        areaByRegion[regions.size() - 1] = area;
        blobByRegion[regions.size() - 1] = blob;
    }

    /**
     * Hollows whose lit floor keeps them out of {@link #findDarkRegions} (an eye socket seen head-on):
     * dark blobs, scale-space maxima of centre against surround (after Lindeberg 1998), each grown over
     * what its scale darkens, then out to the edges walling it in.
     *
     * @param interior pixels well inside the outline
     * @param edge     edge pixels, one pixel wide
     */
    private void findDarkBlobs(boolean[] interior, boolean[] edge) {
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int pixel = 0; pixel < interior.length; pixel++) {
            if (interior[pixel]) {
                left = Math.min(left, pixel % width);
                right = Math.max(right, pixel % width);
                top = Math.min(top, pixel / width);
                bottom = Math.max(bottom, pixel / width);
            }
        }
        if (right < 0) {
            return;
        }
        // A coarse grid over the subject keeps the widest blurs cheap; the blobs it finds are wide.
        int step = Math.max(1, (int) Math.ceil((bottom - top + 1) / (float) BLOB_GRID_ROWS));
        int columns = (right - left) / step + 1;
        int rows = (bottom - top) / step + 1;
        int cells = columns * rows;
        float[] weighted = new float[cells];
        float[] weight = new float[cells];
        boolean[] walled = new boolean[cells];
        for (int pixel = 0; pixel < interior.length; pixel++) {
            if (interior[pixel]) {
                int cell = (pixel / width - top) / step * columns + (pixel % width - left) / step;
                weighted[cell] += luminance[pixel] / (step * step);
                weight[cell] += 1f / (step * step);
                walled[cell] |= edge[pixel];
            }
        }
        // Each blur is a normalized convolution over the subject alone, so the bright background
        // does not make every rim a dark blob.
        float[][] blurred = new float[BLOB_SCALES][];
        float[] sigmaByScale = new float[BLOB_SCALES];
        for (int scale = 0; scale < BLOB_SCALES; scale++) {
            sigmaByScale[scale] = BLOB_SMALLEST_SHARE * rows * (float) Math.pow(BLOB_SCALE_RATIO, scale);
            float[] numerator = gaussianBlur(weighted, columns, rows, sigmaByScale[scale]);
            float[] denominator = gaussianBlur(weight, columns, rows, sigmaByScale[scale]);
            blurred[scale] = new float[cells];
            for (int cell = 0; cell < cells; cell++) {
                blurred[scale][cell] = denominator[cell] > 0f ? numerator[cell] / denominator[cell] : 0f;
            }
        }
        // Centre against a surround about three times as wide: a socket's lit floor and shadowed
        // walls average darker than the brow and cheek around it, where a narrow band cancels out.
        float[][] difference = new float[BLOB_SCALES - BLOB_SURROUND_SCALES][cells];
        for (int scale = 0; scale < difference.length; scale++) {
            for (int cell = 0; cell < cells; cell++) {
                difference[scale][cell] = (blurred[scale + BLOB_SURROUND_SCALES][cell] - blurred[scale][cell])
                        / litLuminance;
            }
        }
        List<Integer> candidates = new ArrayList<>();
        for (int scale = 1; scale + 1 < difference.length; scale++) {
            for (int row = 1; row + 1 < rows; row++) {
                for (int column = 1; column + 1 < columns; column++) {
                    int cell = row * columns + column;
                    float value = difference[scale][cell];
                    boolean peak = weight[cell] >= BLOB_SOLID_SHARE && value >= BLOB_CONTRAST;
                    for (int neighbour = 0; neighbour < 3 * 3 * 3 && peak; neighbour++) {
                        int at = cell + (neighbour % 3 - 1) + (neighbour / 3 % 3 - 1) * columns;
                        peak = neighbour == 3 * 3 * 3 / 2 || difference[scale + neighbour / (3 * 3) - 1][at] < value;
                    }
                    if (peak) {
                        candidates.add(scale * cells + cell);
                    }
                }
            }
        }
        candidates.sort((first, second) -> Float.compare(difference[second / cells][second % cells],
                difference[first / cells][first % cells]));
        boolean[] claimed = new boolean[cells];
        int[] members = new int[cells];
        for (int candidate : candidates) {
            int scale = candidate / cells;
            int centre = candidate % cells;
            if (claimed[centre]) {
                continue;
            }
            float floor = BLOB_REGION_SHARE * difference[scale][centre];
            int count = 0;
            members[count++] = centre;
            claimed[centre] = true;
            boolean[] reachedOutline = new boolean[2];
            int darkCount = 0;
            // First the dark centre its scale measured; then on to the rim edges that wall it in, as a
            // socket's lit floor does not darken but its rim walls it off, unless that leaks away.
            for (int phase = 0; phase < 2; phase++) {
                float reach = (phase == 0 ? BLOB_REACH : BLOB_RIM_REACH) * sigmaByScale[scale];
                for (int next = 0; next < count; next++) {
                    int cell = members[next];
                    for (int direction = 0; direction < PixelImage.NEIGHBOUR_X.length; direction += 2) {
                        int column = cell % columns + PixelImage.NEIGHBOUR_X[direction];
                        int row = cell / columns + PixelImage.NEIGHBOUR_Y[direction];
                        int neighbour = row * columns + column;
                        if (Math.hypot(column - centre % columns, row - centre / columns) > reach) {
                            continue;
                        }
                        boolean inside = column >= 0 && row >= 0 && column < columns && row < rows
                                && weight[neighbour] >= BLOB_SOLID_SHARE;
                        reachedOutline[phase] |= !inside;
                        if (inside && !claimed[neighbour] && (phase == 0 ? difference[scale][neighbour] >= floor
                                : !walled[neighbour])) {
                            claimed[neighbour] = true;
                            members[count++] = neighbour;
                        }
                    }
                }
                darkCount = phase == 0 ? count : darkCount;
            }
            if (reachedOutline[1] || count > BLOB_RIM_GROWTH * darkCount) {
                for (int member = darkCount; member < count; member++) {
                    claimed[members[member]] = false;
                }
                count = darkCount;
            }
            if (reachedOutline[0] || count * step * step < MINIMUM_REGION_PIXELS || count < 3) {
                continue;
            }
            PixelImage mask = new PixelImage(columns, rows);
            mask.fill(PixelImage.OPAQUE | PixelImage.RGB_MASK);
            for (int member = 0; member < count; member++) {
                mask.pixels[members[member]] = PixelImage.OPAQUE;
            }
            addRegion(mask.outlines(PixelImage.OPAQUE | PixelImage.RGB_MASK, 0, 1f, 1).get(0), step,
                    left + step / 2f, top + step / 2f, difference[scale][centre], count * step * step, true);
            for (int pixel = 0; pixel < hollow.length; pixel++) {
                int cell = (pixel / width - top) / step * columns + (pixel % width - left) / step;
                hollow[pixel] |= interior[pixel] && claimed[cell] && mask.pixels[cell] == PixelImage.OPAQUE;
            }
        }
    }

    /**
     * A field blurred by a Gaussian, one pass across and one down, clamped at the border.
     *
     * @param field   the field, row by row
     * @param columns its width
     * @param rows    its height
     * @param sigma   the Gaussian's spread in cells
     * @return the blurred field
     */
    private static float[] gaussianBlur(float[] field, int columns, int rows, float sigma) {
        int reach = (int) Math.ceil(sigma * 3f);
        float[] kernel = new float[reach * 2 + 1];
        for (int offset = -reach; offset <= reach; offset++) {
            kernel[offset + reach] = (float) Math.exp(-offset * offset / (2.0 * sigma * sigma));
        }
        float[] across = new float[field.length];
        float[] blurred = new float[field.length];
        for (int pass = 0; pass < 2; pass++) {
            float[] from = pass == 0 ? field : across;
            float[] to = pass == 0 ? across : blurred;
            for (int cell = 0; cell < field.length; cell++) {
                int column = cell % columns;
                int row = cell / columns;
                float sum = 0f;
                for (int offset = -reach; offset <= reach; offset++) {
                    int at = pass == 0 ? row * columns + Math.max(0, Math.min(columns - 1, column + offset))
                            : Math.max(0, Math.min(rows - 1, row + offset)) * columns + column;
                    sum += from[at] * kernel[offset + reach];
                }
                to[cell] = sum;
            }
        }
        return blurred;
    }

    /**
     * A field's value between pixels, bilinear, clamped at the picture's border.
     *
     * @param field  the field, row by row
     * @param width  its width
     * @param height its height
     * @param x      column, fractional
     * @param y      row, fractional
     * @return the interpolated value
     */
    public static float sample(float[] field, int width, int height, float x, float y) {
        float clampedX = Math.max(0f, Math.min(width - 1f, x));
        float clampedY = Math.max(0f, Math.min(height - 1f, y));
        int left = Math.min(width - 2, (int) clampedX);
        int top = Math.min(height - 2, (int) clampedY);
        float across = clampedX - left;
        float down = clampedY - top;
        int at = top * width + left;
        return (field[at] * (1f - across) + field[at + 1] * across) * (1f - down)
                + (field[at + width] * (1f - across) + field[at + width + 1] * across) * down;
    }
}
