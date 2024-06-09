package shell;

public class FixedCutBothKnotPointsOutside extends FixedCut {

    public FixedCutBothKnotPointsOutside(CutInfo c) {
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
        Knot superKnot = c.superKnot;
        VirtualPoint upperKnotPoint = c.upperKnotPoint;
        int ukpidx = superKnot.knotPointsFlattened.indexOf(upperKnotPoint);
        VirtualPoint vp1 = superKnot.getPrev(ukpidx);
        VirtualPoint vp2 = superKnot.getNext(ukpidx);
        VirtualPoint upperNeighbor = null;
        if(vp1.equals(c.upperCutPoint)){
            upperNeighbor = vp2;
        }else{
            upperNeighbor = vp1;
        }
        Segment upperCut = superKnot.getSegment(c.upperKnotPoint, upperNeighbor);

        
        VirtualPoint lowerKnotPoint = c.lowerKnotPoint;
        int lkpidx = superKnot.knotPointsFlattened.indexOf(lowerKnotPoint);
        VirtualPoint vp3 = superKnot.getPrev(lkpidx);
        VirtualPoint vp4 = superKnot.getNext(lkpidx);
        VirtualPoint lowerNeighbor = null;
        if(vp3.equals(c.lowerCutPoint)){
            lowerNeighbor = vp4;
        }else{
            lowerNeighbor = vp3;
        }

        Segment lowerCut = superKnot.getSegment(c.lowerKnotPoint, lowerNeighbor);
        shell.buff.add("lowerNieghbor: " + lowerNeighbor);
        shell.buff.add("lowerCut: " + lowerCut);
        
        shell.buff.add("upperNieghbor: " + upperNeighbor);
        shell.buff.add("upperCut: " + upperCut);
        
        Segment innerNeighborSegment = knot.getSegment(c.lowerCutPoint, c.upperCutPoint);
        
        Segment s11 = knot.getSegment(c.lowerKnotPoint, c.upperCutPoint);
        Segment s12 = knot.getSegment(lowerNeighbor, c.lowerCutPoint);
        Segment s13 = knot.getSegment(c.upperKnotPoint, upperNeighbor);
        Segment cut1 = lowerCut;
        double d1 = s11.distance + s12.distance + s13.distance + -cut1.distance;
        double delta = d1;

        Segment s21 = knot.getSegment(c.upperKnotPoint, c.lowerCutPoint);
        Segment s22 = knot.getSegment(upperNeighbor, c.upperCutPoint);
        Segment s23 = knot.getSegment(c.lowerKnotPoint, lowerNeighbor);
        Segment cut2 = upperCut;
        double d2 = s21.distance + s22.distance + s23.distance - cut2.distance;
        delta = d2 < delta ? d2 : delta;

        if (delta < minDelta) {
            if (delta == d1) {
                matchSegmentToCutPoint1 = s11;
                matchSegmentToCutPoint2 = s12;
                matchSegmentOuterKnotPointFinal = s13;
                cutSegment2Final = cut1;
                shell.buff.add("d1");

            } else if (delta == d2) {
                matchSegmentToCutPoint1 = s21;
                matchSegmentToCutPoint2 = s22;
                matchSegmentOuterKnotPointFinal = s23;
                cutSegment2Final = cut2;
                shell.buff.add("d2");
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
            CutMatchList result = new CutMatchList(shell, sbe, c.superKnot);

            result.addCutMatch(new Segment[]{cutSegment2Final, innerNeighborSegment},
                     new Segment[] { matchSegmentToCutPoint1,
                            matchSegmentToCutPoint2,
                            matchSegmentOuterKnotPointFinal }, c,"FixedCutBothKnotPointsOutside");
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            CutMatchList cml = new CutMatchList(shell, sbe, c.superKnot);
            cml.addDumbCutMatch(knot, superKnot, "FixedCutBothKnotPointsOutsideNoAvailableCuts");
            throw new SegmentBalanceException(sbe);
        }
    }

}
