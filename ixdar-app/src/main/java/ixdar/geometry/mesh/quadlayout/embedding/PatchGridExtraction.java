package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.List;

import org.joml.Vector3f;

/**
 * The quad mesh the quantization prescribes, placed on the surface: one grid per layout patch, at
 * the integer lattice of that patch's rectangle map.
 *
 * <p>See also: LCK21a Section 6; LCBK19 Section 6.2
 */
public final class PatchGridExtraction {

    /** Corners of a triangle; the copy mesh is triangulated. */
    public static final int TRIANGLE_CORNERS = 3;

    /**
     * Barycentric slack when testing whether a lattice point lies in a triangle. A point on a
     * shared edge is claimed by whichever triangle the scan reaches first, so the tolerance only
     * has to admit it somewhere, not exactly once.
     */
    public static final double BARYCENTRIC_TOLERANCE = 1.0e-9;

    public final LayoutPatchMaps patchMaps;
    public final EmbeddedTMesh tmesh;

    /** Quads to lay along each arc, shared by both its patches. */
    public final LayoutStripSizing sizing;

    /** Sample points along each arc, indexed by arc id, from its start node to its end node. */
    public Vector3f[][] pointsByArc;

    /**
     * Grid of each live patch, indexed by patch id, row-major so that the point at column
     * {@code column} and row {@code row} is at {@code row * gridColumns(patchId) + column}.
     */
    public Vector3f[][] gridByPatchId;

    /** Quads across each live patch in its first direction, indexed by patch id. */
    public int[] widthByPatchId;

    /** Quads across each live patch in its second direction, indexed by patch id. */
    public int[] heightByPatchId;

    /** Quads in the extracted mesh, the sum over patches of width times height. */
    public int quadCount;

    /**
     * Stores the solved patch maps the grid is extracted from.
     *
     * @param patchMaps per-patch rectangle maps of a conforming layout
     * @param sizing    quads to lay along each arc
     */
    public PatchGridExtraction(LayoutPatchMaps patchMaps, LayoutStripSizing sizing) {
        this.patchMaps = patchMaps;
        this.tmesh = patchMaps.tmesh;
        this.sizing = sizing;
    }

    /**
     * The number of grid columns of a patch, one more than its quad width.
     *
     * @param patchId live patch to measure
     * @return the column count
     */
    public int gridColumns(int patchId) {
        return widthByPatchId[patchId] + 1;
    }

    /**
     * The number of grid rows of a patch, one more than its quad height.
     *
     * @param patchId live patch to measure
     * @return the row count
     */
    public int gridRows(int patchId) {
        return heightByPatchId[patchId] + 1;
    }

