package shell.cuts;

import shell.cuts.enums.RouteType;
import shell.knot.Segment;

public class Edge {

    public int idx;
    public boolean isNext;
    public int routeOffset;
    public int matIdx;
    public Segment acrossSegment;
    public Segment cutSegment;

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
