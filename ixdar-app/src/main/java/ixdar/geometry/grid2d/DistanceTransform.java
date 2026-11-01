package ixdar.geometry.grid2d;

/**
 * Exact Euclidean distance transform of a pixel grid: every pixel's distance to the nearest feature
 * pixel, by Felzenszwalb and Huttenlocher's lower envelope of parabolas (Theory of Computing 2012),
 * run along rows and then columns.
 */
public final class DistanceTransform {

    private DistanceTransform() {
    }

    /**
     * Each pixel's distance to the nearest feature pixel.
     *
     * @param feature feature flag per pixel, row by row
     * @param width   pixels per row
     * @param height  rows
     * @return distance in pixels per pixel, longer than the grid's diagonal everywhere when nothing is
     *         a feature
     */
    public static float[] toFeature(boolean[] feature, int width, int height) {
        // Far must stay small enough that adding a squared offset to it keeps its precision.
        double far = 2.0 * ((double) width * width + (double) height * height) + 1.0;
        double[] squared = new double[width * height];
        for (int pixel = 0; pixel < squared.length; pixel++) {
            squared[pixel] = feature[pixel] ? 0.0 : far;
        }
        int longest = Math.max(width, height);
        double[] line = new double[longest];
        double[] envelope = new double[longest];
        int[] apex = new int[longest];
        double[] bound = new double[longest + 1];
        for (int pass = 0; pass < 2; pass++) {
            int lines = pass == 0 ? height : width;
            int length = pass == 0 ? width : height;
            int stride = pass == 0 ? 1 : width;
            for (int lineIndex = 0; lineIndex < lines; lineIndex++) {
                int first = pass == 0 ? lineIndex * width : lineIndex;
                for (int position = 0; position < length; position++) {
                    line[position] = squared[first + position * stride];
                }
                int parabolas = 0;
                apex[0] = 0;
                bound[0] = Double.NEGATIVE_INFINITY;
                bound[1] = Double.POSITIVE_INFINITY;
                for (int position = 1; position < length; position++) {
                    double crossing;
                    do {
                        int previous = apex[parabolas];
                        crossing = (line[position] + (double) position * position - line[previous]
                                - (double) previous * previous) / (2.0 * (position - previous));
                    } while (crossing <= bound[parabolas] && --parabolas >= 0);
                    parabolas++;
                    apex[parabolas] = position;
                    bound[parabolas] = crossing;
                    bound[parabolas + 1] = Double.POSITIVE_INFINITY;
                }
                int parabola = 0;
                for (int position = 0; position < length; position++) {
                    while (bound[parabola + 1] < position) {
                        parabola++;
                    }
                    double offset = position - apex[parabola];
                    envelope[position] = offset * offset + line[apex[parabola]];
                }
                for (int position = 0; position < length; position++) {
                    squared[first + position * stride] = envelope[position];
                }
            }
        }
        float[] distance = new float[squared.length];
        for (int pixel = 0; pixel < distance.length; pixel++) {
            distance[pixel] = (float) Math.sqrt(squared[pixel]);
        }
        return distance;
    }

    /**
     * A mask's signed distance: positive inside, the distance to the nearest outside pixel, and
     * negative outside, minus the distance to the nearest inside pixel.
     *
     * @param inside mask per pixel, row by row
     * @param width  pixels per row
     * @param height rows
     * @return signed distance in pixels per pixel
     */
    public static float[] signed(boolean[] inside, int width, int height) {
        boolean[] outside = new boolean[inside.length];
        for (int pixel = 0; pixel < inside.length; pixel++) {
            outside[pixel] = !inside[pixel];
        }
        float[] toOutside = toFeature(outside, width, height);
        float[] toInside = toFeature(inside, width, height);
        for (int pixel = 0; pixel < inside.length; pixel++) {
            toOutside[pixel] = inside[pixel] ? toOutside[pixel] : -toInside[pixel];
        }
        return toOutside;
    }
}
