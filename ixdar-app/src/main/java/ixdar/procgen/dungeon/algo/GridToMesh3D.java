package ixdar.procgen.dungeon.algo;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.TileGridValue3D;

/**
 * 3D analog of {@link GridToMesh2D}. Emits hollow rooms — every non-empty cell contributes a
 * face on each side adjacent to an EMPTY cell or grid edge, with INWARD-facing winding so the
 * player inside the dungeon sees the front of every wall, floor, and ceiling.
 *
 * <p>Vertical neighbors are checked: the cell directly above and below. STAIR_UP / STAIR_DOWN
 * cells are vertically adjacent (a stair-up's ceiling = a stair-down's floor) and BOTH non-empty,
 * so the floor/ceiling between them is omitted — the player can pass through the opening from
 * one floor to the next.
 *
 * <p>All non-empty cells share a uniform height of {@code cellSize}. Visual ROOM/HALLWAY/STAIR
 * distinction by box height is dropped in favor of walkable interiors.
 */
public final class GridToMesh3D {
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;
    public static final float NUM_0_5 = 0.5f;
    public static final int NUM_8 = 8;
    public static final int NUM_5 = 5;
    public static final int NUM_6 = 6;
    public static final int NUM_7 = 7;

    private GridToMesh3D() {
    }

    /**
     * Sweep the 3D grid and emit a face on every side of every non-empty cell that borders an
     * EMPTY cell or grid edge, with double-sided winding. Floor/ceiling between vertically
     * adjacent non-empty cells (e.g. STAIR_UP under STAIR_DOWN) are skipped so the player can
     * pass through. The mesh is centered on the world origin.
     *
     * @param grid     populated 3D tile grid
     * @param cellSize world-space size of a single grid cell
     * @return a fresh {@link ArrayMesh} with positions, quad indices, and computed normals
     */
    public static ArrayMesh emit(TileGridValue3D grid, float cellSize) {
        // Each visible boundary emits TWO quads (inward + outward) — see GridToMesh2D for why.
        int faces = countFaces(grid) * 2;
        float[] positions = new float[faces * NUM_4 * NUM_3];
        int[] quads = new int[faces * NUM_4];
        float offsetX = -grid.width() * cellSize * NUM_0_5;
        float offsetY = -grid.height() * cellSize * NUM_0_5;
        float offsetZ = -grid.depth() * cellSize * NUM_0_5;

        int vIdx = 0;
        int qIdx = 0;
        int writeFloats = 0;

        for (int y = 0; y < grid.height(); y++) {
            for (int z = 0; z < grid.depth(); z++) {
                for (int x = 0; x < grid.width(); x++) {
                    CellType c = grid.at(x, y, z);
                    if (c == CellType.EMPTY) continue;
                    float minX = offsetX + x * cellSize;
                    float maxX = minX + cellSize;
                    float minY = offsetY + y * cellSize;
                    float maxY = minY + cellSize;
                    float minZ = offsetZ + z * cellSize;
                    float maxZ = minZ + cellSize;

                    // -Y floor (omit if cell below is non-empty)
                    if (y == 0 || grid.at(x, y - 1, z) == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                minX, minY, maxZ,  maxX, minY, maxZ,  maxX, minY, minZ,  minX, minY, minZ);
                        vIdx += NUM_8; qIdx += NUM_8;
                    }
                    // +Y ceiling (omit if cell above is non-empty)
                    if (y == grid.height() - 1 || grid.at(x, y + 1, z) == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                maxX, maxY, minZ,  maxX, maxY, maxZ,  minX, maxY, maxZ,  minX, maxY, minZ);
                        vIdx += NUM_8; qIdx += NUM_8;
                    }
                    // -X wall
                    if (x == 0 || grid.at(x - 1, y, z) == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                minX, maxY, minZ,  minX, maxY, maxZ,  minX, minY, maxZ,  minX, minY, minZ);
                        vIdx += NUM_8; qIdx += NUM_8;
                    }
                    // +X wall
                    if (x == grid.width() - 1 || grid.at(x + 1, y, z) == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                maxX, minY, maxZ,  maxX, maxY, maxZ,  maxX, maxY, minZ,  maxX, minY, minZ);
                        vIdx += NUM_8; qIdx += NUM_8;
                    }
                    // -Z wall
                    if (z == 0 || grid.at(x, y, z - 1) == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                maxX, minY, minZ,  maxX, maxY, minZ,  minX, maxY, minZ,  minX, minY, minZ);
                        vIdx += NUM_8; qIdx += NUM_8;
                    }
                    // +Z wall
                    if (z == grid.depth() - 1 || grid.at(x, y, z + 1) == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                minX, maxY, maxZ,  maxX, maxY, maxZ,  maxX, minY, maxZ,  minX, minY, maxZ);
                        vIdx += NUM_8; qIdx += NUM_8;
                    }
                }
            }
        }
        ArrayMesh mesh = ArrayMesh.fromQuads(positions, quads);
        mesh.computeNormals();
        return mesh;
    }

    private static int countFaces(TileGridValue3D grid) {
        int count = 0;
        for (int y = 0; y < grid.height(); y++) {
            for (int z = 0; z < grid.depth(); z++) {
                for (int x = 0; x < grid.width(); x++) {
                    CellType c = grid.at(x, y, z);
                    if (c == CellType.EMPTY) continue;
                    if (y == 0 || grid.at(x, y - 1, z) == CellType.EMPTY) count++;
                    if (y == grid.height() - 1 || grid.at(x, y + 1, z) == CellType.EMPTY) count++;
                    if (x == 0 || grid.at(x - 1, y, z) == CellType.EMPTY) count++;
                    if (x == grid.width() - 1 || grid.at(x + 1, y, z) == CellType.EMPTY) count++;
                    if (z == 0 || grid.at(x, y, z - 1) == CellType.EMPTY) count++;
                    if (z == grid.depth() - 1 || grid.at(x, y, z + 1) == CellType.EMPTY) count++;
                }
            }
        }
        return count;
    }

    /** Writes one quad in the given winding plus a second quad in reverse (both sides visible). */
    private static int writeQuad(float[] positions, int p, int[] quads, int q, int vBase,
                                 float ax, float ay, float az,
                                 float bx, float by, float bz,
                                 float cx, float cy, float cz,
                                 float dx, float dy, float dz) {
        positions[p++] = ax; positions[p++] = ay; positions[p++] = az;
        positions[p++] = bx; positions[p++] = by; positions[p++] = bz;
        positions[p++] = cx; positions[p++] = cy; positions[p++] = cz;
        positions[p++] = dx; positions[p++] = dy; positions[p++] = dz;
        quads[q]     = vBase;
        quads[q + 1] = vBase + 1;
        quads[q + 2] = vBase + 2;
        quads[q + NUM_3] = vBase + NUM_3;
        positions[p++] = ax; positions[p++] = ay; positions[p++] = az;
        positions[p++] = dx; positions[p++] = dy; positions[p++] = dz;
        positions[p++] = cx; positions[p++] = cy; positions[p++] = cz;
        positions[p++] = bx; positions[p++] = by; positions[p++] = bz;
        quads[q + NUM_4] = vBase + NUM_4;
        quads[q + NUM_5] = vBase + NUM_5;
        quads[q + NUM_6] = vBase + NUM_6;
        quads[q + NUM_7] = vBase + NUM_7;
        return p;
    }
}
