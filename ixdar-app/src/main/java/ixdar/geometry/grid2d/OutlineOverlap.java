package ixdar.geometry.grid2d;

import java.util.Arrays;

/**
 * How well a silhouette matches a traced outline on one pixel grid: their overlap, how far the
 * trace's boundary lies from the silhouette's, and the area the silhouette falls short of or
 * overdraws.
 */
public final class OutlineOverlap {

    /** Intersection over union of the two masks, 0 when both are empty. */
    public float intersectionOverUnion;

    /** Mean distance in pixels from the trace's boundary pixels to the silhouette's boundary. */
    public float meanPixels;

    /** Largest distance in pixels from a trace boundary pixel to the silhouette's boundary. */
    public float maxPixels;

    /** Pixels inside the trace the silhouette leaves empty. */
    public int shortPixels;

    /** Pixels the silhouette covers outside the trace. */
    public int overPixels;

    /**
     * Measure a silhouette against a trace, filling every field.
     *
     * @param trace  the traced outline's interior, row by row
     * @param shape  the silhouette's interior, on the same grid
     * @param width  pixels per row
     * @param height rows
     * @return this, measured
     */
    public OutlineOverlap measure(boolean[] trace, boolean[] shape, int width, int height) {
        int both = 0;
        int either = 0;
        shortPixels = 0;
        overPixels = 0;
        boolean[] shapeBoundary = new boolean[shape.length];
        boolean[] traceBoundary = new boolean[trace.length];
        boolean[] seen = new boolean[2 * 2];
        for (int pixel = 0; pixel < shape.length; pixel++) {
            both += trace[pixel] && shape[pixel] ? 1 : 0;
            either += trace[pixel] || shape[pixel] ? 1 : 0;
            shortPixels += trace[pixel] && !shape[pixel] ? 1 : 0;
            overPixels += !trace[pixel] && shape[pixel] ? 1 : 0;
            // A boundary pixel is one whose 3x3 neighbourhood holds both sides (the morphological
            // gradient), a band two pixels wide straddling the edge.
            int x = pixel % width;
            int y = pixel / width;
            Arrays.fill(seen, false);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int neighbourX = Math.min(width - 1, Math.max(0, x + dx));
                    int neighbourY = Math.min(height - 1, Math.max(0, y + dy));
                    int neighbour = neighbourY * width + neighbourX;
                    seen[shape[neighbour] ? 1 : 0] = true;
                    seen[trace[neighbour] ? 3 : 2] = true;
                }
            }
            shapeBoundary[pixel] = seen[0] && seen[1];
            traceBoundary[pixel] = seen[2] && seen[3];
        }
        intersectionOverUnion = either == 0 ? 0f : both / (float) either;
        float[] toShape = DistanceTransform.toFeature(shapeBoundary, width, height);
        double total = 0.0;
        int count = 0;
        maxPixels = 0f;
        for (int pixel = 0; pixel < trace.length; pixel++) {
            if (traceBoundary[pixel]) {
                total += toShape[pixel];
                count++;
                maxPixels = Math.max(maxPixels, toShape[pixel]);
            }
        }
        meanPixels = count == 0 ? 0f : (float) (total / count);
        return this;
    }
}
