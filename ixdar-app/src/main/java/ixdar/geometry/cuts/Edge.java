package ixdar.geometry.cuts;

import ixdar.geometry.cuts.enums.RouteType;
import ixdar.geometry.knot.Segment;

public class Edge {

    public int idx;
    public boolean isNext;
    public int routeOffset;
    public int matIdx;
    public Segment acrossSegment;
    public Segment cutSegment;

    /**
     * Build a directed edge entry between two knot points, deriving its
     * {@code routeOffset} and matrix index from the {@link RouteType}
     * (next-cut or prev-cut) selected by {@code isNext}.
     *
     * @param idx column index of the target knot point
     * @param isNext {@code true} for the next-neighbor route, {@code false} for the previous-neighbor route
     * @param acrossSegment segment from the source point across to the neighbor
     * @param cutSegment segment from the target point to that same neighbor
     */
    public Edge(int idx, boolean isNext, Segment acrossSegment, Segment cutSegment) {
        this.idx = idx;
        if (isNext) {
            this.routeOffset = RouteType.nextC.routeOffset;
            this.matIdx = 2 * idx + RouteType.nextC.matOffset;
        } else {
            this.routeOffset = RouteType.prevC.routeOffset;
            this.matIdx = 2 * idx + RouteType.prevC.matOffset;
        }
        this.acrossSegment = acrossSegment;
        this.cutSegment = cutSegment;
        this.isNext = isNext;
    }

}
