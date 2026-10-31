package unit.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.graphics.image.PixelImage;

/**
 * {@link PixelImage#outlines} traces each blob's outer boundary once, closed and simplified, skips
 * blobs under the minimum area and never traces a hole: what multiview --outline reports per cell.
 */
class PixelImageOutlinesTest {

    private static final int SIZE = 160;

    private static final int BACKGROUND = 0xFFF5EFE3;

    private static final int SURFACE = 0xFF7C7B7D;

    private static final int DISK_CENTER = 50;

    private static final int DISK_RADIUS = 30;

    private static final int RING_CENTER = 115;

    private static final int RING_OUTER = 25;

    private static final int RING_INNER = 12;

    private static final int SPECK = 150;

    private static final int COLOUR_TOLERANCE = 24;

    private static final float SIMPLIFY_PIXELS = 1.5f;

    private static final int MINIMUM_AREA = 16;

    private static final double RADIUS_SLACK = 2.5;

    @Test
    void diskAndRingAreTracedOnceEachAndTheSpeckIsDropped() {
        PixelImage image = new PixelImage(SIZE, SIZE);
        image.fill(BACKGROUND);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double disk = Math.hypot(x - DISK_CENTER, y - DISK_CENTER);
                double ring = Math.hypot(x - RING_CENTER, y - RING_CENTER);
                if (disk <= DISK_RADIUS || ring <= RING_OUTER && ring >= RING_INNER) {
                    image.set(x, y, SURFACE);
                }
            }
        }
        image.set(SPECK, SPECK, SURFACE);
        image.set(SPECK + 1, SPECK, SURFACE);

        List<int[]> outlines = image.outlines(BACKGROUND, COLOUR_TOLERANCE, SIMPLIFY_PIXELS, MINIMUM_AREA);

        assertEquals(2, outlines.size());
        int[][] circles = { { DISK_CENTER, DISK_RADIUS }, { RING_CENTER, RING_OUTER } };
        for (int blob = 0; blob < 2; blob++) {
            int[] outline = outlines.get(blob);
            int center = circles[blob][0];
            int radius = circles[blob][1];
            assertEquals(outline[0], outline[outline.length - 2], "outline " + blob + " is not closed");
            assertEquals(outline[1], outline[outline.length - 1], "outline " + blob + " is not closed");
            assertTrue(outline.length / 2 > 8 && outline.length / 2 < radius * 2,
                    "outline " + blob + " kept " + outline.length / 2 + " points");
            int minimumX = Integer.MAX_VALUE;
            int maximumX = Integer.MIN_VALUE;
            for (int index = 0; index < outline.length; index += 2) {
                double distance = Math.hypot(outline[index] - center, outline[index + 1] - center);
                assertTrue(Math.abs(distance - radius) < RADIUS_SLACK,
                        "outline " + blob + " point at " + distance + " from a radius " + radius + " circle");
                minimumX = Math.min(minimumX, outline[index]);
                maximumX = Math.max(maximumX, outline[index]);
            }
            assertTrue(Math.abs(center - radius - minimumX) <= 1, "outline " + blob + " misses its left edge");
            assertTrue(Math.abs(center + radius - maximumX) <= 1, "outline " + blob + " misses its right edge");
        }
    }
}
