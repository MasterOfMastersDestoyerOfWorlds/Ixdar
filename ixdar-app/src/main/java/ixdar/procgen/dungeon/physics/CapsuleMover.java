package ixdar.procgen.dungeon.physics;

import org.joml.Vector3f;

import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.TileGridValue3D;

/**
 * Resolves a capsule's desired motion against a {@link TileGridValue3D}, sub-stepping to avoid
 * tunneling and sliding along contacts. {@link CellType#EMPTY} and out-of-grid cells are
 * obstacles; every other cell type is walkable.
 *
 * <p>Cells are placed with {@code GridToMesh3D}'s origin-centered convention, so the two must
 * agree on {@code cellSize} and grid dimensions.
 */
public final class CapsuleMover {
    public static final float NUM_0 = 0f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_1 = 1f;
    public static final float NUM_1e_10 = 1e-10f;

    /** Maximum collision-resolution iterations per sub-step. */
    public static final int MAX_RESOLVE_ITERATIONS = 4;

    private CapsuleMover() {
    }

    /**
     * Slides the capsule along {@code delta}, sub-stepping to avoid tunneling and resolving
     * penetration against obstacle cells via iterated MTV accumulation.
     *
     * @param capsuleAtStart capsule at its starting position (its center is the start point)
     * @param delta          desired motion of the capsule's center
     * @param grid           static obstacle grid; EMPTY cells are solid
     * @param cellSize       world units per cell (matches {@code GridToMesh3D}'s cellSize)
     * @throws IllegalArgumentException if {@code cellSize} is not strictly positive
     * @return the resolved end position of the capsule's center
     */
    public static Vector3f moveAndSlide(CapsuleShape capsuleAtStart,
                                     Vector3f delta,
                                     TileGridValue3D grid,
                                     float cellSize) {
        if (cellSize <= NUM_0) {
            throw new IllegalArgumentException("cellSize must be > 0, got " + cellSize);
        }
        // Sub-stepping based on the smaller of the capsule's body height and radius keeps the
        // capsule from tunneling through thin walls within one frame at high speed.
        float deltaLen = delta.length();
        float maxStep = capsuleAtStart.radius() * NUM_0_5;
        int substeps = Math.max(1, (int) Math.ceil(deltaLen / maxStep));
        Vector3f stepDelta = delta.mul(NUM_1 / substeps);

        Vector3f pos = new Vector3f(capsuleAtStart.centerX(), capsuleAtStart.centerY(), capsuleAtStart.centerZ());
        for (int s = 0; s < substeps; s++) {
            pos = pos.add(stepDelta);
            pos = resolve(capsuleAtStart, pos, grid, cellSize);
        }
        return pos;
    }

    /**
     * Iterative MTV accumulation — pushes the capsule out of any obstacle cells it overlaps.
     *
     * @param proto    capsule template providing halfHeight and radius (recentered each iteration)
     * @param pos      current capsule center, before resolution
     * @param grid     static obstacle grid
     * @param cellSize world units per cell
     * @return capsule center after up to {@link #MAX_RESOLVE_ITERATIONS} resolve passes
     */
    private static Vector3f resolve(CapsuleShape proto, Vector3f pos, TileGridValue3D grid, float cellSize) {
        float offsetX = -grid.width() * cellSize * NUM_0_5;
        float offsetY = -grid.height() * cellSize * NUM_0_5;
        float offsetZ = -grid.depth() * cellSize * NUM_0_5;

        for (int iter = 0; iter < MAX_RESOLVE_ITERATIONS; iter++) {
            CapsuleShape c = new CapsuleShape(pos.x(), pos.y(), pos.z(), proto.halfHeight(), proto.radius());
            // Broad-phase: bounding box of cell indices the capsule could touch.
            float bound = c.boundingSphereRadius();
            int xLo = (int) Math.floor((pos.x() - bound - offsetX) / cellSize);
            int xHi = (int) Math.floor((pos.x() + bound - offsetX) / cellSize);
            int yLo = (int) Math.floor((c.segmentMinY() - bound - offsetY) / cellSize);
            int yHi = (int) Math.floor((c.segmentMaxY() + bound - offsetY) / cellSize);
            int zLo = (int) Math.floor((pos.z() - bound - offsetZ) / cellSize);
            int zHi = (int) Math.floor((pos.z() + bound - offsetZ) / cellSize);
            float totalPushSq = NUM_0;

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
                        Vector3f mtv = CapsuleAabbTest.penetration(c, cell);
                        if (mtv.lengthSquared() == NUM_0) continue;
                        pos = pos.add(mtv);
                        c = proto.atCenter(pos);
                        totalPushSq += mtv.lengthSquared();
                    }
                }
            }
            if (totalPushSq < NUM_1e_10) break;
        }
        return pos;
    }

    /**
     * EMPTY in-grid cells AND out-of-grid cells are obstacles. Out-of-grid handling means the
     * dungeon's outer wall is solid even when the geometry happens to abut the grid boundary.
     *
     * @param grid obstacle grid to sample
     * @param x    cell index along X
     * @param y    cell index along Y (floor)
     * @param z    cell index along Z
     * @return {@code true} if the cell is solid (out-of-bounds or {@link CellType#EMPTY})
     */
    private static boolean isObstacle(TileGridValue3D grid, int x, int y, int z) {
        if (x < 0 || x >= grid.width()) return true;
        if (y < 0 || y >= grid.height()) return true;
        if (z < 0 || z >= grid.depth()) return true;
        return grid.at(x, y, z) == CellType.EMPTY;
    }
}
