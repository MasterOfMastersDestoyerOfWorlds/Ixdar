package shell;

import java.util.ArrayList;

import org.apache.commons.math3.util.Pair;

public class FixedCutTwoMatches extends FixedCut {

    public FixedCutTwoMatches(CutInfo c) {
        super(c);
    }

    @Override
    public CutMatchList findCutMatchListFixedCut() throws SegmentBalanceException {
        // TODO Auto-generated method stubSegmentBalanceException sbe = new
        // SegmentBalanceException(shell, null, superKnot, cutSegment1,

        if (upperMatchSegment == null) {
            new CutMatchList(shell, sbe);
            throw new SegmentBalanceException(sbe);
        }
        if (!(knot.contains(cutSegment1.first) && knot.contains(cutSegment1.last))) {
            new CutMatchList(shell, sbe);
            throw new SegmentBalanceException(sbe);
        }
        if ((knot.contains(external1) || knot.contains(external2))) {
            new CutMatchList(shell, sbe);
            throw new SegmentBalanceException(sbe);
        }

        ArrayList<VirtualPoint> uniqueNeighborPoints = new ArrayList<>();
        VirtualPoint otherNeighborPoint = external2;
        VirtualPoint otherNeighborPoint2 = external2;
        uniqueNeighborPoints.add(external2);
        for (Pair<Segment, VirtualPoint> p : neighborCutSegments) {
            if (!p.getSecond().equals(external2)) {
                uniqueNeighborPoints.add(p.getSecond());
            }
        }
        shell.buff.add(uniqueNeighborPoints);
        int numUnique = uniqueNeighborPoints.size();
        if (numUnique > 1) {
            uniqueNeighborPoints.remove(external2);
            otherNeighborPoint = uniqueNeighborPoints.get(0);
        }
        if (numUnique > 2) {
            shell.buff.add("REEE " + uniqueNeighborPoints);

            uniqueNeighborPoints.remove(otherNeighborPoint);
            otherNeighborPoint2 = uniqueNeighborPoints.get(0);
        }
        uniqueNeighborPoints.add(otherNeighborPoint);
        uniqueNeighborPoints.add(external2);
        shell.buff.add("unique neighbor points: " + numUnique +
                " otherNeghborPoint2: " + otherNeighborPoint2 + " onp: " + otherNeighborPoint + " ex2: " + external2);

        double minDelta = Double.MAX_VALUE;
        int overlapping = -1;
        Segment matchSegmentToCutPoint1 = null;
        Segment matchSegmentToCutPoint2 = null;
        Segment matchSegmentOuterKnotPointFinal = null;
        Segment matchSegmentAcrossFinal = null;
        Segment cutSegmentFinal = null;
        Segment cutSegment2Final = null;
        CutMatchList result = null;
        boolean wouldOrphan = Utils.wouldOrphan(c.lowerCutSegment.getOther(c.lowerKnotPoint), c.lowerKnotPoint, c.upperCutPoint, c.upperKnotPoint, c.superKnot.knotPoints);

        shell.buff.add("WOULD ORPHAN? : " + wouldOrphan);
        

        if (!bothKnotPointsInside && !neighborSegments.contains(knot.getSegment(cp1, external2)) && !wouldOrphan) {
            shell.buff.add("REE CUT");
            
            overlapping = 1;
            result = new CutMatchList(shell, sbe);
            shell.buff.add("ign " + c.lowerMatchSegment + " "  + knot.getSegment(cp1, external2));
            
            result.addCut(cutSegment1, c.lowerMatchSegment, knot.getSegment(cp1, external2),
                    kp1, cp1, c, false, true);
                    shell.buff.add("vum " + result);
                    
            minDelta = result.delta;
        }
        if (bothKnotPointsInside && !neighborSegments.contains(knot.getSegment(cp1, external2)) && !wouldOrphan) {
            shell.buff.add("complex CUT");
            overlapping = 2;
            Segment innerSegment1 = null;
            Segment innerSegment2 = null;
            Segment innerSegment3 = null;
            if (neighborCutSegments.size() > 0) {
                matchSegmentToCutPoint1 = neighborCutSegments.get(0).getFirst();
                innerSegment1 = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(matchSegmentToCutPoint1),
                        Segment.getLastOrderId(matchSegmentToCutPoint1));
            }
            if (neighborCutSegments.size() > 1) {
                matchSegmentToCutPoint2 = neighborCutSegments.get(1).getFirst();
                innerSegment2 = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(matchSegmentToCutPoint1),
                        Segment.getLastOrderId(matchSegmentToCutPoint1));
            }
            if (neighborCutSegments.size() > 2) {
                matchSegmentOuterKnotPointFinal = neighborCutSegments.get(2).getFirst();
                innerSegment3 = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(matchSegmentToCutPoint1),
                        Segment.getLastOrderId(matchSegmentToCutPoint1));
            }
            if (innerSegment1 != null && innerSegment1.equals(innerSegment2)
                    || innerSegment1 != null && innerSegment1.equals(innerSegment3)) {
                cutSegment2Final = innerSegment1;
            } else if (innerSegment2 != null && innerSegment2.equals(innerSegment3)) {
                cutSegment2Final = innerSegment2;
            } else {
                cutSegment2Final = innerSegment1;
            }

            Pair<VirtualPoint, VirtualPoint> mirrors = null;
            if (cutSegment1.contains(upperKnotPoint)) {
                mirrors = Utils.marchLookup(knot, upperKnotPoint, cutSegment1.getOther(upperKnotPoint),
                        cutSegment2Final);
            } else {
                mirrors = Utils.marchLookup(knot, upperKnotPoint, cutSegment2Final.getOther(upperKnotPoint),
                        cutSegment1);

            }



            matchSegmentAcrossFinal = mirrors.getFirst().getClosestSegment(external2, null);
            shell.buff.add(c);
            if (innerSegment1 == null) {
                new CutMatchList(shell, sbe);
                throw new SegmentBalanceException(sbe);
            }
            cutSegmentFinal = cutSegment1;
            CutMatchList simpleCut = new CutMatchList(shell, sbe);
            simpleCut.addTwoCutTwoMatch(cutSegment2Final, new Segment[] {
                    matchSegmentToCutPoint1,
                    matchSegmentToCutPoint2,
                    matchSegmentOuterKnotPointFinal,
                    matchSegmentAcrossFinal },
                    bothKnotPointsInside, c);
            minDelta = simpleCut.delta;
            result = simpleCut;
            shell.buff.add("simple cut: " + simpleCut);
            
            shell.buff.add("SIMPLE CUT MATCH: " + matchSegmentAcrossFinal + " matches: "
                    + matchSegmentOuterKnotPointFinal + " " + matchSegmentToCutPoint1 + " " + matchSegmentToCutPoint2
                    + "CUTS: " + cutSegmentFinal + " " + cutSegment2Final + " minDelta " + minDelta + "");
        }
        // TODO: I think the first cut we check against needs to be the simple cut
        for (int a = 0; a < knot.knotPoints.size(); a++) {

            VirtualPoint knotPoint21 = knot.knotPoints.get(a);
            VirtualPoint knotPoint22 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
            Segment cutSegment2 = knot.getSegment(knotPoint21, knotPoint22);
            // I think that the continue state instead of being do the ct sgments partially
            // overlapp each other, should instead
            // be like
            // in the both knot points inside we only want to check cuts where each section
            // has one knotpoint cna we just check march contain for knot point one and knot
            // point two breaking at the next cut segment?
            if ((cutSegment1.partialOverlaps(cutSegment2)) && !cutSegment2.equals(kpSegment)) {
                shell.buff.add("OVERLAPP CONTINUING " + cutSegment2);

                continue;
            }
            if (bothKnotPointsInside) {
                boolean leftHasOneOut = Utils.marchUntilHasOneKnotPoint(cutSegment1.first, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                boolean rightHasOneOut = Utils.marchUntilHasOneKnotPoint(cutSegment1.last, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                if (!(leftHasOneOut && rightHasOneOut) || !((cutSegment1.contains(kp1) || cutSegment2.contains(kp1))
                        && (cutSegment1.contains(upperKnotPoint) || cutSegment2.contains(upperKnotPoint)))) {
                    shell.buff.add("ONE SIDE WOULD BE UNBALANCED " + cutSegment2);

                    continue;
                }

            }
            if (cutSegment1.equals(cutSegment2)) {
                continue;
            } else {
                double delta = Double.MAX_VALUE;

                VirtualPoint mirror1;
                VirtualPoint mirror21 = cutSegment1.getOther(kp1);

                VirtualPoint mirror22;
                Pair<VirtualPoint, VirtualPoint> mirrors = Utils.marchLookup(knot, kp1, cutSegment1.getOther(kp1),
                        cutSegment2);

                mirror1 = mirrors.getFirst();
                mirror22 = mirrors.getSecond();

                shell.buff.add(kp1);
                double d2 = Double.MAX_VALUE, d1 = Double.MAX_VALUE;
                if (bothKnotPointsInside) {
                    VirtualPoint mirrorKP = mirror22, mirrorCP = mirror21;

                    if (mirror21.equals(upperKnotPoint)) {
                        mirrorKP = mirror21;
                        mirrorCP = mirror22;
                    }
                    shell.buff.add("cuttSegment2: " + cutSegment2);

                    shell.buff.add("mirror1: " + mirror1);
                    shell.buff.add("mirrorKP: " + mirrorKP);
                    shell.buff.add("mirrorCP: " + mirrorCP);
                    if (numUnique == 1) {
                        CutMatchList cutMatch2 = new CutMatchList(shell, sbe);
                        cutMatch2.addTwoCutTwoMatch(cutSegment2,
                                new Segment[] {
                                        knot.getSegment(external2, mirror1),
                                        knot.getSegment(external2, mirrorCP) },
                                bothKnotPointsInside, c);

                        d2 = cutMatch2.delta;
                        delta = d2 < delta ? d2 : delta;

                        if (delta < minDelta) {
                            result = cutMatch2;
                            shell.buff.add("d2");

                            cutSegment2Final = cutSegment2;
                            cutSegmentFinal = cutSegment1;

                            minDelta = delta;
                            overlapping = 2;
                        }
                    }
                    if (numUnique == 2) {

                        CutMatchList cutMatch3 = new CutMatchList(shell, sbe);
                        cutMatch3.addTwoCutTwoMatch(cutSegment2,
                                new Segment[] {
                                        knot.getSegment(external2, mirrorCP),
                                        knot.getSegment(otherNeighborPoint, mirror1) },
                                bothKnotPointsInside, c);

                        double d3 = cutMatch3.delta;
                        delta = d3 < delta ? d3 : delta;

                        CutMatchList cutMatch6 = new CutMatchList(shell, sbe);
                        cutMatch6.addTwoCutTwoMatch(cutSegment2,
                                new Segment[] {
                                        knot.getSegment(external2, mirror1),
                                        knot.getSegment(otherNeighborPoint, mirrorCP) },
                                bothKnotPointsInside, c);

                        double d6 = cutMatch6.delta;

                        if (delta < minDelta) {
                            if (delta == d3) {
                                result = cutMatch3;
                                shell.buff.add("d3");
                            } else if (delta == d6) {
                                result = cutMatch6;
                                shell.buff.add("d6");
                            }

                            cutSegment2Final = cutSegment2;
                            cutSegmentFinal = cutSegment1;

                            minDelta = delta;
                            overlapping = 2;
                        }

                    }
                    if (numUnique == 3) {
                        CutMatchList cutMatch2 = new CutMatchList(shell, sbe);
                        cutMatch2.addTwoCutTwoMatch(cutSegment2,
                                new Segment[] {
                                        knot.getSegment(external2, mirror1),
                                        knot.getSegment(external2, otherNeighborPoint),
                                        knot.getSegment(otherNeighborPoint2, mirrorCP) },
                                bothKnotPointsInside, c);

                        d2 = cutMatch2.delta;
                        delta = d2 < delta ? d2 : delta;

                        CutMatchList cutMatch3 = new CutMatchList(shell, sbe);
                        cutMatch3.addTwoCutTwoMatch(cutSegment2,
                                new Segment[] {
                                        knot.getSegment(external2, otherNeighborPoint2),
                                        knot.getSegment(external2, mirrorCP),
                                        knot.getSegment(otherNeighborPoint, mirror1) },
                                bothKnotPointsInside, c);

                        double d3 = cutMatch3.delta;
                        delta = d3 < delta ? d3 : delta;

                        CutMatchList cutMatch5 = new CutMatchList(shell, sbe);
                        cutMatch5.addTwoCutTwoMatch(cutSegment2,
                                new Segment[] {
                                        knot.getSegment(external2, otherNeighborPoint),
                                        knot.getSegment(external2, mirrorCP),
                                        knot.getSegment(otherNeighborPoint2, mirror1) },
                                bothKnotPointsInside, c);

                        double d5 = cutMatch5.delta;

                        CutMatchList cutMatch6 = new CutMatchList(shell, sbe);
                        cutMatch6.addTwoCutTwoMatch(cutSegment2,
                                new Segment[] {
                                        knot.getSegment(external2, otherNeighborPoint2),
                                        knot.getSegment(external2, mirror1),
                                        knot.getSegment(otherNeighborPoint, mirrorCP) },
                                bothKnotPointsInside, c);

                        double d6 = cutMatch6.delta;

                        if (delta < minDelta) {
                            if (delta == d2) {
                                result = cutMatch2;
                                shell.buff.add("d2");
                            } else if (delta == d3) {
                                result = cutMatch3;
                                shell.buff.add("d3");
                            } else if (delta == d5) {
                                result = cutMatch5;
                                shell.buff.add("d5");
                            } else if (delta == d6) {
                                result = cutMatch6;
                                shell.buff.add("d6");
                            }

                            cutSegment2Final = cutSegment2;
                            cutSegmentFinal = cutSegment1;

                            minDelta = delta;
                            overlapping = 2;
                        }
                    }
                } else {

                    CutMatchList cutMatch1 = new CutMatchList(shell, sbe);
                    cutMatch1.addTwoCutTwoMatch(cutSegment2,
                            new Segment[] {
                                    knot.getSegment(external2, mirror1),
                                    knot.getSegment(external2, mirror21),
                                    knot.getSegment(otherNeighborPoint, mirror22) },
                            bothKnotPointsInside, c);
                    d1 = cutMatch1.delta;
                    delta = d1 < delta ? d1 : delta;

                    CutMatchList cutMatch2 = new CutMatchList(shell, sbe);
                    cutMatch2.addTwoCutTwoMatch(cutSegment2,
                            new Segment[] {
                                    knot.getSegment(external2, mirror1),
                                    knot.getSegment(external2, mirror22),
                                    knot.getSegment(otherNeighborPoint, mirror21) },
                            bothKnotPointsInside, c);

                    d2 = cutMatch2.delta;
                    delta = d2 < delta ? d2 : delta;

                    if (delta < minDelta) {
                        if (d1 < d2) {
                            result = cutMatch1;
                        } else {
                            result = cutMatch2;
                        }
                        cutSegmentFinal = cutSegment1;
                        cutSegment2Final = cutSegment2;

                        minDelta = delta;
                        overlapping = 2;
                    }

                }

            }
        }
        if (overlapping == 1) {
            shell.buff.add("GOING WIHT SINGLE CUT");
            return result;

        } else if (overlapping == 2) {
            shell.buff.add("GOING WIHT Multi CUT");
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            CutMatchList cml = new CutMatchList(shell, sbe);
            cml.addDumbCutMatch(knot, superKnot);
            throw new SegmentBalanceException(sbe);
        }
    }

}
