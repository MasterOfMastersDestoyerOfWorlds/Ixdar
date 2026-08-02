package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.joml.Vector3f;

/**
 * The quad mesh the quantization prescribes, placed on the surface: one grid
 * per layout patch, at the integer lattice of that patch's rectangle map.
 *
 * <p>
 * See also: LCK21a Section 6; LCBK19 Section 6.2
 */
public final class PatchGridExtraction {

    /** Corners of a triangle; the copy mesh is triangulated. */
    public static final int TRIANGLE_CORNERS = 3;

    /**
     * Barycentric slack when testing whether a lattice point lies in a triangle. A
     * point on a shared edge is claimed by whichever triangle the scan reaches
     * first, so the tolerance only has to admit it somewhere, not exactly once.
     */
    public static final double BARYCENTRIC_TOLERANCE = 1.0e-9;

    public final LayoutPatchMaps patchMaps;
    public final EmbeddedTMesh tmesh;

    /**
     * The relaxed grid map to read instead of the patches' rectangles. When set,
     * every lattice site — the boundary included — is inverted through the map, so
     * patch boundaries follow the relaxation off their rectangles; null keeps the
     * boundary pinned to the traced arcs.
     */
    public GlobalGridMap optimizedGrid;

    /**
     * Sample points along each arc, indexed by arc id, from its start node to its
     * end node.
     */
    public Vector3f[][] pointsByArc;

    /**
     * Grid of each live patch, indexed by patch id, row-major so that the point at
     * column {@code column} and row {@code row} is at
     * {@code row * gridColumns(patchId) + column}.
     */
    public Vector3f[][] gridByPatchId;

    /** Quads across each live patch in its first direction, indexed by patch id. */
    public int[] widthByPatchId;

    /**
     * Quads across each live patch in its second direction, indexed by patch id.
     */
    public int[] heightByPatchId;

    /** Quads in the extracted mesh, the sum over patches of width times height. */
    public int quadCount;

    /** Strip id of an arc not yet assigned to one. */
    public static final int UNASSIGNED = -1;

    /** Parametric length of each arc, indexed by arc id. */
    public double[] lengthByArc;

    /** Parametric length one quad edge should span. */
    public final double targetEdgeLength;

    /**
     * Strip each arc belongs to, indexed by arc id; {@link #UNASSIGNED} for a
     * retired arc.
     */
    public int[] stripByArc;

    /** Quads laid along each arc, indexed by arc id; zero for a retired arc. */
    public int[] quadsByArc;

    /** Number of strips found. */
    public int stripCount;

    /**
     * Largest ratio of a strip's longest arc to its mean. Consistency forces one
     * count on the whole strip, so a spread far above one means no assignment can
     * size that strip well.
     */
    public double worstStripSpread;

    /** Arcs in the strip that set {@link #worstStripSpread}. */
    public int worstStripArcCount;

    /** Shortest arc of the strip that set {@link #worstStripSpread}. */
    public double worstStripShortest;

    /** Longest arc of the strip that set {@link #worstStripSpread}. */
    public double worstStripLongest;

    /** Quads the strip that set {@link #worstStripSpread} had to settle on. */
    public int worstStripQuads;

    /**
     * Stores the solved patch maps the grid is extracted from.
     *
     * @param patchMaps per-patch rectangle maps of a conforming layout
     * @param sizing    quads to lay along each arc
     */
    public PatchGridExtraction(LayoutPatchMaps patchMaps, double targetEdgeLength) {
        this.patchMaps = patchMaps;
        this.tmesh = patchMaps.tmesh;
        this.targetEdgeLength = targetEdgeLength;
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
     * @throws IllegalStateException when a lattice point falls outside every
     *                               triangle of its patch's region, which a
     *                               fold-free map cannot do
     * @return this, populated
     */
    public PatchGridExtraction build() {
        layoutStripSizing();
        placeArcPoints();
        gridByPatchId = new Vector3f[tmesh.patches.size()][];
        widthByPatchId = new int[tmesh.patches.size()];
        heightByPatchId = new int[tmesh.patches.size()];
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            widthByPatchId[patch.patchId] = quadsByArc[patch.sideArcIds.get(0).get(0)];
            heightByPatchId[patch.patchId] = quadsByArc[patch.sideArcIds.get(1).get(0)];
            int columns = gridColumns(patch.patchId);
            int rows = gridRows(patch.patchId);
            Vector3f[] grid = new Vector3f[columns * rows];
            gridByPatchId[patch.patchId] = grid;
            if (optimizedGrid == null) {
                placeBoundaryPoints(patch, columns, rows, grid);
            }
            placeLatticePoints(patch.patchId, patch.patchId, 0, 0, 0, columns, rows, grid);
            if (optimizedGrid != null) {
                for (int[] neighbour : optimizedGrid.chartNeighbourhood(patch.patchId)) {
                    placeLatticePoints(patch.patchId, neighbour[0], neighbour[1], neighbour[2],
                            neighbour[3], columns, rows, grid);
                }
            }
            requireComplete(patch.patchId, columns, rows, grid);
            quadCount += widthByPatchId[patch.patchId] * heightByPatchId[patch.patchId];
        }
        return this;
    }

