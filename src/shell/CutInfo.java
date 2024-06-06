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

    ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments;

    VirtualPoint upperCutPoint;

    boolean needTwoNeighborMatches;
    boolean bothKnotPointsInside;
    boolean bothCutPointsOutside;

    VirtualPoint upperKnotPoint;
    VirtualPoint upperExternal;
    Segment upperCutSegment;
    Segment upperMatchSegment;

    VirtualPoint lowerKnotPoint;
    VirtualPoint lowerExternal;
    Segment lowerCutSegment;
    Segment lowerMatchSegment;
    Shell shell;
    SegmentBalanceException sbe;
    VirtualPoint lowerCutPoint;

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
        this.superKnot = superKnot;
        this.external1 = external1;
        this.external2 = external2;
        this.cutSegment1 = cutSegment1;
        this.kp1 = kp1;
        this.cp1 = cp1;
        this.kpSegment = kpSegment;

        this.innerNeighborSegments = innerNeighborSegments;
        this.innerNeighborSegmentLookup = innerNeighborSegmentLookup;
        this.neighborSegments = neighborSegments;
        this.neighborCutSegments = neighborCutSegments;

        this.needTwoNeighborMatches = needTwoNeighborMatches;
        this.bothKnotPointsInside = bothKnotPointsInside;
        this.bothCutPointsOutside = bothCutPointsOutside;

        this.upperKnotPoint = upperKnotPoint;
        this.upperCutPoint = topCutPoint;
        this.upperCutSegment = upperCutSegment;
        this.upperMatchSegment = upperMatchSegment;
        this.upperExternal = upperMatchSegment.getOther(upperKnotPoint);

        this.lowerKnotPoint = lowerKnotPoint;
        this.lowerCutSegment = lowerCutSegment;
        this.lowerCutPoint = lowerCutSegment.getOther(lowerKnotPoint);
        this.lowerMatchSegment = lowerMatchSegment;
        this.lowerExternal = lowerMatchSegment.getOther(lowerKnotPoint);

        this.sbe = new SegmentBalanceException(shell, null, this);
    }

    public CutInfo(Shell shell, VirtualPoint lowerKnotPoint, VirtualPoint lowerCutPoint, VirtualPoint lowerExternal,
            VirtualPoint upperKnotPoint, VirtualPoint upperCutPoint, VirtualPoint upperExternal, Knot superKnot) {
        this.shell = shell;
        this.knot = superKnot;
        this.superKnot = superKnot;

        this.lowerExternal = lowerExternal;
        this.lowerKnotPoint = lowerKnotPoint;
        this.lowerCutSegment = superKnot.getSegment(lowerKnotPoint, lowerCutPoint);
        this.lowerCutPoint = lowerCutSegment.getOther(lowerKnotPoint);
        this.lowerMatchSegment = lowerKnotPoint.getClosestSegment(lowerExternal, null);

        this.neighborSegments = new ArrayList<>();

        this.upperMatchSegment = upperKnotPoint.getClosestSegment(upperExternal, lowerMatchSegment);
        this.upperCutPoint = upperCutPoint;
        this.upperExternal = upperExternal;
        this.upperCutSegment = superKnot.getSegment(upperCutPoint, upperKnotPoint);
    }

    public CutInfo(Shell shell, Segment cutSegmentFinal, Segment matchSegment1Final, Segment cutSegment2Final,
            Segment matchSegment2Final, Knot knot) {
        this.shell = shell;
        this.lowerCutSegment = cutSegmentFinal;
        this.lowerMatchSegment = matchSegment1Final;
        this.upperCutSegment = cutSegment2Final;
        this.upperMatchSegment = matchSegment2Final;
        this.superKnot = knot;
        // TODO Auto-generated constructor stub
    }

    @Override
    public String toString() {
        return " minKnot: " + knot + " | external " + external1 + " | neighbor: " + external2 + " | cutSegment1: "
                + cutSegment1 + " | kp1: " + kp1 + " | cp1: " + cp1 + " | superKnot: " + superKnot + " | kpSegment: "
                + kpSegment +

                " \ninnerNeighborSegments: " + innerNeighborSegments + " neighborSegments: "
                + neighborSegments + " innerNeighborSegmentLookup " + innerNeighborSegmentLookup + " neighborCuts: "
                + Utils.pairsToString(neighborCutSegments) + "\n" +

                " upperCutPointIsOutside: " + needTwoNeighborMatches + " bothKnotPOintsInside: "
                + bothKnotPointsInside + "\n" +

                " lowerCutSegment: " + lowerCutSegment + " lowerKnotPoint: " + lowerKnotPoint + " lowerCutPoint"
                + lowerCutPoint + " lowerMatchSegment: "
                + lowerMatchSegment + " lowerExternal: " + lowerExternal + "\n" +

                " upperCutSegment: " + upperCutSegment + " upperKnotPoint: " + upperKnotPoint + " upperCutPoint"
                + upperCutPoint + " upperMatchSegment: "
                + upperMatchSegment + " upperExternal: " + upperExternal;
    }

    public SegmentBalanceException genNewSegmentBalanceException() {

        return new SegmentBalanceException(shell, null, this);
    }

}
