package shell;

public class FixedCutBothCutsOutside extends FixedCut {

    public FixedCutBothCutsOutside(CutInfo c) {
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

        VirtualPoint leftNeighbor = upperCutSegment.getOther(upperKnotPoint);

        VirtualPoint rightNeighbor = lowerCutSegment.getOther(lowerKnotPoint);
        for (int a = 0; a < knot.knotPoints.size(); a++) {

            VirtualPoint knotPoint21 = knot.knotPoints.get(a);
            VirtualPoint knotPoint22 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
            Segment cutSegment2 = knot.getSegment(knotPoint21, knotPoint22);

            if (cutSegment1.equals(cutSegment2)) {
                continue;
            } else {

                boolean leftHasOneOut = Utils.marchUntilHasOneKnotPoint(cutSegment1.first, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                boolean rightHasOneOut = Utils.marchUntilHasOneKnotPoint(cutSegment1.last, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                if (!(leftHasOneOut && rightHasOneOut) || !((cutSegment1.contains(kp1) || cutSegment2.contains(kp1))
                        && (cutSegment1.contains(upperKnotPoint) || cutSegment2.contains(upperKnotPoint)))) {
                    shell.buff.add("ONE SIDE WOULD BE UNBALANCED " + cutSegment2);

                    continue;
                }
                double delta = Double.MAX_VALUE;
                VirtualPoint mirror1 = null;
                VirtualPoint mirror2 = null;
                if (!cutSegment1.first.equals(upperKnotPoint) && !cutSegment1.first.equals(lowerKnotPoint)) {
                    mirror1 = cutSegment1.first;
                }
                if (!cutSegment1.last.equals(upperKnotPoint) && !cutSegment1.last.equals(lowerKnotPoint)) {
                    if (mirror1 == null) {
                        mirror1 = cutSegment1.last;
                    } else {
                        mirror2 = cutSegment1.last;
                    }
                }
                if (!cutSegment2.first.equals(upperKnotPoint) && !cutSegment2.first.equals(lowerKnotPoint)) {
                    if (mirror1 == null) {
                        mirror1 = cutSegment2.first;
                    } else if (mirror2 == null) {
                        mirror2 = cutSegment2.first;
                    }
                }
                if (!cutSegment2.last.equals(upperKnotPoint) && !cutSegment2.last.equals(lowerKnotPoint)) {
                    if (mirror1 == null) {
                        mirror1 = cutSegment2.last;
                    } else if (mirror2 == null) {
                        mirror2 = cutSegment2.last;
                    }
                }
                if (cutSegment1.equals(kpSegment)) {
                    if (mirror2 == null) {
                        mirror2 = cutSegment2.getOther(mirror1);
                    }
                }

                if (cutSegment2.equals(kpSegment)) {
                    if (mirror2 == null) {
                        mirror2 = cutSegment1.getOther(mirror1);
                    }
                }

                double d2 = Double.MAX_VALUE;
                double d1 = Double.MAX_VALUE;
                if (mirror2 == null || mirror1 == null) {
                    new CutMatchList(shell, sbe);
                    throw new SegmentBalanceException(sbe);
                }
                Segment s11 = knot.getSegment(leftNeighbor, mirror1);
                Segment s12 = knot.getSegment(rightNeighbor, mirror2);
                boolean replicatesCutSegment = s11.equals(lowerCutSegment) || s12.equals(lowerCutSegment)
                        || s11.equals(upperCutSegment) || s12.equals(upperCutSegment);
                d1 = s11.distance + s12.distance - cutSegment2.distance;
                if (!replicatesCutSegment) {
                    delta = d1 < delta ? d1 : delta;
                }

                Segment s21 = knot.getSegment(leftNeighbor, mirror2);
                Segment s22 = knot.getSegment(rightNeighbor, mirror1);
                boolean replicatesCutSegment2 = s21.equals(lowerCutSegment) || s22.equals(lowerCutSegment)
                        || s21.equals(upperCutSegment) || s22.equals(upperCutSegment);
                d2 = s21.distance + s22.distance - cutSegment2.distance;
                if (!replicatesCutSegment2) {
                    delta = d2 < delta ? d2 : delta;
                }

                if (delta < minDelta) {
                    if (delta == d1) {
                        matchSegmentToCutPoint1 = s11;
                        matchSegmentToCutPoint2 = s12;
                        shell.buff.add("d1");

                    } else if (delta == d2) {
                        matchSegmentToCutPoint1 = s21;
                        matchSegmentToCutPoint2 = s22;
                        shell.buff.add("d2");
                    }

                    cutSegment2Final = cutSegment2;
                    cutSegmentFinal = cutSegment1;

                    shell.buff.add("Group1 cut1: " + cutSegmentFinal + " cut2: " + cutSegment2 + " matches: " +
                            s11 + " " + s12);
                    shell.buff.add("Group2 cut1: " + cutSegmentFinal + " cut2: " + cutSegment2 + " matches: " +
                            s21 + " " + s22);

                    minDelta = delta;
                    overlapping = 2;

                }
            }
        }
        if (overlapping == 2) {
            shell.buff.add("cut1: " + cutSegmentFinal + " cut2: " + cutSegment2Final + " matches: " +
                    matchSegmentToCutPoint1 + " " + matchSegmentToCutPoint2 + " " + matchSegmentOuterKnotPointFinal);
            CutMatchList result = new CutMatchList(shell, sbe);
            result.addTwoCutTwoMatch( cutSegment2Final, new Segment[] {
                            matchSegmentToCutPoint1,
                            matchSegmentToCutPoint2,
                            matchSegmentOuterKnotPointFinal,
                    },
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
