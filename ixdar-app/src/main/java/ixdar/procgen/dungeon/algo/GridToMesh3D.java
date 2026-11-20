package ixdar.procgen.dungeon.algo;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.procgen.dungeon.values.CellType;

/**
 * 3D analog of {@link GridToMesh2D}: each non-empty cell gets an INWARD-wound face on every
 * side facing an EMPTY cell or the grid edge.
 *
 * <p>Vertical neighbors count as adjacency, so the floor between a STAIR_UP and the STAIR_DOWN
 * above it is omitted and the opening stays passable.
 */
public final class GridToMesh3D {

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
     * @return a fresh {@link HalfEdgeMesh} with positions, quad indices, and computed normals
     */
    public static HalfEdgeMesh emit(int width, int height, int depth, CellType[] cells, float cellSize) {
        int faces = countFaces(width, height, depth, cells) * 2;
        PackedPoints positions = new PackedPoints(faces * 4);
        int[] quads = new int[faces * 4];
        float offsetX = -width * cellSize * 0.5f;
        float offsetY = -height * cellSize * 0.5f;
        float offsetZ = -depth * cellSize * 0.5f;

        int vIdx = 0;
        int qIdx = 0;

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

                    if (y == 0 || cells[idx(x, y - 1, z, width, depth)] == CellType.EMPTY) {
                        writeQuad(positions, quads, qIdx, vIdx,
                                minX, minY, maxZ,  maxX, minY, maxZ,  maxX, minY, minZ,  minX, minY, minZ);
                        vIdx += 8; qIdx += 8;
                    }
                    if (y == height - 1 || cells[idx(x, y + 1, z, width, depth)] == CellType.EMPTY) {
                        writeQuad(positions, quads, qIdx, vIdx,
                                maxX, maxY, minZ,  maxX, maxY, maxZ,  minX, maxY, maxZ,  minX, maxY, minZ);
                        vIdx += 8; qIdx += 8;
                    }
                    if (x == 0 || cells[idx(x - 1, y, z, width, depth)] == CellType.EMPTY) {
                        writeQuad(positions, quads, qIdx, vIdx,
                                minX, maxY, minZ,  minX, maxY, maxZ,  minX, minY, maxZ,  minX, minY, minZ);
                        vIdx += 8; qIdx += 8;
                    }
                    if (x == width - 1 || cells[idx(x + 1, y, z, width, depth)] == CellType.EMPTY) {
                        writeQuad(positions, quads, qIdx, vIdx,
                                maxX, minY, maxZ,  maxX, maxY, maxZ,  maxX, maxY, minZ,  maxX, minY, minZ);
                        vIdx += 8; qIdx += 8;
                    }
                    if (z == 0 || cells[idx(x, y, z - 1, width, depth)] == CellType.EMPTY) {
                        writeQuad(positions, quads, qIdx, vIdx,
                                maxX, minY, minZ,  maxX, maxY, minZ,  minX, maxY, minZ,  minX, minY, minZ);
                        vIdx += 8; qIdx += 8;
                    }
                    if (z == depth - 1 || cells[idx(x, y, z + 1, width, depth)] == CellType.EMPTY) {
                        writeQuad(positions, quads, qIdx, vIdx,
                                minX, maxY, maxZ,  maxX, maxY, maxZ,  maxX, minY, maxZ,  minX, minY, maxZ);
                        vIdx += 8; qIdx += 8;
                    }
                }
            }
        }
        HalfEdgeMesh mesh = HalfEdgeMeshOps.bulkAllocate(positions, quads, 4);
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
    private static void writeQuad(PackedPoints positions, int[] quads, int q, int vBase,
                                 float ax, float ay, float az,
                                 float bx, float by, float bz,
                                 float cx, float cy, float cz,
                                 float dx, float dy, float dz) {
        positions.set(vBase, ax, ay, az);
        positions.set(vBase + 1, bx, by, bz);
        positions.set(vBase + 2, cx, cy, cz);
        positions.set(vBase + 3, dx, dy, dz);
        quads[q]     = vBase;
        quads[q + 1] = vBase + 1;
        quads[q + 2] = vBase + 2;
        quads[q + 3] = vBase + 3;
        positions.set(vBase + 4, ax, ay, az);
        positions.set(vBase + 5, dx, dy, dz);
        positions.set(vBase + 6, cx, cy, cz);
        positions.set(vBase + 7, bx, by, bz);
        quads[q + 4] = vBase + 4;
        quads[q + 5] = vBase + 5;
        quads[q + 6] = vBase + 6;
        quads[q + 7] = vBase + 7;
    }
}
