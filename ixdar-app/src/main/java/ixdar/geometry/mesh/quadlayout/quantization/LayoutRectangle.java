package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.List;

/**
 * One positive-area cell of the quantized layout complex. Sides are stored in
 * canonical orientation: side 0 and side 2 both run in the same parametric
 * direction (cycle order for sides 0 and 1, reversed cycle order for sides 2
 * and 3), so a column offset measured from a side's canonical start names the
 * same iso-line on the opposite side. Width is the quantized sum of sides
 * 0/2, height of sides 1/3; eq. (2) consistency guarantees opposite sides
 * agree.
 */
public final class LayoutRectangle {

    public static final int SIDES = 4;

    public final int rectangleId;

    /** Originating valid T-mesh patch id; split products inherit it. */
    public final int rootPatchId;

    public final int width;
    public final int height;

    /** Positive segments per side in canonical order. */
    public final List<List<LayoutSideSegment>> sideSegments = new ArrayList<>(SIDES);

    /**
     * Collapse-cluster id at every segment boundary per side, size
     * {@code sideSegments.get(s).size() + 1} (first and last entries are the
     * side's corner clusters). Synthetic boundaries created by splits use
     * cluster ids at or above the collapse's cluster count.
     */
    public final List<List<Integer>> boundaryClusters = new ArrayList<>(SIDES);

    /** False once this rectangle has been split into two halves. */
    public boolean alive = true;

    /**
     * Creates an empty rectangle shell; the builder fills sides afterwards.
     *
     * @param rectangleId unique rectangle id
     * @param rootPatchId originating valid T-mesh patch id
     * @param width       quantized width (sides 0/2 sum)
     * @param height      quantized height (sides 1/3 sum)
     */
    public LayoutRectangle(int rectangleId, int rootPatchId, int width, int height) {
        this.rectangleId = rectangleId;
        this.rootPatchId = rootPatchId;
        this.width = width;
        this.height = height;
        for (int side = 0; side < SIDES; side++) {
            sideSegments.add(new ArrayList<>());
            boundaryClusters.add(new ArrayList<>());
        }
    }

    /**
     * Quantized extent of one side (width for sides 0/2, height for 1/3).
     *
     * @param side side index 0..3
     * @return quantized side extent
     */
    public int sideExtent(int side) {
        return side % 2 == 0 ? width : height;
    }
}
