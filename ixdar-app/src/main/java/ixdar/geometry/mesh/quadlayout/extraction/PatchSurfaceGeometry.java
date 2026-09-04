package ixdar.geometry.mesh.quadlayout.extraction;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.CoonsEvaluator;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * Builders and readers for the finished layout's render geometry: each live patch's extracted
 * quad grid as quad faces (one component per patch, vertices row-major), the patch id as a
 * per-face {@link #PATCH_ID} attribute; the Coons variant blends each patch's grid borders.
 *
 * <p>See also: LCK21a Section 6
 */
public final class PatchSurfaceGeometry {

    /** Slot name for the per-face {@link IntField} of layout patch ids. */
    public static final String PATCH_ID = "patch_id";

    private PatchSurfaceGeometry() {
    }

    /**
     * The extracted quad grids on the surface, one quad-grid component per live patch.
     *
     * @param grids the per-patch grids of a conforming layout
     * @return bundle of the grid mesh with the {@link #PATCH_ID} per-face slot
     */
    public static GeometryBundle surfaceBundle(PatchGridExtraction grids) {
        return build(grids, false);
    }

    /**
     * The same grid topology with each patch's interior filled by a Coons blend of its four
     * borders, which the blend reproduces verbatim.
     *
     * @param grids the per-patch grids of a conforming layout
     * @return bundle of the blended grid mesh with the {@link #PATCH_ID} per-face slot
     */
    public static GeometryBundle coonsBundle(PatchGridExtraction grids) {
        return build(grids, true);
    }

    /**
     * The {@link #PATCH_ID} attribute of a patch-surface bundle.
     *
     * @param bundle patch-surface bundle
     * @throws IllegalArgumentException if the slot is missing or its length mismatches the mesh
     * @return per-face patch ids, one per dense face index
     */
    public static IntField patchIds(GeometryBundle bundle) {
        Object slot = bundle.slots().get(PATCH_ID);
        if (!(slot instanceof IntField field)) {
            throw new IllegalArgumentException(
                    "geometry carries no '" + PATCH_ID + "' IntField slot");
        }
        if (field.length() != bundle.mesh().faceCount()) {
            throw new IllegalArgumentException("'" + PATCH_ID + "' length " + field.length()
                    + " != face count " + bundle.mesh().faceCount());
        }
        return field;
    }

    private static GeometryBundle build(PatchGridExtraction grids, boolean coons) {
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int faceTotal = 0;
        for (EmbeddedPatch patch : grids.tmesh.patches) {
            if (patch.alive) {
                faceTotal += (grids.gridColumns(patch.patchId) - 1)
                        * (grids.gridRows(patch.patchId) - 1);
            }
        }
        int[] patchIds = new int[faceTotal];
        int faceCursor = 0;
        for (EmbeddedPatch patch : grids.tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            int columns = grids.gridColumns(patch.patchId);
            int rows = grids.gridRows(patch.patchId);
            Vector3f[] grid = grids.gridByPatchId[patch.patchId];
            float[] blend = coons ? coonsBlend(grid, columns, rows) : null;
            int[] vertexIds = new int[columns * rows];
            for (int index = 0; index < vertexIds.length; index++) {
                vertexIds[index] = coons
                        ? mesh.addVertex(blend[index * HalfEdgeMesh.FLOATS_PER_VERTEX],
                                blend[index * HalfEdgeMesh.FLOATS_PER_VERTEX + 1],
                                blend[index * HalfEdgeMesh.FLOATS_PER_VERTEX + 2])
                        : mesh.addVertex(grid[index].x, grid[index].y, grid[index].z);
            }
            for (int row = 0; row < rows - 1; row++) {
                for (int column = 0; column < columns - 1; column++) {
                    int corner00 = vertexIds[row * columns + column];
                    int corner10 = vertexIds[row * columns + column + 1];
                    int corner01 = vertexIds[(row + 1) * columns + column];
                    int corner11 = vertexIds[(row + 1) * columns + column + 1];
                    mesh.addFace(corner00, corner10, corner11, corner01);
                    patchIds[faceCursor++] = patch.patchId;
                }
            }
        }
        return GeometryBundle.ofMesh(mesh).withSlot(PATCH_ID, new IntField(patchIds));
    }

    /**
     * The Coons blend of a patch's four grid borders, which reproduces the borders verbatim and
     * fills the interior transfinitely rather than following the surface.
     *
     * @param grid    the patch's extracted grid, row-major
     * @param columns grid columns
     * @param rows    grid rows
     * @return packed xyz triples in the same shape as the grid
     */
    private static float[] coonsBlend(Vector3f[] grid, int columns, int rows) {
        Vector3f[] sideU0 = new Vector3f[columns];
        Vector3f[] sideU1 = new Vector3f[columns];
        for (int column = 0; column < columns; column++) {
            sideU0[column] = grid[column];
            sideU1[column] = grid[(rows - 1) * columns + column];
        }
        Vector3f[] sideV0 = new Vector3f[rows];
        Vector3f[] sideV1 = new Vector3f[rows];
        for (int row = 0; row < rows; row++) {
            sideV0[row] = grid[row * columns];
            sideV1[row] = grid[row * columns + columns - 1];
        }
        return CoonsEvaluator.blendGrid(sideU0, sideU1, sideV0, sideV1);
    }
}
