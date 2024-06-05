package shell;

import java.util.ArrayList;

import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.commons.math3.util.Pair;

public class CutInfo {
    Knot knot;
    VirtualPoint external1;
    VirtualPoint external2;
    Segment cutSegment1;
    VirtualPoint kp1;
    VirtualPoint cp1;
    Knot superKnot;

    Segment kpSegment;
    ArrayList<Segment> innerNeighborSegments;

    MultiKeyMap<Integer, Segment> innerNeighborSegmentLookup;
    ArrayList<Segment> neighborSegments;

    Segment upperCutSegment;
    ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments;

    VirtualPoint topCutPoint;

    boolean needTwoNeighborMatches;
    boolean bothKnotPointsInside;
    boolean bothCutPointsOutside;

    VirtualPoint upperKnotPoint;

    Segment upperMatchSegment;
    VirtualPoint lowerKnotPoint;
    Segment lowerCutSegment;
    Shell shell;
    SegmentBalanceException sbe;
    Segment lowerMatchSegment;

    public CutInfo(Shell shell, Knot knot, VirtualPoint external1, VirtualPoint external2, Segment cutSegment1,
            VirtualPoint kp1,
            VirtualPoint cp1, Knot superKnot, Segment kpSegment, ArrayList<Segment> innerNeighborSegments,
            MultiKeyMap<Integer, Segment> innerNeighborSegmentLookup, ArrayList<Segment> neighborSegments,
            ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments,
            VirtualPoint topCutPoint, boolean needTwoNeighborMatches, boolean bothKnotPointsInside,
            boolean bothCutPointsOutside, VirtualPoint upperKnotPoint, Segment upperMatchSegment,
            Segment upperCutSegment,
            VirtualPoint lowerKnotPoint, Segment lowerMatchSegment, Segment lowerCutSegment) {
        this.shell = shell;
        this.knot = knot;
        this.external1 = external1;
        this.external2 = external2;
        this.cutSegment1 = cutSegment1;
        this.kp1 = kp1;
        this.cp1 = cp1;
        this.superKnot = superKnot;
        this.kpSegment = kpSegment;
        this.innerNeighborSegments = innerNeighborSegments;
        this.innerNeighborSegmentLookup = innerNeighborSegmentLookup;
        this.neighborSegments = neighborSegments;
        this.neighborCutSegments = neighborCutSegments;
        this.topCutPoint = topCutPoint;
        this.needTwoNeighborMatches = needTwoNeighborMatches;
        this.bothKnotPointsInside = bothKnotPointsInside;
        this.bothCutPointsOutside = bothCutPointsOutside;
        this.upperKnotPoint = upperKnotPoint;
        this.upperCutSegment = upperCutSegment;
        this.upperMatchSegment = upperMatchSegment;
        this.lowerKnotPoint = lowerKnotPoint;
        this.lowerCutSegment = lowerCutSegment;
        this.lowerMatchSegment = lowerMatchSegment;

        this.sbe = new SegmentBalanceException(shell, null, superKnot, cutSegment1,
                new Segment(kp1, external1, 0.0), upperCutSegment,
                new Segment(upperCutSegment.getOther(topCutPoint), upperCutSegment.getOther(topCutPoint), 0.0));
    }

    @Override
    public String toString() {
        return " minKnot: " + knot + " | external " + external1 + " | neighbor: " + external2 + " | Lower Cut: "
                + cutSegment1 + " | kp: " + kp1
                + " | vp: " + cp1 + " | superKnot: " + superKnot + " | kpSegment: " + kpSegment
                + " \ninnerNeighborSegments: " + innerNeighborSegments + " neighborSegments: "
                + neighborSegments + " upperCutSegment: " + upperCutSegment + " neighborCuts: "
                + InternalPathEngine.pairsToString(neighborCutSegments) +
                " upperCutPointIsOutside: " + needTwoNeighborMatches + " bothKnotPOintsInside: "
                + bothKnotPointsInside + " upperKnotPoint: " + upperKnotPoint + " upperMatchSegment: "
                + upperMatchSegment
                + " ex2: " + upperMatchSegment.getOther(upperKnotPoint);
    }

    public SegmentBalanceException genNewSegmentBalanceException() {

        return new SegmentBalanceException(shell, null, superKnot, cutSegment1,
                new Segment(kp1, external1, 0.0), upperCutSegment,
                new Segment(upperCutSegment.getOther(topCutPoint), upperCutSegment.getOther(topCutPoint), 0.0));
    }

}
