package ixdar.procgen.dungeon.algo;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.TileGridValue;

/**
 * Converts a {@link TileGridValue} into an {@link ArrayMesh} of <em>hollow</em> rooms — every
 * non-empty cell contributes a floor and ceiling, plus a wall on each side adjacent to an EMPTY
 * cell or grid edge. Walls between two non-empty cells are NOT emitted, so the player can walk
 * through ROOM/HALLWAY transitions without phasing through geometry.
 *
 * <p>Quad winding is INWARD (normals point into the cell), so the inside of each wall is the
 * front face. Player stands inside the dungeon and sees walls on all sides.
 *
 * <p>The mesh is centered at the world origin. All non-empty cells share a single height
 * ({@code cellSize}) — visual distinction between ROOM and HALLWAY is dropped so the geometry
 * is simple and walkable. (Per-cell-type height variation can come back via partial walls at
 * height transitions when player physics lands.)
 */
public final class GridToMesh2D {
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0 = 0f;
    public static final int NUM_8 = 8;
    public static final int NUM_5 = 5;
    public static final int NUM_6 = 6;
    public static final int NUM_7 = 7;

    private GridToMesh2D() {
    }

    /**
     * TODO: document {@code emit}.
     *
     * @param grid TODO: describe
     * @param cellSize TODO: describe
     * @return TODO: describe
     */
    public static ArrayMesh emit(TileGridValue grid, float cellSize) {
        // Each visible boundary emits TWO quads (inward + outward winding) so the dungeon is
        // visible both when the camera is inside a room and when it's flying over from above.
        // Doubles vertex/face count but keeps the renderer happy without touching shaders or
        // backface-cull state. countFaces returns the count of boundary FACES; we multiply by 2
        // for the two windings.
        int faces = countFaces(grid) * 2;
        float[] positions = new float[faces * NUM_4 * NUM_3];
        int[] quads = new int[faces * NUM_4];
        float offsetX = -grid.width() * cellSize * NUM_0_5;
        float offsetZ = -grid.height() * cellSize * NUM_0_5;

        int vIdx = 0; // index of the next vertex to write (in vertex slots, not float slots)
        int qIdx = 0; // index of the next quad index to write
        int writeFloats = 0;

        for (int y = 0; y < grid.height(); y++) {
            for (int x = 0; x < grid.width(); x++) {
                CellType c = grid.at(x, y);
                if (c == CellType.EMPTY) continue;
                float minX = offsetX + x * cellSize;
                float maxX = minX + cellSize;
                float minZ = offsetZ + y * cellSize;
                float maxZ = minZ + cellSize;
                float minY = NUM_0;
                float maxY = cellSize;

                // Floor (-Y face, inward normal +Y)
                writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                        minX, minY, maxZ,  maxX, minY, maxZ,  maxX, minY, minZ,  minX, minY, minZ);
                vIdx += NUM_8; qIdx += NUM_8;
                // Ceiling (+Y face, inward normal -Y)
                writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                        maxX, maxY, minZ,  maxX, maxY, maxZ,  minX, maxY, maxZ,  minX, maxY, minZ);
                vIdx += NUM_8; qIdx += NUM_8;

                // -X wall (inward normal +X)
                if (x == 0 || grid.at(x - 1, y) == CellType.EMPTY) {
                    writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                            minX, maxY, minZ,  minX, maxY, maxZ,  minX, minY, maxZ,  minX, minY, minZ);
                    vIdx += NUM_8; qIdx += NUM_8;
                }
                // +X wall (inward normal -X)
                if (x == grid.width() - 1 || grid.at(x + 1, y) == CellType.EMPTY) {
                    writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                            maxX, minY, maxZ,  maxX, maxY, maxZ,  maxX, maxY, minZ,  maxX, minY, minZ);
                    vIdx += NUM_8; qIdx += NUM_8;
                }
                // -Z wall (inward normal +Z)
                if (y == 0 || grid.at(x, y - 1) == CellType.EMPTY) {
                    writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                            maxX, minY, minZ,  maxX, maxY, minZ,  minX, maxY, minZ,  minX, minY, minZ);
                    vIdx += NUM_8; qIdx += NUM_8;
                }
                // +Z wall (inward normal -Z)
                if (y == grid.height() - 1 || grid.at(x, y + 1) == CellType.EMPTY) {
                    writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                            minX, maxY, maxZ,  maxX, maxY, maxZ,  maxX, minY, maxZ,  minX, minY, maxZ);
                    vIdx += NUM_8; qIdx += NUM_8;
                }
            }
        }
        ArrayMesh mesh = ArrayMesh.fromQuads(positions, quads);
        mesh.computeNormals();
        return mesh;
    }

    /** Counts the number of quads we'll emit so we can size arrays exactly. */
    private static int countFaces(TileGridValue grid) {
        int count = 0;
        for (int y = 0; y < grid.height(); y++) {
            for (int x = 0; x < grid.width(); x++) {
                CellType c = grid.at(x, y);
                if (c == CellType.EMPTY) continue;
                count += 2; // floor + ceiling
                if (x == 0 || grid.at(x - 1, y) == CellType.EMPTY) count++;
                if (x == grid.width() - 1 || grid.at(x + 1, y) == CellType.EMPTY) count++;
                if (y == 0 || grid.at(x, y - 1) == CellType.EMPTY) count++;
                if (y == grid.height() - 1 || grid.at(x, y + 1) == CellType.EMPTY) count++;
            }
        }
        return count;
    }

    /** Writes one quad in the given winding plus a second quad in reverse winding (both sides). */
    private static int writeQuad(float[] positions, int p, int[] quads, int q, int vBase,
                                 float ax, float ay, float az,
                                 float bx, float by, float bz,
                                 float cx, float cy, float cz,
                                 float dx, float dy, float dz) {
        // Inward face (a, b, c, d).
        positions[p++] = ax; positions[p++] = ay; positions[p++] = az;
        positions[p++] = bx; positions[p++] = by; positions[p++] = bz;
        positions[p++] = cx; positions[p++] = cy; positions[p++] = cz;
        positions[p++] = dx; positions[p++] = dy; positions[p++] = dz;
        quads[q]     = vBase;
        quads[q + 1] = vBase + 1;
        quads[q + 2] = vBase + 2;
        quads[q + NUM_3] = vBase + NUM_3;
        // Outward face (reversed: a, d, c, b).
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
