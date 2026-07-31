package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Builds a {@link PatchRectangleMap} for a patch from its region of the working copy, sizing the
 * rectangle from the sides' quantized lengths and handing the map one breakpoint per boundary arc.
 *
 * <p>Throws when the {@link PatchRegions} region and the patch disagree on a boundary vertex.
 */
public final class PatchRegionMapper {

    /** Corners of a triangle; the copy mesh is triangulated. */
    public static final int TRIANGLE_CORNERS = 3;

    public final EmbeddedTMesh tmesh;
    public final PatchRegions regions;

    /**
     * Stores the T-mesh and its computed regions.
     *
     * @param tmesh   embedded T-mesh whose patches are mapped
     * @param regions the patch regions, already built
     */
    public PatchRegionMapper(EmbeddedTMesh tmesh, PatchRegions regions) {
        this.tmesh = tmesh;
        this.regions = regions;
    }

    /**
     * Builds and solves the rectangle map for one patch.
     *
     * @param patchId patch to map
     * @throws IllegalStateException when the patch has no region, a boundary vertex is missing
     *                               from the region, a face is not a triangle, or the patch's
     *                               opposite sides disagree
     * @return the solved {@link PatchRectangleMap}
     */
    public PatchRectangleMap mapPatch(int patchId) {
        List<Integer> regionFaces = regions.copyFacesByPatch.get(patchId);
        if (regionFaces == null) {
            throw new IllegalStateException("patch " + patchId + " has no region to map");
        }
        HalfEdgeMesh copy = tmesh.topology.copy;
        Map<Integer, Integer> denseByCopyVertex = new HashMap<>();
        List<Integer> denseCopyVertex = new ArrayList<>();
        int[][] triangles = new int[regionFaces.size()][TRIANGLE_CORNERS];
        for (int faceIndex = 0; faceIndex < regionFaces.size(); faceIndex++) {
            int faceId = regionFaces.get(faceIndex);
            if (copy.faceVertexCount(faceId) != TRIANGLE_CORNERS) {
                throw new IllegalStateException("copy face " + faceId + " is not a triangle;"
                        + " the region map needs a triangulated region");
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int copyVertex = copy.faceVertexAt(faceId, corner);
                triangles[faceIndex][corner] = denseOf(copyVertex, denseByCopyVertex, denseCopyVertex);
            }
        }
        Vector3f[] positions = new Vector3f[denseCopyVertex.size()];
        for (int dense = 0; dense < denseCopyVertex.size(); dense++) {
            positions[dense] = copy.vertexPosition(denseCopyVertex.get(dense));
        }

        EmbeddedPatch patch = tmesh.patches.get(patchId);
        int[][] sideBreakLoopIndex = new int[EmbeddedPatch.SIDES][];
        int[][] sideBreakOffset = new int[EmbeddedPatch.SIDES][];
        List<Integer> boundaryLoop = new ArrayList<>();
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideArcs = patch.sideArcIds.get(side);
            List<Integer> sideNodes = patch.sideNodeIds.get(side);
            sideBreakLoopIndex[side] = new int[sideArcs.size() + 1];
            sideBreakOffset[side] = new int[sideArcs.size() + 1];
            sideBreakLoopIndex[side][0] = boundaryLoop.size();
            boundaryLoop.add(denseBoundaryVertex(patchId, side,
                    tmesh.nodes.get(sideNodes.get(0)).copyVertex, denseByCopyVertex));
            int offset = 0;
            for (int arcIndex = 0; arcIndex < sideArcs.size(); arcIndex++) {
                EmbeddedArc arc = tmesh.arcs.get(sideArcs.get(arcIndex));
                List<Integer> path = arc.path.copyVertexPath;
                boolean forward = arc.startNodeId == sideNodes.get(arcIndex);
                for (int step = 1; step < path.size(); step++) {
                    int copyVertex = forward ? path.get(step) : path.get(path.size() - 1 - step);
                    boundaryLoop.add(denseBoundaryVertex(patchId, side, copyVertex,
                            denseByCopyVertex));
                }
                offset += arc.quantizedLength;
                sideBreakLoopIndex[side][arcIndex + 1] = boundaryLoop.size() - 1;
                sideBreakOffset[side][arcIndex + 1] = offset;
            }
            boundaryLoop.remove(boundaryLoop.size() - 1);
        }
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            int lastBreak = sideBreakLoopIndex[side].length - 1;
            sideBreakLoopIndex[side][lastBreak] =
                    sideBreakLoopIndex[(side + 1) % EmbeddedPatch.SIDES][0];
        }
        int width = requireOppositeSidesEqual(patchId, 0);
        int height = requireOppositeSidesEqual(patchId, 1);
        return new PatchRectangleMap(positions, triangles, toIntArray(boundaryLoop),
                sideBreakLoopIndex, sideBreakOffset, width, height,
                toIntArray(denseCopyVertex)).build();
    }

    /**
     * The dense index of a boundary vertex, which must already have been seen in the region's
     * faces.
     *
     * @param patchId           patch being mapped, for the message
     * @param side              side being walked, for the message
     * @param copyVertex        copy vertex on the patch's boundary
     * @param denseByCopyVertex map from copy vertex id to dense index, filled from the region
     * @throws IllegalStateException when the vertex is not in the patch's region
     * @return the vertex's dense index
     */
    private int denseBoundaryVertex(int patchId, int side, int copyVertex,
            Map<Integer, Integer> denseByCopyVertex) {
        Integer dense = denseByCopyVertex.get(copyVertex);
        if (dense == null) {
            throw new IllegalStateException("boundary vertex " + copyVertex + " of patch " + patchId
                    + " side " + side + " is not in the patch's region; the layout is torn");
        }
        return dense;
    }

    /**
     * The quantized length shared by a pair of opposite sides, which the conforming layout
     * guarantees are equal.
     *
     * @param patchId patch to measure
     * @param side    the lower of the two opposite side indices, {@code 0} or {@code 1}
     * @throws IllegalStateException when the two sides disagree, so the patch is not a rectangle
     * @return the length both sides carry
     */
    private int requireOppositeSidesEqual(int patchId, int side) {
        int here = tmesh.sideQuantizedLength(patchId, side);
        int opposite = tmesh.sideQuantizedLength(patchId, side + 2);
        if (here != opposite) {
            throw new IllegalStateException("patch " + patchId + " side " + side + " has quantized"
                    + " length " + here + " but the opposite side has " + opposite + "; the layout"
                    + " is not conforming, so it has no rectangle to map onto");
        }
        return here;
    }

    /**
     * The dense index of a copy vertex, assigning the next one on first sight.
     *
     * @param copyVertex        copy mesh vertex id
     * @param denseByCopyVertex map from copy vertex id to dense index, extended here
     * @param denseCopyVertex   dense-index-to-copy-vertex list, extended here
     * @return the copy vertex's dense index
     */
    private int denseOf(int copyVertex, Map<Integer, Integer> denseByCopyVertex,
            List<Integer> denseCopyVertex) {
        Integer existing = denseByCopyVertex.get(copyVertex);
        if (existing != null) {
            return existing;
        }
        int dense = denseCopyVertex.size();
        denseByCopyVertex.put(copyVertex, dense);
        denseCopyVertex.add(copyVertex);
        return dense;
    }

    /**
     * Copies a list of integers into a primitive array.
     *
     * @param values integer list
     * @return an {@code int[]} with the same values in order
     */
    private int[] toIntArray(List<Integer> values) {
        int[] array = new int[values.size()];
        for (int index = 0; index < values.size(); index++) {
            array[index] = values.get(index);
        }
        return array;
    }
}
