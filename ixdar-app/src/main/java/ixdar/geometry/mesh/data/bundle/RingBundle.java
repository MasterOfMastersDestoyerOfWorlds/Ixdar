package ixdar.geometry.mesh.data.bundle;

import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Sizing for the edge-id-indexed masks ring marks are stored in.
 */
public final class RingBundle {

    private RingBundle() {
    }

    /**
     * One past the largest live edge id, the length an edge-id-indexed array needs.
     *
     * @param mesh mesh to measure
     * @return the smallest length that indexes every live edge id
     */
    public static int edgeIdCeiling(MeshTopology mesh) {
        int maxEdgeId = 0;
        for (int index = 0; index < mesh.edgeCount(); index++) {
            maxEdgeId = Math.max(maxEdgeId, mesh.edgeIdAt(index));
        }
        return maxEdgeId + 1;
    }
}
