package ixdar.procgen.dungeon.algo;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.procgen.dungeon.values.CellType;

/**
 * 3D analog of {@link GridToMesh2D}: each non-empty cell gets an INWARD-wound face on every
 * side facing an EMPTY cell or the grid edge.
 *
 * <p>Vertical neighbors count as adjacency, so the floor between a STAIR_UP and the STAIR_DOWN
 * above it is omitted and the opening stays passable.
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
     * Sweep the 3D grid and emit a double-sided face on every side of every non-empty cell that
     * borders an EMPTY cell or grid edge, centered on the world origin.
     *
     * @param width    grid width in cells (X)
     * @param height   grid height in floors (Y)
     * @param depth    grid depth in cells (Z)
     * @param cells    cell types indexed {@code x + width * (z + depth * y)}
     * @param cellSize world-space size of a single grid cell
     * @return a fresh {@link ArrayMesh} with positions, quad indices, and computed normals
     */
    public static ArrayMesh emit(int width, int height, int depth, CellType[] cells, float cellSize) {
        // Each visible boundary emits TWO quads (inward + outward) — see GridToMesh2D for why.
        int faces = countFaces(width, height, depth, cells) * 2;
        float[] positions = new float[faces * NUM_4 * NUM_3];
        int[] quads = new int[faces * NUM_4];
        float offsetX = -width * cellSize * NUM_0_5;
        float offsetY = -height * cellSize * NUM_0_5;
        float offsetZ = -depth * cellSize * NUM_0_5;

        int vIdx = 0;
        int qIdx = 0;
        int writeFloats = 0;

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    CellType c = cells[idx(x, y, z, width, depth)];
                    if (c == CellType.EMPTY) continue;
                    float minX = offsetX + x * cellSize;
                    float maxX = minX + cellSize;
                    float minY = offsetY + y * cellSize;
                    float maxY = minY + cellSize;
                    float minZ = offsetZ + z * cellSize;
                    float maxZ = minZ + cellSize;

                    // -Y floor (omit if cell below is non-empty)
                    if (y == 0 || cells[idx(x, y - 1, z, width, depth)] == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                minX, minY, maxZ,  maxX, minY, maxZ,  maxX, minY, minZ,  minX, minY, minZ);
                        vIdx += NUM_8; qIdx += NUM_8;
                    }
                    // +Y ceiling (omit if cell above is non-empty)
                    if (y == height - 1 || cells[idx(x, y + 1, z, width, depth)] == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                maxX, maxY, minZ,  maxX, maxY, maxZ,  minX, maxY, maxZ,  minX, maxY, minZ);
                        vIdx += NUM_8; qIdx += NUM_8;
                    }
                    // -X wall
                    if (x == 0 || cells[idx(x - 1, y, z, width, depth)] == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                minX, maxY, minZ,  minX, maxY, maxZ,  minX, minY, maxZ,  minX, minY, minZ);
                        vIdx += NUM_8; qIdx += NUM_8;
                    }
                    // +X wall
                    if (x == width - 1 || cells[idx(x + 1, y, z, width, depth)] == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                maxX, minY, maxZ,  maxX, maxY, maxZ,  maxX, maxY, minZ,  maxX, minY, minZ);
                        vIdx += NUM_8; qIdx += NUM_8;
                    }
                    // -Z wall
                    if (z == 0 || cells[idx(x, y, z - 1, width, depth)] == CellType.EMPTY) {
                        writeFloats = writeQuad(positions, writeFloats, quads, qIdx, vIdx,
                                maxX, minY, minZ,  maxX, maxY, minZ,  minX, maxY, minZ,  minX, minY, minZ);
                        vIdx += NUM_8; qIdx += NUM_8;
                    }
                    // +Z wall
                    if (z == depth - 1 || cells[idx(x, y, z + 1, width, depth)] == CellType.EMPTY) {
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

    private static int idx(int x, int y, int z, int width, int depth) {
        return x + width * (z + depth * y);
    }

    private static int countFaces(int width, int height, int depth, CellType[] cells) {
        int count = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    CellType c = cells[idx(x, y, z, width, depth)];
                    if (c == CellType.EMPTY) continue;
                    if (y == 0 || cells[idx(x, y - 1, z, width, depth)] == CellType.EMPTY) count++;
                    if (y == height - 1 || cells[idx(x, y + 1, z, width, depth)] == CellType.EMPTY) count++;
                    if (x == 0 || cells[idx(x - 1, y, z, width, depth)] == CellType.EMPTY) count++;
                    if (x == width - 1 || cells[idx(x + 1, y, z, width, depth)] == CellType.EMPTY) count++;
                    if (z == 0 || cells[idx(x, y, z - 1, width, depth)] == CellType.EMPTY) count++;
                    if (z == depth - 1 || cells[idx(x, y, z + 1, width, depth)] == CellType.EMPTY) count++;
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
