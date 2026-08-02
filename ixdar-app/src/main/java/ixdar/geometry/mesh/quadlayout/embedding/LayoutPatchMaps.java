package ixdar.geometry.mesh.quadlayout.embedding;

/**
 * The Tutte map of every patch of a conforming layout onto its quantized rectangle, which the
 * union of the patch maps turns into a global integer grid map.
 *
 * <p>See also: LCBK19 Section 6.2
 */
public final class LayoutPatchMaps {

    public final EmbeddedTMesh tmesh;

    /** Regions of the working copy, rebuilt after the refinement retriangulates it. */
    public PatchRegions regions;

    /** Solved rectangle map of each live patch, indexed by patch id; null for a retired patch. */
    public PatchRectangleMap[] mapByPatchId;

    /** Chords subdivided to make every region 3-connected. */
    public int subdividedChordCount;

    /** Patches mapped. */
    public int mappedPatchCount;

    /** Patches whose cotangent solve folded and were re-solved with uniform weights. */
    public int uniformFallbackPatchCount;

    /**
     * Stores the T-mesh whose patches are mapped.
     *
     * @param tmesh conforming embedded T-mesh
     */
    public LayoutPatchMaps(EmbeddedTMesh tmesh) {
        this.tmesh = tmesh;
    }

    /**
     * Refines the working copy, recomputes the patch regions and solves every patch's map,
     * asserting each one is fold-free. A patch whose cotangent solve folds is re-solved with
     * uniform weights, which Tutte's theorem guarantees valid (RPP17 §6).
     *
     * @throws IllegalStateException when a patch folds under uniform weights too, or the regions
     *                               do not partition the surface
     * @return this, solved
     */
    public LayoutPatchMaps build() {
        subdividedChordCount = new ThreeConnectivityRefinement(tmesh).refine();
        regions = new PatchRegions(tmesh).build();
        PatchRegionMapper mapper = new PatchRegionMapper(tmesh, regions);
        mapByPatchId = new PatchRectangleMap[tmesh.patches.size()];
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            PatchRectangleMap map = mapper.mapPatch(patch.patchId);
            if (map.flippedTriangleCount() > 0) {
                uniformFallbackPatchCount++;
                map.solveUniform();
            }
            try {
                map.assertFoldFree();
            } catch (IllegalStateException broken) {
                throw new IllegalStateException("patch " + patch.patchId + ": "
                        + broken.getMessage(), broken);
            }
            mapByPatchId[patch.patchId] = map;
            mappedPatchCount++;
        }
        System.out.println("[patch-maps] patches=" + mappedPatchCount + " subdividedChords="
                + subdividedChordCount + " uniformFallbacks=" + uniformFallbackPatchCount);
        return this;
    }
}
