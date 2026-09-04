package ixdar.graphics.render.model;

/**
 * Interleaved float vertex format: one shader attribute location and float count per
 * attribute, packed in declaration order with no padding.
 */
public final class VertexLayout {

    public final int[] locations;
    public final int[] sizes;
    public final int floatsPerVertex;

    /**
     * A format with the given attributes.
     *
     * @param locations shader attribute location per attribute
     * @param sizes     floats per attribute, parallel to {@code locations}
     */
    public VertexLayout(int[] locations, int[] sizes) {
        this.locations = locations;
        this.sizes = sizes;
        int total = 0;
        for (int size : sizes) {
            total += size;
        }
        this.floatsPerVertex = total;
    }
}
