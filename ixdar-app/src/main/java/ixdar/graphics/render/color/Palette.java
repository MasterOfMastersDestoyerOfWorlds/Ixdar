package ixdar.graphics.render.color;

/**
 * An ordered set of colours a tool cycles through by number: ring, region or patch ids pick an
 * entry, wrapping around past the end.
 */
public final class Palette {

    public static final float OVERFLOW_SHADE = 0.55f;

    public final Color[] colors;

    /**
     * A palette of the given colours, in the order ids take them.
     *
     * @param colors one or more entries
     */
    public Palette(Color... colors) {
        this.colors = colors.clone();
    }

    /**
     * How many entries the palette holds.
     *
     * @return the entry count
     */
    public int size() {
        return colors.length;
    }

    /**
     * The entry an id takes, wrapping around: id {@code size()} takes entry 0 again.
     *
     * @param index any id, negative ones wrap from the end
     * @return the entry
     */
    public Color color(int index) {
        return colors[Math.floorMod(index, colors.length)];
    }

    /**
     * The entry an id takes, each further round past the palette a darker shade of it, so an id
     * past the end never repeats an earlier id's colour exactly.
     *
     * @param index id, 0 or more
     * @return the entry, scaled by {@link #OVERFLOW_SHADE} once per completed round
     */
    public Color shadedColor(int index) {
        return color(index).shaded((float) Math.pow(OVERFLOW_SHADE, index / colors.length));
    }

    /**
     * The smallest RGB distance between any two entries: how alike the two closest entries read.
     *
     * @return the minimum pairwise {@link Color#distanceTo distance}, infinite for one entry
     */
    public double minimumDistance() {
        double minimum = Double.POSITIVE_INFINITY;
        for (int first = 0; first < colors.length; first++) {
            for (int second = first + 1; second < colors.length; second++) {
                minimum = Math.min(minimum, colors[first].distanceTo(colors[second]));
            }
        }
        return minimum;
    }
}
