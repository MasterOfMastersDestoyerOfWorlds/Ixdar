package ixdar.geometry.mesh.quadlayout.gridmap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedPatch;

/**
 * Per-corner grid coordinates of the integer grid map over the working copy,
 * packed for the iso-grid surface shader. Built once per map state, so the
 * initial and relaxed maps can be painted on the surface and compared directly.
 */
public final class GridMapIsoSurface {

    public final LayoutPatchMaps patchMaps;

    /** The map state read at build time, {@code {u0, v0, u1, v1, ...}} per patch. */
    public final double[][] uvByPatchId;

    /** Grid u per face corner, indexed {@code 3 * activeFace + corner}. */
    public double[] cornerU;

    /** Grid v per face corner, parallel to {@link #cornerU}. */
    public double[] cornerV;

    /** Whether each face folds against its patch map's orientation, by active face. */
    public boolean[] faceFlipped;

    /** Faces folding against their patch map's orientation; a healthy map has none. */
    public int flippedFaceCount;

    /**
     * Stores the patch maps and the map state to bake.
     *
     * @param patchMaps  solved per-patch maps naming the chart triangles
     * @param uvByPatchId grid coordinates per dense vertex per patch, read at build time
     */
    public GridMapIsoSurface(LayoutPatchMaps patchMaps, double[][] uvByPatchId) {
        this.patchMaps = patchMaps;
        this.uvByPatchId = uvByPatchId;
    }

    /**
     * Bakes every live patch's chart coordinates into per-corner arrays over the
     * working copy, flagging faces whose chart image is inverted.
     *
     * @return this, baked
     */
    public GridMapIsoSurface build() {
        HalfEdgeMesh copy = patchMaps.tmesh.topology.copy;
        int faceCount = copy.faceCount();
        cornerU = new double[faceCount * HalfEdgeMesh.TRIANGLE_CORNERS];
        cornerV = new double[faceCount * HalfEdgeMesh.TRIANGLE_CORNERS];
        faceFlipped = new boolean[faceCount];
        Map<Integer, Integer> activeByFaceId = new HashMap<>(faceCount * 2);
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            activeByFaceId.put(copy.faceIdAt(activeFace), activeFace);
        }
        for (EmbeddedPatch patch : patchMaps.tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            PatchRectangleMap map = patchMaps.mapByPatchId[patch.patchId];
            boolean counterClockwise = map.isCounterClockwise();
            double[] uv = uvByPatchId[patch.patchId];
            List<Integer> regionFaces = patchMaps.regions.copyFacesByPatch.get(patch.patchId);
            for (int faceIndex = 0; faceIndex < regionFaces.size(); faceIndex++) {
                int activeFace = activeByFaceId.get(regionFaces.get(faceIndex));
                int[] triangle = map.triangles[faceIndex];
                int base = activeFace * HalfEdgeMesh.TRIANGLE_CORNERS;
                for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
                    int dense = triangle[corner];
                    cornerU[base + corner] = uv[dense * GlobalGridMap.GRID_COORDINATES];
                    cornerV[base + corner] = uv[dense * GlobalGridMap.GRID_COORDINATES + 1];
                }
                double doubleArea = (cornerU[base + 1] - cornerU[base])
                        * (cornerV[base + 2] - cornerV[base])
                        - (cornerU[base + 2] - cornerU[base])
                        * (cornerV[base + 1] - cornerV[base]);
                if (doubleArea == 0.0 || (doubleArea > 0.0) != counterClockwise) {
                    faceFlipped[activeFace] = true;
                    flippedFaceCount++;
                }
            }
        }
        return this;
    }
}
