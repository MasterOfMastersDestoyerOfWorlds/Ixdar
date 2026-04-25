package ixdar.procgen.dungeon.physics;

/**
 * Axis-aligned bounding box, {@code min} corner at {@code (minX, minY, minZ)} and {@code max}
 * corner at {@code (maxX, maxY, maxZ)}. Used as the obstacle primitive for the dungeon's
 * tile-grid solid cells.
 */
public record AabbBox(float minX, float minY, float minZ,
                      float maxX, float maxY, float maxZ) {

    public AabbBox {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException(
                    "AabbBox max must be >= min on every axis, got min=("
                            + minX + "," + minY + "," + minZ + ") max=("
                            + maxX + "," + maxY + "," + maxZ + ")");
        }
    }

    public float centerX() { return (minX + maxX) * 0.5f; }
    public float centerY() { return (minY + maxY) * 0.5f; }
    public float centerZ() { return (minZ + maxZ) * 0.5f; }
}
