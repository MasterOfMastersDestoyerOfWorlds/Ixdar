package ixdar.procgen.dungeon.algo;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.TileGridValue;

/**
 * Converts a {@link TileGridValue} into an {@link ArrayMesh} suitable for rendering a walkable
 * dungeon. Every non-empty cell emits one axis-aligned box (8 vertices, 6 quad faces). ROOM
 * cells emit a full unit-height box; HALLWAY cells emit a flatter box so the two types are
 * visually distinguishable in the fly-cam viewer. Cells with {@link CellType#EMPTY} produce no
 * geometry.
 *
 * <p>The emitted mesh is centered at the world origin — cell (gridW/2, gridH/2) lands near
 * (0, 0, 0) and the mesh spans roughly {@code [-gridW*cellSize/2, +gridW*cellSize/2]} in X/Z.
 * This matches the Ixdar mesh convention (primitives like {@code cube(size=1)} span {@code ±0.5}),
 * so downstream transforms and the fly-cam viewer see a "unit-ish" object. The grid Y axis
 * maps to world Z; world Y is wall-height. Quad winding matches {@code CubeMeshNode} so
 * normals-from-quads produces outward-facing normals.
 *
 * <p>Option B (instanced rendering via InstanceOnPointsNode) is deferred — unit tests in this
 * ticket only verify vertex / face counts and basic well-formedness.
 */
public final class GridToMesh2D {

    /** Relative height of a HALLWAY cell box (1.0 = same as ROOM). */
    public static final float HALLWAY_HEIGHT_FRACTION = 0.4f;

    private GridToMesh2D() {
    }

    public static ArrayMesh emit(TileGridValue grid, float cellSize) {
        int filled = 0;
        for (int i = 0; i < grid.cellCount(); i++) {
            if (grid.cells()[i] != CellType.EMPTY) filled++;
        }
        float[] positions = new float[filled * 8 * 3];
        int[] quads = new int[filled * 6 * 4];
        // Offset so the mesh is centered on the world origin (Ixdar mesh convention).
        float offsetX = -grid.width() * cellSize * 0.5f;
        float offsetZ = -grid.height() * cellSize * 0.5f;
        int vOff = 0;
        int fOff = 0;
        int boxIdx = 0;
        for (int y = 0; y < grid.height(); y++) {
            for (int x = 0; x < grid.width(); x++) {
                CellType c = grid.at(x, y);
                if (c == CellType.EMPTY) continue;
                float heightFrac = (c == CellType.HALLWAY) ? HALLWAY_HEIGHT_FRACTION : 1.0f;
                writeBox(positions, vOff, quads, fOff, boxIdx,
                        offsetX + x * cellSize, 0f, offsetZ + y * cellSize,
                        cellSize, cellSize * heightFrac, cellSize);
                vOff += 8 * 3;
                fOff += 6 * 4;
                boxIdx++;
            }
        }
        ArrayMesh mesh = ArrayMesh.fromQuads(positions, quads);
        mesh.computeNormals();
        return mesh;
    }

    /** Writes 8 vertex positions and 6 quad indices for one axis-aligned box. */
    private static void writeBox(float[] positions, int vOff, int[] quads, int fOff, int boxIdx,
                                 float minX, float minY, float minZ,
                                 float sx, float sy, float sz) {
        float maxX = minX + sx;
        float maxY = minY + sy;
        float maxZ = minZ + sz;
        // Match CubeMeshNode vertex ordering (-Z face first, then +Z, winding CCW from outside):
        //   0:(-x,-y,-z) 1:(+x,-y,-z) 2:(+x,+y,-z) 3:(-x,+y,-z)
        //   4:(-x,-y,+z) 5:(+x,-y,+z) 6:(+x,+y,+z) 7:(-x,+y,+z)
        int v = vOff;
        positions[v++] = minX; positions[v++] = minY; positions[v++] = minZ;
        positions[v++] = maxX; positions[v++] = minY; positions[v++] = minZ;
        positions[v++] = maxX; positions[v++] = maxY; positions[v++] = minZ;
        positions[v++] = minX; positions[v++] = maxY; positions[v++] = minZ;
        positions[v++] = minX; positions[v++] = minY; positions[v++] = maxZ;
        positions[v++] = maxX; positions[v++] = minY; positions[v++] = maxZ;
        positions[v++] = maxX; positions[v++] = maxY; positions[v++] = maxZ;
        positions[v++] = minX; positions[v++] = maxY; positions[v++] = maxZ;
        int base = boxIdx * 8;
        // 6 quads, same winding as CubeMeshNode (outward-facing).
        int[] box = {
                0, 3, 2, 1,   // Back (-Z)
                4, 5, 6, 7,   // Front (+Z)
                0, 1, 5, 4,   // Bottom (-Y)
                3, 7, 6, 2,   // Top   (+Y)
                1, 2, 6, 5,   // Right (+X)
                0, 4, 7, 3,   // Left  (-X)
        };
        for (int i = 0; i < 24; i++) {
            quads[fOff + i] = base + box[i];
        }
    }
}
