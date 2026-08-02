package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every patch's map carried into one common grid, which is LCBK19 Figure 10(d) as a single object
 * and the variable vector the optimization moves.
 *
 * <p>See also: LCBK19 Section 6.2
 */
public final class GlobalGridMap {

    /** Coordinates of a grid position. */
    public static final int GRID_COORDINATES = 2;

    /** A layout node this far from an integer is off the grid, breaking EBCK13 Constraint 2. */
    public static final double INTEGER_TOLERANCE = 1.0e-6;

    /** Depth of the non-seam patch neighbourhood searched for sites the relaxation moved away. */
    public static final int NEIGHBOUR_DEPTH = 2;

    /** Off-grid nodes named individually in the audit log before the counter takes over. */
    public static final int OFF_GRID_SAMPLES_LISTED = 4;

    public final EmbeddedTMesh tmesh;
    public final LayoutPatchMaps patchMaps;
    public final IntegerGridMap frames;

    /**
     * Common-grid position of every dense vertex of every live patch, laid out
     * {@code {u0, v0, u1, v1, ...}}; null for a retired patch.
     */
    public double[][] uvByPatchId;

    /** Dense index of each copy vertex within a patch's map, indexed by patch id. */
    public Map<Integer, Integer>[] denseByCopyVertexByPatchId;

    /** Layout-node coordinates found off the integer grid. */
    public int offGridNodeCount;

    /** Largest distance from an integer over every layout node's coordinates. */
    public double worstNodeIntegerDeviation;

    /**
     * Stores the per-patch maps and the frames that place them in one grid.
     *
     * @param patchMaps solved per-patch rectangle maps
     * @param frames    the patches' quarter turns and integer origins
     */
    public GlobalGridMap(LayoutPatchMaps patchMaps, IntegerGridMap frames) {
        this.patchMaps = patchMaps;
        this.frames = frames;
        this.tmesh = patchMaps.tmesh;
    }

