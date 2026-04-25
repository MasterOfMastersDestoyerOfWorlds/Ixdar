package ixdar.procgen.dungeon.physics;

import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.TileGridValue3D;

/**
 * Resolves a capsule's desired motion against a {@link TileGridValue3D} of tile-grid solid cells.
 * Empty cells (per {@link CellType#EMPTY}) and out-of-grid cells are obstacles; any other cell
 * type ({@link CellType#ROOM}, {@link CellType#HALLWAY}, {@link CellType#STAIR_UP},
 * {@link CellType#STAIR_DOWN}) is walkable interior — the capsule can pass freely through them.
 *
 * <p>Algorithm:
 * <ul>
 *   <li>Sub-step the desired delta into pieces small enough to avoid tunneling
 *       (each piece &lt;= {@code radius * 0.5}).</li>
 *   <li>After applying each piece, query the grid cells that the capsule's bounding sphere can
 *       overlap and accumulate MTVs from {@link CapsuleAabbTest#penetration} on each obstacle
 *       cell. Apply, repeat up to a fixed iteration cap (4) until residual penetration is
 *       negligible.</li>
 *   <li>Return the resolved end position. Any component of the original delta that pointed
 *       into a wall is consumed by the MTV; the perpendicular components survive (slide).</li>
 * </ul>
 *
 * <p>The grid is centered at the world origin per {@code GridToMesh3D}'s convention:
 * cell {@code (x, y, z)} occupies world AABB {@code [offsetX + x*cs, offsetX + (x+1)*cs] x
 * [offsetY + y*cs, offsetY + (y+1)*cs] x [offsetZ + z*cs, offsetZ + (z+1)*cs]} where
 * {@code offsetX = -gridW * cs / 2} (and analogously for Y, Z).
 */
public final class CapsuleMover {

    /** Maximum collision-resolution iterations per sub-step. */
    public static final int MAX_RESOLVE_ITERATIONS = 4;

    private CapsuleMover() {
    }

    /**
     * @param capsuleAtStart capsule at its starting position (its center is the start point)
     * @param delta          desired motion of the capsule's center
     * @param grid           static obstacle grid; EMPTY cells are solid
     * @param cellSize       world units per cell (matches {@code GridToMesh3D}'s cellSize)
     * @return the resolved end position of the capsule's center
     */
    public static Vec3f moveAndSlide(CapsuleShape capsuleAtStart,
                                     Vec3f delta,
                                     TileGridValue3D grid,
                                     float cellSize) {
        if (cellSize <= 0f) {
            throw new IllegalArgumentException("cellSize must be > 0, got " + cellSize);
        }
        // Sub-stepping based on the smaller of the capsule's body height and radius keeps the
        // capsule from tunneling through thin walls within one frame at high speed.
        float deltaLen = delta.length();
        float maxStep = capsuleAtStart.radius() * 0.5f;
        int substeps = Math.max(1, (int) Math.ceil(deltaLen / maxStep));
        Vec3f stepDelta = delta.scale(1f / substeps);

        Vec3f pos = capsuleAtStart.center();
        for (int s = 0; s < substeps; s++) {
            pos = pos.add(stepDelta);
            pos = resolve(capsuleAtStart, pos, grid, cellSize);
        }
        return pos;
    }

    /** Iterative MTV accumulation — pushes the capsule out of any obstacle cells it overlaps. */
    private static Vec3f resolve(CapsuleShape proto, Vec3f pos, TileGridValue3D grid, float cellSize) {
        float offsetX = -grid.width() * cellSize * 0.5f;
        float offsetY = -grid.height() * cellSize * 0.5f;
        float offsetZ = -grid.depth() * cellSize * 0.5f;

        for (int iter = 0; iter < MAX_RESOLVE_ITERATIONS; iter++) {
            CapsuleShape c = proto.atCenter(pos);
            // Broad-phase: bounding box of cell indices the capsule could touch.
            float bound = c.boundingSphereRadius();
            int xLo = (int) Math.floor((pos.x() - bound - offsetX) / cellSize);
            int xHi = (int) Math.floor((pos.x() + bound - offsetX) / cellSize);
            int yLo = (int) Math.floor((c.segmentMinY() - bound - offsetY) / cellSize);
            int yHi = (int) Math.floor((c.segmentMaxY() + bound - offsetY) / cellSize);
            int zLo = (int) Math.floor((pos.z() - bound - offsetZ) / cellSize);
            int zHi = (int) Math.floor((pos.z() + bound - offsetZ) / cellSize);
            float totalPushSq = 0f;

            for (int gy = yLo; gy <= yHi; gy++) {
                for (int gz = zLo; gz <= zHi; gz++) {
                    for (int gx = xLo; gx <= xHi; gx++) {
                        if (!isObstacle(grid, gx, gy, gz)) continue;
                        AabbBox cell = new AabbBox(
                                offsetX + gx * cellSize,
                                offsetY + gy * cellSize,
                                offsetZ + gz * cellSize,
                                offsetX + (gx + 1) * cellSize,
                                offsetY + (gy + 1) * cellSize,
                                offsetZ + (gz + 1) * cellSize);
                        Vec3f mtv = CapsuleAabbTest.penetration(c, cell);
                        if (mtv.lengthSquared() == 0f) continue;
                        pos = pos.add(mtv);
                        c = proto.atCenter(pos);
                        totalPushSq += mtv.lengthSquared();
                    }
                }
            }
            if (totalPushSq < 1e-10f) break;
        }
        return pos;
    }

    /**
     * EMPTY in-grid cells AND out-of-grid cells are obstacles. Out-of-grid handling means the
     * dungeon's outer wall is solid even when the geometry happens to abut the grid boundary.
     */
    private static boolean isObstacle(TileGridValue3D grid, int x, int y, int z) {
        if (x < 0 || x >= grid.width()) return true;
        if (y < 0 || y >= grid.height()) return true;
        if (z < 0 || z >= grid.depth()) return true;
        return grid.at(x, y, z) == CellType.EMPTY;
    }
}