    /**
     * Groups the arcs into strips and gives each strip one quad count.
     *
     * @throws IllegalStateException when a patch side carries more than one arc,
     *                               which leaves the strips ill-defined
     * @return this, sized
     */
    public void layoutStripSizing() {
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                int arcCount = patch.sideArcIds.get(side).size();
                if (arcCount != 1) {
                    throw new IllegalStateException("patch " + patch.patchId + " side " + side
                            + " carries " + arcCount + " arcs; the strips are only well defined"
                            + " once every side is one arc, so a degree-two node interior to a"
                            + " side has to be merged away first");
                }
            }
        }
        stripByArc = new int[tmesh.arcs.size()];
        quadsByArc = new int[tmesh.arcs.size()];
        Arrays.fill(stripByArc, UNASSIGNED);
        List<List<Integer>> arcsByStrip = new ArrayList<>();
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!arc.alive || stripByArc[arc.arcId] != UNASSIGNED) {
                continue;
            }
            arcsByStrip.add(floodStrip(arc.arcId, stripCount++));
        }
        //TODO: this needs to come from the quantization according to LCK21a
    }

    /**
     * Collects one strip: the arcs reachable from a seed by stepping to the
     * opposite side of an incident patch, which are exactly the arcs a rectangle
     * forces to carry the same count.
     *
     * @param seedArcId arc to start from
     * @param strip     strip id to stamp
     * @return the strip's member arc ids
     */
    private List<Integer> floodStrip(int seedArcId, int strip) {
        List<Integer> frontier = new ArrayList<>();
        stripByArc[seedArcId] = strip;
        frontier.add(seedArcId);
        for (int cursor = 0; cursor < frontier.size(); cursor++) {
            int arcId = frontier.get(cursor);
            EmbeddedArc arc = tmesh.arcs.get(arcId);
            for (int patchId : new int[] { arc.leftPatchId, arc.rightPatchId }) {
                if (patchId == EmbeddedTMesh.NONE || !tmesh.patches.get(patchId).alive) {
                    continue;
                }
                EmbeddedPatch patch = tmesh.patches.get(patchId);
                for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                    if (patch.sideArcIds.get(side).get(0) != arcId) {
                        continue;
                    }
                    int oppositeArcId = patch.sideArcIds
                            .get((side + 2) % EmbeddedPatch.SIDES).get(0);
                    if (stripByArc[oppositeArcId] == UNASSIGNED) {
                        stripByArc[oppositeArcId] = strip;
                        frontier.add(oppositeArcId);
                    }
                }
            }
        }
        return frontier;
    }

    /**
     * Distributes each arc's sample points along its edge path by chord length, so
     * that the arc itself owns them and both incident patches read the same
     * positions.
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
            int samples = quadsByArc[arc.arcId];
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
                int samples = quadsByArc[arc.arcId];
                for (int sample = 0; sample <= samples; sample++) {
                    grid[borderIndex(side, offset + sample, columns, rows)] = points[forward ? sample
                            : samples - sample];
                }
                offset += samples;
            }
        }
    }

    /**
     * The grid index of the point at a sample offset along one side of a patch,
     * walking the boundary the way {@link PatchRectangleMap} pins it to the
     * rectangle.
     *
     * @param side    side index in {@code [0, 4)}
     * @param offset  sample offset from the side's start
     * @param columns the patch's grid columns
     * @param rows    the patch's grid rows
     * @return the index into the row-major grid
     */
    public int borderIndex(int side, int offset, int columns, int rows) {
        switch (side) {
        case 0:
            return offset;
        case 1:
            return offset * columns + columns - 1;
        case 2:
            return (rows - 1) * columns + (columns - 1 - offset);
        default:
            return (rows - 1 - offset) * columns;
        }
    }

    /**
     * Fills a patch's still-empty lattice sites from one source patch's triangles,
     * inverting the piecewise-linear map exactly by barycentric containment.
     *
     * @param framePatchId  patch whose grid and frame the sites live in
     * @param sourcePatchId patch whose triangles and surface positions are read
     * @param quarterTurns  rotation of the automorphism carrying the source chart
     *                      into the frame's
     * @param translationU  grid u translation of that automorphism
     * @param translationV  grid v translation of that automorphism
     * @param columns       the frame patch's grid columns
     * @param rows          the frame patch's grid rows
     * @param grid          grid to fill, row-major
     */
    private void placeLatticePoints(int framePatchId, int sourcePatchId, int quarterTurns,
            int translationU, int translationV, int columns, int rows, Vector3f[] grid) {
        PatchRectangleMap frameMap = patchMaps.mapByPatchId[framePatchId];
        PatchRectangleMap map = patchMaps.mapByPatchId[sourcePatchId];
        double scaleU = (columns - 1) / frameMap.width;
        double scaleV = (rows - 1) / frameMap.height;
        double[] local = new double[2];
        double[] cornerU = new double[TRIANGLE_CORNERS];
        double[] cornerV = new double[TRIANGLE_CORNERS];
        for (int[] triangle : map.triangles) {
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                patchLocal(framePatchId, sourcePatchId, quarterTurns, translationU, translationV,
                        map, triangle[corner], local);
                cornerU[corner] = local[0] * scaleU;
                cornerV[corner] = local[1] * scaleV;
            }
            double ux = cornerU[0];
            double uy = cornerV[0];
            double vx = cornerU[1];
            double vy = cornerV[1];
            double wx = cornerU[2];
            double wy = cornerV[2];
            double doubleArea = (vx - ux) * (wy - uy) - (wx - ux) * (vy - uy);
            if (doubleArea == 0.0) {
                continue;
            }
            int columnLow = Math.max(0, (int) Math.ceil(Math.min(ux, Math.min(vx, wx))));
            int columnHigh = Math.min(columns - 1, (int) Math.floor(Math.max(ux, Math.max(vx, wx))));
            int rowLow = Math.max(0, (int) Math.ceil(Math.min(uy, Math.min(vy, wy))));
            int rowHigh = Math.min(rows - 1, (int) Math.floor(Math.max(uy, Math.max(vy, wy))));
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
                    grid[row * columns + column] = new Vector3f(map.positions[triangle[0]]).mul((float) first)
                            .fma((float) second, map.positions[triangle[1]])
                            .fma((float) third, map.positions[triangle[2]]);
                }
            }
        }
    }

    /**
     * A source vertex's position in the frame patch's rectangle, its chart
     * coordinates carried through the automorphism into the frame's chart first.
     * Without a relaxed map source and frame coincide and the solved rectangle
     * coordinates are read directly.
     *
     * @param framePatchId  patch whose frame is undone
     * @param sourcePatchId patch the vertex belongs to
     * @param quarterTurns  rotation of the automorphism carrying the source chart
     *                      into the frame's
     * @param translationU  grid u translation of that automorphism
     * @param translationV  grid v translation of that automorphism
     * @param map           the source patch's solved rectangle map
     * @param dense         dense vertex index within that map
     * @param out           receives the rectangle position
     */
    private void patchLocal(int framePatchId, int sourcePatchId, int quarterTurns,
            int translationU, int translationV, PatchRectangleMap map, int dense, double[] out) {
        if (optimizedGrid == null) {
            out[0] = map.rectangleU[dense];
            out[1] = map.rectangleV[dense];
            return;
        }
        double[] uv = optimizedGrid.uvByPatchId[sourcePatchId];
        IntegerGridMap.rotate(quarterTurns, uv[dense * GlobalGridMap.GRID_COORDINATES],
                uv[dense * GlobalGridMap.GRID_COORDINATES + 1], out);
        optimizedGrid.frames.toLocal(framePatchId, out[0] + translationU, out[1] + translationV,
                out);
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
