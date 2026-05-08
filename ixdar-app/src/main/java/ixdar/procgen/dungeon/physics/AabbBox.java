package ixdar.procgen.dungeon.physics;

/**
 * Axis-aligned bounding box, {@code min} corner at {@code (minX, minY, minZ)} and {@code max}
 * corner at {@code (maxX, maxY, maxZ)}. Used as the obstacle primitive for the dungeon's
 * tile-grid solid cells.
 */
public record AabbBox(float minX, float minY, float minZ,
                      float maxX, float maxY, float maxZ) {
    public static final String STR = ",";
    public static final float NUM_0_5 = 0.5f;

    /**
     * Validates that {@code max} is greater-than-or-equal-to {@code min} on every axis.
     *
     * @throws IllegalArgumentException if any {@code max} component is strictly less than its
     *     corresponding {@code min} component
     */
    public AabbBox {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException(
                    "AabbBox max must be >= min on every axis, got min=("
                            + minX + STR + minY + STR + minZ + ") max=("
                            + maxX + STR + maxY + STR + maxZ + ")");
        }
    }

    /**
     * Midpoint of the X extent.
     *
     * @return {@code (minX + maxX) / 2}
     */
    public float centerX() { return (minX + maxX) * NUM_0_5; }
    /**
     * Midpoint of the Y extent.
     *
     * @return {@code (minY + maxY) / 2}
     */
    public float centerY() { return (minY + maxY) * NUM_0_5; }
    /**
     * Midpoint of the Z extent.
     *
     * @return {@code (minZ + maxZ) / 2}
     */
    public float centerZ() { return (minZ + maxZ) * NUM_0_5; }
}
