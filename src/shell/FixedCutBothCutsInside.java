package shell;

public class FixedCutBothCutsInside extends FixedCut {

    public FixedCutBothCutsInside(CutInfo c) {
        super(c);
    }

    @Override
    public CutMatchList findCutMatchListFixedCut() throws SegmentBalanceException {

        double minDelta = Double.MAX_VALUE;
        int overlapping = -1;
        Segment matchSegmentToCutPoint1 = null;
        Segment matchSegmentToCutPoint2 = null;
        Segment matchSegmentOuterKnotPointFinal = null;
        Segment cutSegmentFinal = null;
        Segment cutSegment2Final = null;
        Segment leftCut = neighborCutSegments.get(0).getFirst();
        VirtualPoint leftNeighbor = neighborCutSegments.get(0).getSecond();
        VirtualPoint leftKnotPoint = leftCut.getOther(leftNeighbor);
        VirtualPoint leftCutPoint = cutSegment1.contains(leftKnotPoint) ? cp1 : topCutPoint;

        Segment rightCut = neighborCutSegments.get(1).getFirst();
        VirtualPoint rightNeighbor = neighborCutSegments.get(1).getSecond();
        VirtualPoint rightKnotPoint = rightCut.getOther(rightNeighbor);
        VirtualPoint rightCutPoint = cutSegment1.contains(rightKnotPoint) ? cp1 : topCutPoint;

        Segment s11 = knot.getSegment(leftKnotPoint, rightCutPoint);
        Segment s12 = knot.getSegment(leftNeighbor, leftCutPoint);
        Segment s13 = knot.getSegment(rightKnotPoint, rightNeighbor);
        Segment cut1 = leftCut;
        double d1 = s11.distance + s12.distance + s13.distance + -cut1.distance;
        double delta = d1;

        Segment s21 = knot.getSegment(rightKnotPoint, leftCutPoint);
        Segment s22 = knot.getSegment(rightNeighbor, rightCutPoint);
        Segment s23 = knot.getSegment(leftKnotPoint, leftNeighbor);
        Segment cut2 = rightCut;
        double d2 = s21.distance + s22.distance + s23.distance - cut2.distance;
        delta = d2 < delta ? d2 : delta;

        if (delta < minDelta) {
            if (delta == d1) {
                matchSegmentToCutPoint1 = s11;
                matchSegmentToCutPoint2 = s12;
                matchSegmentOuterKnotPointFinal = s13;
                cutSegment2Final = cut1;
                shell.buff.add("d1");
                neighborCutSegments.remove(1);
                neighborSegments.remove(rightCut);

            } else if (delta == d2) {
                matchSegmentToCutPoint1 = s21;
                matchSegmentToCutPoint2 = s22;
                matchSegmentOuterKnotPointFinal = s23;
                cutSegment2Final = cut2;
                shell.buff.add("d2");
                neighborCutSegments.remove(0);
                neighborSegments.remove(leftCut);
            }
            shell.buff.add("Group1 cut1: " + cutSegmentFinal + " cut2: " + cut1 + " matches: " +
                    s11 + " " + s12 + " " + s13);
            shell.buff.add("Group2 cut1: " + cutSegmentFinal + " cut2: " + cut2 + " matches: " +
                    s21 + " " + s22 + " " + s23);

            cutSegmentFinal = cutSegment1;

            minDelta = delta;
            overlapping = 2;

        }
        if (overlapping == 2) {
            shell.buff.add("cut1: " + cutSegmentFinal + " cut2: " + cutSegment2Final + " matches: " +
                    matchSegmentToCutPoint1 + " " + matchSegmentToCutPoint2 + " " + matchSegmentOuterKnotPointFinal);
            CutMatchList result = new CutMatchList(shell, sbe);

            result.addTwoCutTwoMatch(cutSegment2Final,
                     new Segment[] { matchSegmentToCutPoint1,
                            matchSegmentToCutPoint2,
                            matchSegmentOuterKnotPointFinal },
                    true, c);
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            CutMatchList cml = new CutMatchList(shell, sbe);
            cml.addDumbCutMatch(knot, superKnot);
            throw new SegmentBalanceException(sbe);
        }
    }

}
