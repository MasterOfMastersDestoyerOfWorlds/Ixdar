package ixdar.geometry.mesh.quadlayout.extraction;

import java.util.List;

import org.joml.Vector3f;

/**
 * One layout patch as the renderer needs it: its four boundary polylines in the walking order the
 * patch stores them, its four corner positions, and two grids of the same shape — the quad mesh
 * extracted onto the surface, and a Coons blend of the four sides.
 */
public final class LayoutPatchCurves {

    public final int patchId;

    /** The four side polylines, each from its own corner to the next, corners included. */
    public final List<List<Vector3f>> sidePolylines;

    /** Corner positions in side order, so entry {@code s} starts side {@code s}. */
    public final Vector3f[] cornerPositions;

    /** Grid columns, one more than the patch's quad width. */
    public final int gridColumns;

    /** Grid rows, one more than the patch's quad height. */
    public final int gridRows;

    /** Surface sample points, packed xyz row-major over rows then columns. */
    public final float[] surfaceGrid;

    /** Coons blend of the four sides, same packing and shape as {@link #surfaceGrid}. */
    public final float[] coonsGrid;

    /**
     * Creates one patch's render record.
     *
     * @param patchId         patch this describes
     * @param sidePolylines   four side polylines, corners included
     * @param cornerPositions four corner positions in side order
     * @param gridColumns     grid columns, one more than the quad width
     * @param gridRows        grid rows, one more than the quad height
     * @param surfaceGrid     surface sample points, packed xyz
     * @param coonsGrid       Coons blend of the four sides, packed xyz
     */
    public LayoutPatchCurves(int patchId, List<List<Vector3f>> sidePolylines,
            Vector3f[] cornerPositions, int gridColumns, int gridRows, float[] surfaceGrid,
            float[] coonsGrid) {
        this.patchId = patchId;
        this.sidePolylines = sidePolylines;
        this.cornerPositions = cornerPositions;
        this.gridColumns = gridColumns;
        this.gridRows = gridRows;
        this.surfaceGrid = surfaceGrid;
        this.coonsGrid = coonsGrid;
    }
}
