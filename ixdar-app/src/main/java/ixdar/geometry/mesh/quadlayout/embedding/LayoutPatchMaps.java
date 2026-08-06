package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * The Tutte map of every patch of a conforming layout onto the rectangle its
 * quad counts prescribe, which the union of the patch maps turns into a global
 * integer grid map.
 *
 * <p>
 * See also: LCBK19 Section 6.2
 */
public final class LayoutPatchMaps {

    /** Largest patch regions named individually in the balance report. */
    public static final int LARGEST_REGIONS_LISTED = 5;

    public final EmbeddedTMesh tmesh;

    /** The parametrization the patches' extents are measured in. */
    public final SeamlessParameterization seamless;

    /** Parametric length one quad edge should span. */
    public final double targetEdgeLength;

    /** Quads per arc and the boundary parameter, measured from the parametrization. */
    public LayoutResolution resolution;

    /**
     * Regions of the working copy, rebuilt after the refinement retriangulates it.
     */
    public PatchRegions regions;

    /**
     * Solved rectangle map of each live patch, indexed by patch id; null for a
     * retired patch.
     */
    public PatchRectangleMap[] mapByPatchId;

    /** Chords subdivided to make every region 3-connected. */
    public int subdividedChordCount;

    /** Patches mapped. */
    public int mappedPatchCount;

    /**
     * Patches whose cotangent solve folded and were re-solved with uniform weights.
     */
    public int uniformFallbackPatchCount;

    /**
     * Patches still folded after the uniform re-solve. Tutte's theorem forbids this,
     * so a non-zero count names a broken precondition upstream, not a solver problem.
     */
    public int foldedPatchCount;

    /**
     * Stores the T-mesh whose patches are mapped and the parametrization their
     * rectangles are measured from.
     *
     * @param tmesh            conforming embedded T-mesh
     * @param seamless         the parametrization the patches' extents are measured
     *                         in
     * @param targetEdgeLength parametric length one quad edge should span
     */
    public LayoutPatchMaps(EmbeddedTMesh tmesh, SeamlessParameterization seamless,
            double targetEdgeLength) {
        this.tmesh = tmesh;
        this.seamless = seamless;
        this.targetEdgeLength = targetEdgeLength;
    }

    /**
     * Refines the working copy, measures the layout's resolution, recomputes the
     * patch regions and solves every patch's map. A patch whose cotangent solve folds
     * is re-solved with uniform weights, which Tutte's theorem guarantees valid
     * (RPP17 §6); one still folded afterwards is counted, not repaired.
     *
     * @throws IllegalStateException when the regions do not partition the surface
     * @return this, solved
     */
    public LayoutPatchMaps build() {
        subdividedChordCount = new ThreeConnectivityRefinement(tmesh).refine();
        resolution = new LayoutResolution(tmesh, seamless, targetEdgeLength).build();
        regions = new PatchRegions(tmesh).build();
        reportRegionBalance();
        PatchRegionMapper mapper = new PatchRegionMapper(tmesh, regions);
        mapByPatchId = new PatchRectangleMap[tmesh.patches.size()];
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            PatchRectangleMap map = mapper.mapPatch(patch.patchId);
            int cotangentFoldCount = map.flippedTriangleCount();
            if (cotangentFoldCount > 0) {
                System.out.println("[patch-maps] patch=" + patch.patchId + " cotangentFolds="
                        + cotangentFoldCount + "; retrying with uniform weights");
                uniformFallbackPatchCount++;
                map.solveUniform();
            }
            try {
                map.assertFoldFree();
            } catch (IllegalStateException stillFolded) {
                foldedPatchCount++;
                System.out.println("[patch-maps] patch=" + patch.patchId + ": "
                        + stillFolded.getMessage());
            }
            mapByPatchId[patch.patchId] = map;
            mappedPatchCount++;
        }
        System.out.println("[patch-maps] patches=" + mappedPatchCount + " subdividedChords="
                + subdividedChordCount + " uniformFallbacks=" + uniformFallbackPatchCount
                + " stillFolded=" + foldedPatchCount);
        return this;
    }

    /**
     * Reports how the surface is shared out among the patches against the rectangle
     * each one is mapped onto. A patch holding a large share of the area on a small
     * rectangle cannot be mapped without extreme compression, so this is the first
     * place a lopsided resolution shows.
     */
    private void reportRegionBalance() {
        double totalArea = 0.0;
        int totalFaces = 0;
        List<double[]> byArea = new ArrayList<>();
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            List<Integer> faces = regions.copyFacesByPatch.get(patch.patchId);
            double area = regionArea(faces);
            totalArea += area;
            totalFaces += faces.size();
            byArea.add(new double[] { area, patch.patchId, faces.size(),
                    tmesh.sideQuadCount(patch.patchId, 0),
                    tmesh.sideQuadCount(patch.patchId, 1) });
        }
        byArea.sort((first, second) -> Double.compare(second[0], first[0]));
        System.out.printf("[patch-maps] region balance: faces=%d area=%.4f over %d patches;"
                + " largest by area:%n", totalFaces, totalArea, byArea.size());
        for (int index = 0; index < Math.min(LARGEST_REGIONS_LISTED, byArea.size()); index++) {
            double[] entry = byArea.get(index);
            System.out.printf("[patch-maps]   patch %d: %.2f%% of area, %d faces, rectangle %dx%d%n",
                    (int) entry[1], 100.0 * entry[0] / Math.max(1.0e-30, totalArea),
                    (int) entry[2], (int) entry[3], (int) entry[4]);
        }
    }

    /**
     * The surface area of one patch's region on the working copy.
     *
     * @param faces the region's copy faces
     * @return the summed triangle area
     */
    private double regionArea(List<Integer> faces) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        Vector3f first = new Vector3f();
        Vector3f second = new Vector3f();
        Vector3f third = new Vector3f();
        double area = 0.0;
        for (int faceId : faces) {
            copy.vertexPosition(copy.faceVertexAt(faceId, 0), first);
            copy.vertexPosition(copy.faceVertexAt(faceId, 1), second);
            copy.vertexPosition(copy.faceVertexAt(faceId, 2), third);
            area += second.sub(first).cross(third.sub(first)).length() / 2.0;
        }
        return area;
    }
}
