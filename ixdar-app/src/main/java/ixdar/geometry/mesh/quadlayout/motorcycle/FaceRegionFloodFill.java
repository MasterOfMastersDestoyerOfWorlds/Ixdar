package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.Arrays;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * BFS flood fill of active faces into connected regions separated by a caller
 * supplied set of barrier edges. {@link MotorcycleGraph} uses it with every
 * trace-crossed edge as a barrier (T-mesh regions);
 * {@code LayoutExtraction} re-runs it with only positively quantized arcs as
 * barriers (final layout regions).
 */
public final class FaceRegionFloodFill {

    public final SeamlessParameterization seamless;

    /** Region index per active face; populated by {@link #fill(boolean[])}. */
    public int[] regionIdByActiveFace;

    /** Number of distinct regions found by {@link #fill(boolean[])}. */
    public int regionCount;

    /**
     * Stores the parametrization whose face/edge adjacency drives the fill.
     *
     * @param seamless built seamless parametrization supplying the mesh and the
     *                 id-to-active index maps
     */
    public FaceRegionFloodFill(SeamlessParameterization seamless) {
        this.seamless = seamless;
    }

    /**
     * Flood-fill faces into regions, refusing to cross barrier edges. Face
     * granularity is approximate near barriers (a trace crosses triangle
     * interiors), which is fine for the display coloring this feeds.
     *
     * @param barrierByActiveEdge true per active edge that separates regions
     * @return region index per active face, also kept in
     *         {@link #regionIdByActiveFace}
     */
    public int[] fill(boolean[] barrierByActiveEdge) {
        int faceCount = seamless.mesh.faceCount();
        regionIdByActiveFace = new int[faceCount];
        Arrays.fill(regionIdByActiveFace, -1);
        int nextRegionId = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            if (regionIdByActiveFace[activeFace] >= 0) {
                continue;
            }
            ArrayList<Integer> frontier = new ArrayList<>();
            frontier.add(activeFace);
            regionIdByActiveFace[activeFace] = nextRegionId;
            int head = 0;
            while (head < frontier.size()) {
                int frontierActiveFace = frontier.get(head++);
                int faceId = seamless.mesh.faceIdAt(frontierActiveFace);
                for (int edge = 0; edge < SeamlessParameterization.CORNERS_PER_FACE; edge++) {
                    int edgeId = seamless.mesh.faceEdgeAt(faceId, edge);
                    int activeEdge = seamless.crossField.edgeIdToActive.get(edgeId);
                    if (barrierByActiveEdge[activeEdge]) {
                        continue;
                    }
                    HalfEdgeMesh.EdgeFaceIds edgeFaces = seamless.mesh.edgeFaceIds(activeEdge);
                    int neighborFaceId = edgeFaces.faceA == faceId ? edgeFaces.faceB : edgeFaces.faceA;
                    if (neighborFaceId < 0) {
                        continue;
                    }
                    int neighborActive = seamless.crossField.faceIdToActive.get(neighborFaceId);
                    if (regionIdByActiveFace[neighborActive] >= 0) {
                        continue;
                    }
                    regionIdByActiveFace[neighborActive] = nextRegionId;
                    frontier.add(neighborActive);
                }
            }
            nextRegionId++;
        }
        regionCount = nextRegionId;
        return regionIdByActiveFace;
    }
}
