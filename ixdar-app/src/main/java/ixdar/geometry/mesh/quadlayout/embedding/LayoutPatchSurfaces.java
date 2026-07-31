package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.CoonsEvaluator;

/**
 * Render records for the finished layout: per patch, its four traced boundary polylines, the
 * extracted quad grid on the surface, and a Coons blend of the same four sides at the same grid
 * resolution.
 *
 * <p>See also: LCK21a Section 6
 */
public final class LayoutPatchSurfaces {

    /** Components of a packed position. */
    public static final int VECTOR_COMPONENTS = 3;

    public final PatchGridExtraction quadGrid;
    public final EmbeddedTMesh tmesh;

    /** One record per live patch, in patch id order. */
    public final List<LayoutPatchCurves> patches = new ArrayList<>();

    /**
     * Stores the extracted grids the records are built from.
     *
     * @param quadGrid the per-patch grids of a conforming layout
     */
    public LayoutPatchSurfaces(PatchGridExtraction quadGrid) {
        this.quadGrid = quadGrid;
        this.tmesh = quadGrid.tmesh;
    }

    /**
     * Builds one record per live patch.
     *
     * @return this, populated
     */
    public LayoutPatchSurfaces build() {
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            int columns = quadGrid.gridColumns(patch.patchId);
            int rows = quadGrid.gridRows(patch.patchId);
            Vector3f[] grid = quadGrid.gridByPatchId[patch.patchId];
            float[] surfaceGrid = new float[columns * rows * VECTOR_COMPONENTS];
            for (int index = 0; index < grid.length; index++) {
                surfaceGrid[index * VECTOR_COMPONENTS] = grid[index].x;
                surfaceGrid[index * VECTOR_COMPONENTS + 1] = grid[index].y;
                surfaceGrid[index * VECTOR_COMPONENTS + 2] = grid[index].z;
            }
            List<List<Vector3f>> sidePolylines = new ArrayList<>(EmbeddedPatch.SIDES);
            Vector3f[] cornerPositions = new Vector3f[EmbeddedPatch.SIDES];
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                sidePolylines.add(sidePolyline(patch, side));
                cornerPositions[side] = tmesh.topology.copy.vertexPosition(
                        tmesh.nodes.get(patch.cornerNodeId(side)).copyVertex);
            }
            patches.add(new LayoutPatchCurves(patch.patchId, sidePolylines, cornerPositions,
                    columns, rows, surfaceGrid, coonsBlend(grid, columns, rows)));
        }
        return this;
    }

    /**
     * The boundary of one side of a patch at the quad grid's own resolution, from its corner to
     * the next.
     *
     * <p>The arc's carved edge path zig-zags along triangle edges; the layout arc a reader sees
     * is the chain of quad edges along it, which is what the sample points give.
     *
     * @param patch patch to read
     * @param side  side index in {@code [0, 4)}
     * @return the side's sample positions in walking order
     */
    private List<Vector3f> sidePolyline(EmbeddedPatch patch, int side) {
        List<Integer> sideArcs = patch.sideArcIds.get(side);
        List<Integer> sideNodes = patch.sideNodeIds.get(side);
        List<Vector3f> polyline = new ArrayList<>();
        polyline.add(tmesh.topology.copy.vertexPosition(
                tmesh.nodes.get(sideNodes.get(0)).copyVertex));
        for (int arcIndex = 0; arcIndex < sideArcs.size(); arcIndex++) {
            EmbeddedArc arc = tmesh.arcs.get(sideArcs.get(arcIndex));
            Vector3f[] points = quadGrid.pointsByArc[arc.arcId];
            boolean forward = arc.startNodeId == sideNodes.get(arcIndex);
            for (int sample = 1; sample < points.length; sample++) {
                polyline.add(points[forward ? sample : points.length - 1 - sample]);
            }
        }
        return polyline;
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
    private float[] coonsBlend(Vector3f[] grid, int columns, int rows) {
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
