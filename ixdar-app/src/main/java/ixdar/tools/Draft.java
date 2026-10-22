package ixdar.tools;

/**
 * What a {@link Tool} is building and has not committed: an {@link Item} plus the points it is
 * built through, each with a label, which of them is selected and which hovered, whether it would
 * commit as it stands, and the item it was reopened from.
 */
public final class Draft extends Item {

    public static final int COORDINATES_PER_POINT = 3;

    /** The points the draft is built through, packed xyz, in the tool's order. */
    public float[] points = new float[0];

    /** A label for each point, parallel to {@link #points}' triples. */
    public String[] pointLabels = new String[0];

    /** Whether committing now would succeed. */
    public boolean valid;

    /** Index of the selected point, which Delete and a drag act on, or -1. */
    public int selectedPoint = -1;

    /** Index of the point under the cursor, or -1. */
    public int hoveredPoint = -1;

    /** The item the draft was reopened from, which discarding it restores, or {@code null}. */
    public Item reopened;
}
