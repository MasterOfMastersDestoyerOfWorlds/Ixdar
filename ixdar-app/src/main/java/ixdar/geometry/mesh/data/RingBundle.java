package ixdar.geometry.mesh.data;

/**
 * Accessors for the {@link RingCandidates#SLOT} bundle slot, and the edge-marks and curve geometry
 * the ring nodes publish alongside it.
 */
public final class RingBundle {

    private RingBundle() {
    }

    /**
     * The ring candidates riding a bundle.
     *
     * @param bundle geometry bundle to read
     * @return the candidates, or {@code null} when the bundle carries none
     */
    public static RingCandidates of(GeometryBundle bundle) {
        return bundle.slots().get(RingCandidates.SLOT) instanceof RingCandidates rings
                ? rings
                : null;
    }

    /**
     * A copy of {@code bundle} carrying the rings, their polylines as curve geometry, and one
     * edge-marks label per ring.
     *
     * @param bundle geometry bundle to copy
     * @param rings  candidates to attach
     * @return the bundle copy
     */
    public static GeometryBundle with(GeometryBundle bundle, RingCandidates rings) {
        GeometryBundle out = bundle.withSlot(RingCandidates.SLOT, rings)
                .withSlot(CurveGeometry.SLOT, rings.curves());
        int edgeIdCeiling = edgeIdCeiling(bundle.mesh());
        for (int ring = 0; ring < rings.ringCount; ring++) {
            out = EdgeMarks.with(out, rings.markLabel(ring), rings.edgeMarks(ring, edgeIdCeiling));
        }
        return out;
    }

    /**
     * Per-active-edge mask of every ring, the form a node's {@code selection} output takes.
     *
     * @param mesh  mesh whose active edge order the mask follows
     * @param rings candidates whose cycles are marked
     * @return one flag per active edge, true where the edge lies on some ring
     */
    public static boolean[] selectionByActiveEdge(MeshTopology mesh, RingCandidates rings) {
        boolean[] byEdgeId = rings.unionEdgeMarks(edgeIdCeiling(mesh));
        boolean[] selection = new boolean[mesh.edgeCount()];
        for (int activeEdge = 0; activeEdge < selection.length; activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            selection[activeEdge] = edgeId < byEdgeId.length && byEdgeId[edgeId];
        }
        return selection;
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