    /**
     * Places every patch's grid points on the surface.
     *
     * @throws IllegalStateException when a lattice point falls outside every triangle of its
     *                               patch's region, which a fold-free map cannot do
     * @return this, populated
     */
    public PatchGridExtraction build() {
        placeArcPoints();
        gridByPatchId = new Vector3f[tmesh.patches.size()][];
        widthByPatchId = new int[tmesh.patches.size()];
        heightByPatchId = new int[tmesh.patches.size()];
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            PatchRectangleMap map = patchMaps.mapByPatchId[patch.patchId];
            widthByPatchId[patch.patchId] = sizing.sideQuads(patch, 0);
            heightByPatchId[patch.patchId] = sizing.sideQuads(patch, 1);
            int columns = gridColumns(patch.patchId);
            int rows = gridRows(patch.patchId);
            Vector3f[] grid = new Vector3f[columns * rows];
            gridByPatchId[patch.patchId] = grid;
            placeBoundaryPoints(patch, columns, rows, grid);
            placeInteriorPoints(map, columns, rows, grid);
            requireComplete(patch.patchId, columns, rows, grid);
            quadCount += widthByPatchId[patch.patchId] * heightByPatchId[patch.patchId];
        }
        return this;
    }

    /**
     * Distributes each arc's sample points along its edge path by chord length, so that the arc
     * itself owns them and both incident patches read the same positions.
     */
    private void placeArcPoints() {
        pointsByArc = new Vector3f[tmesh.arcs.size()][];
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!arc.alive) {
                continue;
            }
            List<Integer> path = arc.path.copyVertexPath;
            Vector3f[] pathPositions = new Vector3f[path.size()];
            double[] cumulative = new double[path.size()];
            for (int step = 0; step < path.size(); step++) {
                pathPositions[step] = tmesh.topology.copy.vertexPosition(path.get(step));
                if (step > 0) {
                    cumulative[step] = cumulative[step - 1]
                            + pathPositions[step].distance(pathPositions[step - 1]);
                }
            }
            double total = cumulative[path.size() - 1];
            int samples = sizing.quadsByArc[arc.arcId];
            Vector3f[] points = new Vector3f[samples + 1];
            points[0] = new Vector3f(pathPositions[0]);
            points[samples] = new Vector3f(pathPositions[path.size() - 1]);
            int step = 1;
            for (int sample = 1; sample < samples; sample++) {
                double target = total * sample / samples;
                while (step < path.size() - 1 && cumulative[step] < target) {
                    step++;
                }
                double span = cumulative[step] - cumulative[step - 1];
                float fraction = span == 0.0 ? 0f : (float) ((target - cumulative[step - 1]) / span);
                points[sample] = new Vector3f(pathPositions[step - 1])
                        .lerp(pathPositions[step], fraction);
            }
            pointsByArc[arc.arcId] = points;
        }
    }

    /**
     * Copies the four sides' arc sample points into a patch's grid border.
     *
     * @param patch   patch whose border is filled
     * @param columns the patch's grid columns
     * @param rows    the patch's grid rows
     * @param grid    grid to fill, row-major
     */
    private void placeBoundaryPoints(EmbeddedPatch patch, int columns, int rows, Vector3f[] grid) {
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideArcs = patch.sideArcIds.get(side);
            List<Integer> sideNodes = patch.sideNodeIds.get(side);
            int offset = 0;
            for (int arcIndex = 0; arcIndex < sideArcs.size(); arcIndex++) {
                EmbeddedArc arc = tmesh.arcs.get(sideArcs.get(arcIndex));
                Vector3f[] points = pointsByArc[arc.arcId];
                boolean forward = arc.startNodeId == sideNodes.get(arcIndex);
                int samples = sizing.quadsByArc[arc.arcId];
                for (int sample = 0; sample <= samples; sample++) {
                    grid[borderIndex(side, offset + sample, columns, rows)] =
                            points[forward ? sample : samples - sample];
                }
                offset += samples;
            }
        }
    }

    /**
     * The grid index of the point at a sample offset along one side of a patch, walking the
     * boundary the way {@link PatchRectangleMap} pins it to the rectangle.
     *
     * @param side    side index in {@code [0, 4)}
     * @param offset  sample offset from the side's start
     * @param columns the patch's grid columns
     * @param rows    the patch's grid rows
     * @return the index into the row-major grid
     */
    private int borderIndex(int side, int offset, int columns, int rows) {
        switch (side) {
            case 0: return offset;
            case 1: return offset * columns + columns - 1;
            case 2: return (rows - 1) * columns + (columns - 1 - offset);
            default: return (rows - 1 - offset) * columns;
        }
    }

    /**
     * Places a patch's interior grid points, lifting each lattice point by its barycentric
     * coordinates in the rectangle-map triangle that contains it.
     *
     * <p>The map is piecewise linear and fold-free, so scanning the triangles inverts it exactly
     * and no iterative search is needed.
     *
     * @param map     the patch's solved rectangle map
     * @param columns the patch's grid columns
     * @param rows    the patch's grid rows
     * @param grid    grid to fill, row-major
     */
    private void placeInteriorPoints(PatchRectangleMap map, int columns, int rows,
            Vector3f[] grid) {
        double scaleU = (columns - 1) / map.width;
        double scaleV = (rows - 1) / map.height;
        for (int[] triangle : map.triangles) {
            double ux = map.rectangleU[triangle[0]] * scaleU;
            double uy = map.rectangleV[triangle[0]] * scaleV;
            double vx = map.rectangleU[triangle[1]] * scaleU;
            double vy = map.rectangleV[triangle[1]] * scaleV;
            double wx = map.rectangleU[triangle[2]] * scaleU;
            double wy = map.rectangleV[triangle[2]] * scaleV;
            double doubleArea = (vx - ux) * (wy - uy) - (wx - ux) * (vy - uy);
            if (doubleArea == 0.0) {
                continue;
            }
            int columnLow = Math.max(1, (int) Math.ceil(Math.min(ux, Math.min(vx, wx))));
            int columnHigh = Math.min(columns - 2, (int) Math.floor(Math.max(ux, Math.max(vx, wx))));
            int rowLow = Math.max(1, (int) Math.ceil(Math.min(uy, Math.min(vy, wy))));
            int rowHigh = Math.min(rows - 2, (int) Math.floor(Math.max(uy, Math.max(vy, wy))));
            for (int row = rowLow; row <= rowHigh; row++) {
                for (int column = columnLow; column <= columnHigh; column++) {
                    if (grid[row * columns + column] != null) {
                        continue;
                    }
                    double first = ((vx - column) * (wy - row) - (wx - column) * (vy - row))
                            / doubleArea;
                    double second = ((wx - column) * (uy - row) - (ux - column) * (wy - row))
                            / doubleArea;
                    double third = 1.0 - first - second;
                    if (first < -BARYCENTRIC_TOLERANCE || second < -BARYCENTRIC_TOLERANCE
                            || third < -BARYCENTRIC_TOLERANCE) {
                        continue;
                    }
                    grid[row * columns + column] =
                            new Vector3f(map.positions[triangle[0]]).mul((float) first)
                                    .fma((float) second, map.positions[triangle[1]])
                                    .fma((float) third, map.positions[triangle[2]]);
                }
            }
        }
    }

    /**
     * Checks a patch's grid has a point at every lattice site.
     *
     * @param patchId patch to check, for the message
     * @param columns the patch's grid columns
     * @param rows    the patch's grid rows
     * @param grid    the filled grid
     * @throws IllegalStateException when a lattice point was never placed
     */
    private void requireComplete(int patchId, int columns, int rows, Vector3f[] grid) {
        for (int index = 0; index < grid.length; index++) {
            if (grid[index] == null) {
                throw new IllegalStateException("patch " + patchId + " has no surface point for"
                        + " grid site (" + index % columns + ", " + index / columns + ") of its "
                        + columns + "x" + rows + " grid; a fold-free map covers every lattice"
                        + " point");
            }
        }
    }
}
