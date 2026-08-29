package ixdar.geometry.mesh.quadlayout.gridmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.UvField;
import ixdar.geometry.mesh.quadlayout.ChartAtlas;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.extraction.ExtractedPatchGrids;
import ixdar.geometry.mesh.quadlayout.extraction.ExtractedQuadMesh;
import ixdar.geometry.mesh.quadlayout.extraction.PatchGridExtraction;

/**
 * Every patch's map carried into one common grid, which is LCBK19 Figure 10(d)
 * as a single object and the variable vector the optimization moves.
 *
 * <p>
 * See also: LCBK19 Section 6.2
 */
public final class GlobalGridMap implements UvField {

    /** Coordinates of a grid position. */
    public static final int GRID_COORDINATES = 2;

    /**
     * A layout node this far from an integer is off the grid, breaking EBCK13
     * Constraint 2.
     */
    public static final double INTEGER_TOLERANCE = 1.0e-6;

    /**
     * Depth of the non-seam patch neighbourhood searched for sites the relaxation
     * moved away.
     */
    public static final int NEIGHBOUR_DEPTH = 2;

    /**
     * Off-grid nodes named individually in the audit log before the counter takes
     * over.
     */
    public static final int OFF_GRID_SAMPLES_LISTED = 4;

    public final ArcNetwork tmesh;
    public final LayoutPatchMaps patchMaps;
    public final IntegerGridMap frames;

    /**
     * Common-grid position of every dense vertex of every live patch, laid out
     * {@code {u0, v0, u1, v1, ...}}; null for a retired patch.
     */
    public double[][] uvByPatchId;

    /**
     * Dense index of each copy vertex within a patch's map, indexed by patch id.
     */
    public Map<Integer, Integer>[] denseByCopyVertexByPatchId;

    /** Layout-node coordinates found off the integer grid. */
    public int offGridNodeCount;

    /** Largest distance from an integer over every layout node's coordinates. */
    public double worstNodeIntegerDeviation;

    /**
     * Patch charts and arc transitions: framed values at first, refined in place
     * by {@link GridMapVerification}, whose storage it is.
     */
    public final ChartAtlas atlas;

    public GridMapDofSystem gridDofs;

    public PatchGridExtraction quadGridInitial;

    public GridMapOptimizer gridOptimizer;

    public GridMapIsoSurface isoSurfaceInitial;

    public GridMapIsoSurface isoSurfaceRelaxed;

    public GridMapVerification gridVerification;

    public ExtractedQuadMesh quadMesh;

    public ExtractedPatchGrids extractedGrids;

    public UvField seamless;

    /**
     * Stores the per-patch maps and the frames that place them in one grid.
     *
     * @param patchMaps solved per-patch rectangle maps
     * @param frames    the patches' quarter turns and integer origins
     * @param seamless the seamless parameterization to constrain the global grid map against
     */
    public GlobalGridMap(LayoutPatchMaps patchMaps, IntegerGridMap frames, UvField seamless) {
        this.patchMaps = patchMaps;
        this.frames = frames;
        this.tmesh = patchMaps.tmesh;
        this.seamless = seamless;
        this.atlas = buildAtlas();
    }

    /**
     * Per-corner u over the working copy, from the latest baked iso surface.
     *
     * @param faceId copy face id
     * @param corner corner index in {@code [0, 3)}
     * @return u-coordinate at the given corner
     */
    @Override
    public double u(int faceId, int corner) {
        return currentIsoSurface().u(faceId, corner);
    }

    /**
     * Per-corner v over the working copy, from the latest baked iso surface.
     *
     * @param faceId copy face id
     * @param corner corner index in {@code [0, 3)}
     * @return v-coordinate at the given corner
     */
    @Override
    public double v(int faceId, int corner) {
        return currentIsoSurface().v(faceId, corner);
    }

    /**
     * The latest baked iso surface: relaxed when the relaxation has run.
     *
     * @return the iso surface backing the per-corner accessors
     */
    public GridMapIsoSurface currentIsoSurface() {
        return isoSurfaceRelaxed != null ? isoSurfaceRelaxed : isoSurfaceInitial;
    }

    /**
     * Fills the atlas: one chart per patch over the copy faces, one boundary per
     * arc directed right patch to left, transitions from the frames until the
     * verification refines them in place.
     *
     * @return the filled atlas
     */
    private ChartAtlas buildAtlas() {
        ChartAtlas built = new ChartAtlas(tmesh.patches.size(),
                tmesh.topology.sourceFaceByCopyFace.length, tmesh.arcs.size(), true);
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            for (int copyFace : patchMaps.regions.copyFacesByPatch.get(patch.patchId)) {
                built.chartOfFace[copyFace] = patch.patchId;
            }
        }
        for (EmbeddedArc arc : tmesh.arcs) {
            built.chartA[arc.arcId] = arc.rightPatchId;
            built.chartB[arc.arcId] = arc.leftPatchId;
            built.quarterTurns[arc.arcId] = frames.transitionQuarterTurnsByArcId[arc.arcId];
            built.translationU[arc.arcId] = frames.transitionTranslationUByArcId[arc.arcId];
            built.translationV[arc.arcId] = frames.transitionTranslationVByArcId[arc.arcId];
        }
        return built;
    }

    /**
     * The live patches within {@link #NEIGHBOUR_DEPTH} arcs of a patch, each with
     * the composed grid automorphism carrying its chart into the patch's own.
     * Entries are deduplicated by patch and transform together, because holonomy
     * around a singular node can legitimately reach one patch under two transforms.
     *
     * @param patchId patch whose neighbourhood is walked
     * @return entries {@code {patchId, quarterTurns, translationU, translationV}},
     *         nearest first
     */
    public List<int[]> chartNeighbourhood(int patchId) {
        List<int[]> reached = new ArrayList<>();
        reached.add(new int[] { patchId, 0, 0, 0 });
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
                        if (other == ArcNetwork.NONE || other == patch.patchId
                                || !atlas.hasTransition(arcId)
                                || !tmesh.patches.get(other).alive) {
                            continue;
                        }
                        double[] composed = ChartAtlas.compose(
                                new double[] { entry[1], entry[2], entry[3] },
                                atlas.transition(arcId, other));
                        int composedTurns = (int) composed[0];
                        int composedU = (int) Math.round(composed[1]);
                        int composedV = (int) Math.round(composed[2]);
                        boolean seen = false;
                        for (int[] existing : reached) {
                            if (existing[0] == other && existing[1] == composedTurns
                                    && existing[2] == composedU && existing[3] == composedV) {
                                seen = true;
                                break;
                            }
                        }
                        if (!seen) {
                            reached.add(new int[] { other, composedTurns, composedU, composedV });
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
     * @return the node's {@code (u, v)}, or null when the node is not in the
     *         patch's map
     */
    public double[] nodePosition(int patchId, int nodeId) {
        Integer dense = denseByCopyVertexByPatchId[patchId].get(tmesh.nodes.get(nodeId).copyVertex);
        if (dense == null) {
            return null;
        }
        double[] uv = uvByPatchId[patchId];
        return new double[] { uv[dense * GRID_COORDINATES], uv[dense * GRID_COORDINATES + 1] };
    }
}
