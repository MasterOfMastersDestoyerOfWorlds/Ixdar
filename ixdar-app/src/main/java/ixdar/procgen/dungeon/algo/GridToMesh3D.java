package ixdar.procgen.dungeon.algo;

import ixdar.geometry.mesh.data.ArrayMesh;
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

    private GridToMesh3D() {
    }

    public static ArrayMesh emit(TileGridValue3D grid, float cellSize) {
        // Each visible boundary emits TWO quads (inward + outward) — see GridToMesh2D for why.
        int faces = countFaces(grid) * 2;
        float[] positions = new float[faces * 4 * 3];
        int[] quads = new int[faces * 4];
        float offsetX = -grid.width() * cellSize * 0.5f;
        float offsetY = -grid.height() * cellSize * 0.5f;
        float offsetZ = -grid.depth() * cellSize * 0.5f;

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
                        vIdx += 8; qIdx += 8;
                    }
                    // +Y ceiling (omit if cell above is non-empty)
                    if (y == grid.height() - 1 || grid.at(x, y + 1, z) == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                maxX, maxY, minZ,  maxX, maxY, maxZ,  minX, maxY, maxZ,  minX, maxY, minZ);
                        vIdx += 8; qIdx += 8;
                    }
                    // -X wall
                    if (x == 0 || grid.at(x - 1, y, z) == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                minX, maxY, minZ,  minX, maxY, maxZ,  minX, minY, maxZ,  minX, minY, minZ);
                        vIdx += 8; qIdx += 8;
                    }
                    // +X wall
                    if (x == grid.width() - 1 || grid.at(x + 1, y, z) == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                maxX, minY, maxZ,  maxX, maxY, maxZ,  maxX, maxY, minZ,  maxX, minY, minZ);
                        vIdx += 8; qIdx += 8;
                    }
                    // -Z wall
                    if (z == 0 || grid.at(x, y, z - 1) == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                maxX, minY, minZ,  maxX, maxY, minZ,  minX, maxY, minZ,  minX, minY, minZ);
                        vIdx += 8; qIdx += 8;
                    }
                    // +Z wall
                    if (z == grid.depth() - 1 || grid.at(x, y, z + 1) == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                minX, maxY, maxZ,  maxX, maxY, maxZ,  maxX, minY, maxZ,  minX, minY, maxZ);
                        vIdx += 8; qIdx += 8;
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
        quads[q + 3] = vBase + 3;
        positions[p++] = ax; positions[p++] = ay; positions[p++] = az;
        positions[p++] = dx; positions[p++] = dy; positions[p++] = dz;
        positions[p++] = cx; positions[p++] = cy; positions[p++] = cz;
        positions[p++] = bx; positions[p++] = by; positions[p++] = bz;
        quads[q + 4] = vBase + 4;
        quads[q + 5] = vBase + 5;
        quads[q + 6] = vBase + 6;
        quads[q + 7] = vBase + 7;
        return p;
    }
}
