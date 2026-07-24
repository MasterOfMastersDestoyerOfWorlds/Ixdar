package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Builds a {@link PatchRectangleMap} for a patch from its region of the working copy, sizing the
 * rectangle from the sides' quantized lengths floored to {@link #MIN_RECTANGLE_SIDE}.
 *
 * <p>Throws when the {@link PatchRegions} region and the patch disagree on a boundary vertex.
 */
public final class PatchRegionMapper {

    /** Smallest rectangle side length, so a unit-quantized side still maps to a real rectangle. */
    public static final double MIN_RECTANGLE_SIDE = 1.0;

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
     *                               from the region, or a face is not a triangle
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
        int[] cornerAt = new int[EmbeddedPatch.SIDES];
        List<Integer> boundaryLoop = new ArrayList<>();
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            cornerAt[side] = boundaryLoop.size();
            for (int copyVertex : sideCopyVertices(patchId, side)) {
                Integer dense = denseByCopyVertex.get(copyVertex);
                if (dense == null) {
                    throw new IllegalStateException("boundary vertex " + copyVertex + " of patch "
                            + patchId + " side " + side + " is not in the patch's region; the"
                            + " layout is torn");
                }
                boundaryLoop.add(dense);
            }
        }
        double width = Math.max(MIN_RECTANGLE_SIDE, Math.max(
                tmesh.sideQuantizedLength(patchId, 0), tmesh.sideQuantizedLength(patchId, 2)));
        double height = Math.max(MIN_RECTANGLE_SIDE, Math.max(
                tmesh.sideQuantizedLength(patchId, 1), tmesh.sideQuantizedLength(patchId, 3)));
        return new PatchRectangleMap(positions, triangles, toIntArray(boundaryLoop), cornerAt,
                width, height, toIntArray(denseCopyVertex)).build();
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
     * The copy vertices along one side of a patch, from the side's start corner up to but not
     * including the next corner, oriented so consecutive sides join without repeating a vertex.
     *
     * @param patchId patch to read
     * @param side    side index in {@code [0, 4)}
     * @return the side's copy vertex ids in walking order, excluding the trailing corner
     */
    private List<Integer> sideCopyVertices(int patchId, int side) {
        EmbeddedPatch patch = tmesh.patches.get(patchId);
        List<Integer> sideNodes = patch.sideNodeIds.get(side);
        List<Integer> sideArcs = patch.sideArcIds.get(side);
        List<Integer> vertices = new ArrayList<>();
        vertices.add(tmesh.nodes.get(sideNodes.get(0)).copyVertex);
        for (int arcIndex = 0; arcIndex < sideArcs.size(); arcIndex++) {
            EmbeddedArc arc = tmesh.arcs.get(sideArcs.get(arcIndex));
            List<Integer> path = arc.path.copyVertexPath;
            boolean forward = arc.startNodeId == sideNodes.get(arcIndex);
            if (forward) {
                for (int step = 1; step < path.size(); step++) {
                    vertices.add(path.get(step));
                }
            } else {
                for (int step = path.size() - 2; step >= 0; step--) {
                    vertices.add(path.get(step));
                }
            }
        }
        vertices.remove(vertices.size() - 1);
        return vertices;
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
