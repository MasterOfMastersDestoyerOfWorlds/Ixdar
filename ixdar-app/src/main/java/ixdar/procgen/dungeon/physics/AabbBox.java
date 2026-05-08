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
     * TODO: document {@code AabbBox}.
     *
     * @throws IllegalArgumentException TODO: describe
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
     * TODO: document {@code centerX}.
     *
     * @return TODO: describe
     */
    public float centerX() { return (minX + maxX) * NUM_0_5; }
    /**
     * TODO: document {@code centerY}.
     *
     * @return TODO: describe
     */
    public float centerY() { return (minY + maxY) * NUM_0_5; }
    /**
     * TODO: document {@code centerZ}.
     *
     * @return TODO: describe
     */
    public float centerZ() { return (minZ + maxZ) * NUM_0_5; }
}