    /**
     * Carries every patch's rectangle coordinates through its frame, then checks the layout's nodes
     * landed on integers.
     *
     * @return this, populated
     */
    @SuppressWarnings("unchecked")
    public GlobalGridMap build() {
        uvByPatchId = new double[tmesh.patches.size()][];
        denseByCopyVertexByPatchId = new HashMap[tmesh.patches.size()];
        double[] grid = new double[GRID_COORDINATES];
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            PatchRectangleMap map = patchMaps.mapByPatchId[patch.patchId];
            double[] uv = new double[map.positions.length * GRID_COORDINATES];
            Map<Integer, Integer> denseByCopyVertex = new HashMap<>();
            for (int dense = 0; dense < map.positions.length; dense++) {
                frames.toGrid(patch.patchId, map.rectangleU[dense], map.rectangleV[dense], grid);
                uv[dense * GRID_COORDINATES] = grid[0];
                uv[dense * GRID_COORDINATES + 1] = grid[1];
                denseByCopyVertex.put(map.vertexLabel[dense], dense);
            }
            uvByPatchId[patch.patchId] = uv;
            denseByCopyVertexByPatchId[patch.patchId] = denseByCopyVertex;
        }
        measureNodes();
        System.out.printf("[global-grid] patches=%d offGridNodes=%d worstNodeDeviation=%.3e%n",
                frames.placedPatchCount, offGridNodeCount, worstNodeIntegerDeviation);
        return this;
    }

    /**
     * Checks every layout node sits on an integer of the common grid, which is what makes the
     * quantization's assigned lengths meaningful in the map.
     */
    private void measureNodes() {
        int samplesPrinted = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                List<Integer> sideNodes = patch.sideNodeIds.get(side);
                for (int nodeId : sideNodes) {
                    double[] position = nodePosition(patch.patchId, nodeId);
                    if (position == null) {
                        continue;
                    }
                    boolean offGrid = false;
                    for (int axis = 0; axis < GRID_COORDINATES; axis++) {
                        double deviation = Math.abs(position[axis] - Math.round(position[axis]));
                        worstNodeIntegerDeviation = Math.max(worstNodeIntegerDeviation, deviation);
                        offGrid |= deviation > INTEGER_TOLERANCE;
                        offGridNodeCount += deviation > INTEGER_TOLERANCE ? 1 : 0;
                    }
                    if (offGrid && samplesPrinted < OFF_GRID_SAMPLES_LISTED) {
                        samplesPrinted++;
                        System.out.printf(
                                "[global-grid]   off-grid node=%d patch=%d side=%d sideArcs=%d"
                                        + " sideNodes=%d at (%.4f, %.4f)%n",
                                nodeId, patch.patchId, side, patch.sideArcIds.get(side).size(),
                                sideNodes.size(), position[0], position[1]);
                    }
                }
            }
        }
    }

    /**
     * The live patches within {@link #NEIGHBOUR_DEPTH} arcs of a patch, each with the composed
     * grid automorphism carrying its chart into the patch's own. Entries are deduplicated by
     * patch and transform together, because holonomy around a singular node can legitimately
     * reach one patch under two transforms.
     *
     * @param patchId patch whose neighbourhood is walked
     * @return entries {@code {patchId, quarterTurns, translationU, translationV}}, nearest first
     */
    public List<int[]> chartNeighbourhood(int patchId) {
        List<int[]> reached = new ArrayList<>();
        double[] rotated = new double[GRID_COORDINATES];
        reached.add(new int[] {patchId, 0, 0, 0});
        int frontierStart = 0;
        for (int depth = 0; depth < NEIGHBOUR_DEPTH; depth++) {
            int frontierEnd = reached.size();
            for (int cursor = frontierStart; cursor < frontierEnd; cursor++) {
                int[] entry = reached.get(cursor);
                EmbeddedPatch patch = tmesh.patches.get(entry[0]);
                for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                    for (int arcId : patch.sideArcIds.get(side)) {
                        EmbeddedArc arc = tmesh.arcs.get(arcId);
                        int other = arc.leftPatchId == patch.patchId ? arc.rightPatchId
                                : arc.leftPatchId;
                        int turns = frames.transitionQuarterTurnsByArcId[arcId];
                        if (other == EmbeddedTMesh.NONE || other == patch.patchId
                                || turns == IntegerGridMap.NOT_PLACED
                                || !tmesh.patches.get(other).alive) {
                            continue;
                        }
                        int mapTurns = turns;
                        int mapU = frames.transitionTranslationUByArcId[arcId];
                        int mapV = frames.transitionTranslationVByArcId[arcId];
                        if (patch.patchId == arc.rightPatchId) {
                            mapTurns = (IntegerGridMap.QUARTER_TURNS - turns)
                                    % IntegerGridMap.QUARTER_TURNS;
                            IntegerGridMap.rotate(mapTurns, mapU, mapV, rotated);
                            mapU = -(int) Math.round(rotated[0]);
                            mapV = -(int) Math.round(rotated[1]);
                        }
                        int composedTurns = (entry[1] + mapTurns) % IntegerGridMap.QUARTER_TURNS;
                        IntegerGridMap.rotate(entry[1], mapU, mapV, rotated);
                        int composedU = entry[2] + (int) Math.round(rotated[0]);
                        int composedV = entry[3] + (int) Math.round(rotated[1]);
                        boolean seen = false;
                        for (int[] existing : reached) {
                            if (existing[0] == other && existing[1] == composedTurns
                                    && existing[2] == composedU && existing[3] == composedV) {
                                seen = true;
                                break;
                            }
                        }
                        if (!seen) {
                            reached.add(new int[] {other, composedTurns, composedU, composedV});
                        }
                    }
                }
            }
            frontierStart = frontierEnd;
        }
        return reached.subList(1, reached.size());
    }

    /**
     * The common-grid position a patch gives one of the layout's nodes.
     *
     * @param patchId patch to read in
     * @param nodeId  node to locate
     * @return the node's {@code (u, v)}, or null when the node is not in the patch's map
     */
    public double[] nodePosition(int patchId, int nodeId) {
        Integer dense = denseByCopyVertexByPatchId[patchId].get(tmesh.nodes.get(nodeId).copyVertex);
        if (dense == null) {
            return null;
        }
        double[] uv = uvByPatchId[patchId];
        return new double[] {uv[dense * GRID_COORDINATES], uv[dense * GRID_COORDINATES + 1]};
    }
}
