package ixdar.geometry.volume;

import java.util.Arrays;
import java.util.HashMap;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;

/**
 * The level set of a voxel grid as a closed triangle mesh, by marching tetrahedra over the Kuhn split
 * of each lattice cube, six tetrahedra around its main diagonal, so no case table is needed.
 */
public final class MarchingTetrahedra {
    public static final int CUBE_CORNERS = 8;
    public static final int TETRAHEDRON_CORNERS = 4;
    public static final int[][] KUHN_TETRAHEDRA = {
        { 0, 1, 3, 7 }, { 0, 1, 5, 7 }, { 0, 2, 3, 7 }, { 0, 2, 6, 7 }, { 0, 4, 5, 7 }, { 0, 4, 6, 7 } };
    public static final float EDGE_CLAMP = 0.02f;
    public static final int INITIAL_CAPACITY = 1024;

    private MarchingTetrahedra() {
    }

    /**
     * Extract the surface where the grid crosses a level, each triangle wound to face from the
     * points above the level (inside) toward those at or below it; a crossing point is clamped
     * {@link #EDGE_CLAMP} of its edge away from either end so no triangle collapses.
     *
     * @param grid  the volume
     * @param level the value the surface passes through
     * @return the triangle mesh, normals computed
     */
    public static HalfEdgeMesh extract(VoxelGrid grid, float level) {
        float[] field = grid.values;
        HashMap<Long, Integer> crossingVertex = new HashMap<>();
        float[] positions = new float[INITIAL_CAPACITY];
        int vertexCount = 0;
        int[] triangles = new int[INITIAL_CAPACITY];
        int triangleCorners = 0;
        int[] corner = new int[CUBE_CORNERS];
        int[] inside = new int[TETRAHEDRON_CORNERS];
        int[] outside = new int[TETRAHEDRON_CORNERS];
        int[] ring = new int[TETRAHEDRON_CORNERS];
        Vector3f from = new Vector3f();
        Vector3f to = new Vector3f();
        Vector3f outward = new Vector3f();
        for (int cube = 0; cube < field.length; cube++) {
            if (grid.coordinate(cube, 0) + 1 >= grid.size[0] || grid.coordinate(cube, 1) + 1 >= grid.size[1]
                    || grid.coordinate(cube, 2) + 1 >= grid.size[2]) {
                continue;
            }
            for (int bits = 0; bits < CUBE_CORNERS; bits++) {
                corner[bits] = cube + (bits >> 2 & 1) * grid.strideZ + (bits >> 1 & 1) * grid.strideY + (bits & 1);
            }
            for (int[] tetrahedron : KUHN_TETRAHEDRA) {
                int insideCount = 0;
                int outsideCount = 0;
                for (int index : tetrahedron) {
                    if (field[corner[index]] > level) {
                        inside[insideCount++] = corner[index];
                    } else {
                        outside[outsideCount++] = corner[index];
                    }
                }
                if (insideCount == 0 || outsideCount == 0) {
                    continue;
                }
                // The crossing edges in cyclic order: a fan of three around a lone corner, or the
                // quad between two inside and two outside corners.
                int ringSize = insideCount == 2 ? 4 : 3;
                for (int index = 0; index < ringSize; index++) {
                    int inner = insideCount == 1 ? inside[0] : insideCount == 3 ? inside[index] : inside[index / 2];
                    int outer = insideCount == 1 ? outside[index] : insideCount == 3 ? outside[0]
                            : outside[(index + 1) / 2 % 2];
                    long key = (long) Math.min(inner, outer) * field.length + Math.max(inner, outer);
                    Integer known = crossingVertex.get(key);
                    if (known == null) {
                        float fraction = Math.max(EDGE_CLAMP, Math.min(1f - EDGE_CLAMP,
                                (field[inner] - level) / (field[inner] - field[outer])));
                        if (positions.length < (vertexCount + 1) * 3) {
                            positions = Arrays.copyOf(positions, positions.length * 2);
                        }
                        grid.position(inner, from).lerp(grid.position(outer, to), fraction);
                        positions[vertexCount * 3] = from.x;
                        positions[vertexCount * 3 + 1] = from.y;
                        positions[vertexCount * 3 + 2] = from.z;
                        known = vertexCount++;
                        crossingVertex.put(key, known);
                    }
                    ring[index] = known;
                }
                // Outward: from the inside corners' centre toward the outside corners'.
                outward.zero();
                for (int index = 0; index < TETRAHEDRON_CORNERS; index++) {
                    boolean isInside = index < insideCount;
                    grid.position(isInside ? inside[index] : outside[index - insideCount], from)
                            .mul(isInside ? -1f / insideCount : 1f / outsideCount);
                    outward.add(from);
                }
                for (int fan = 1; fan + 1 < ringSize; fan++) {
                    int a = ring[0] * 3;
                    int b = ring[fan] * 3;
                    int c = ring[fan + 1] * 3;
                    from.set(positions[b] - positions[a], positions[b + 1] - positions[a + 1],
                            positions[b + 2] - positions[a + 2]);
                    to.set(positions[c] - positions[a], positions[c + 1] - positions[a + 1],
                            positions[c + 2] - positions[a + 2]);
                    boolean flip = from.cross(to).dot(outward) < 0f;
                    if (triangles.length < triangleCorners + 3) {
                        triangles = Arrays.copyOf(triangles, triangles.length * 2);
                    }
                    triangles[triangleCorners++] = ring[0];
                    triangles[triangleCorners++] = flip ? ring[fan + 1] : ring[fan];
                    triangles[triangleCorners++] = flip ? ring[fan] : ring[fan + 1];
                }
            }
        }
        HalfEdgeMesh mesh = HalfEdgeMeshOps.bulkAllocate(Arrays.copyOf(positions, vertexCount * 3),
                Arrays.copyOf(triangles, triangleCorners), 3);
        mesh.computeNormals();
        return mesh;
    }
}
