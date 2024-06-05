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
            CutMatchList cml = new CutMatchList(shell, sbe);
            throw new SegmentBalanceException(sbe);
        }
        if (!(knot.contains(cutSegment1.first) && knot.contains(cutSegment1.last))) {
            shell.buff.add(knot);
            shell.buff.add(cutSegment1);
            float z = 1 / 0;
        }
        if ((knot.contains(external1) || knot.contains(external2))) {
            float z = 1 / 0;
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
        shell.buff.add(
                "otherNeghborPoint2: " + otherNeighborPoint2 + " onp: " + otherNeighborPoint + " ex2: " + external2);

        double minDelta = Double.MAX_VALUE;
        int overlapping = -1;
        Segment matchSegmentToCutPoint1 = null;
        Segment matchSegmentToCutPoint2 = null;
        Segment matchSegmentOuterKnotPointFinal = null;
        Segment matchSegmentAcrossFinal = null;
        Segment cutSegmentFinal = null;
        Segment cutSegment2Final = null;
        VirtualPoint knotPoint1Final = null;

        if (!bothKnotPointsInside) {
            overlapping = 1;
            matchSegmentToCutPoint1 = knot.getSegment(cp1, external2);
            knotPoint1Final = cp1;
            cutSegmentFinal = cutSegment1;
            minDelta = matchSegmentToCutPoint1.distance;
        }
        if (bothKnotPointsInside && !neighborSegments.contains(knot.getSegment(cp1, external2))) {
            overlapping = 2;
            double delta = 0.0;
            Segment innerSegment1 = null;
            Segment innerSegment2 = null;
            Segment innerSegment3 = null;
            if (neighborCutSegments.size() > 0) {
                matchSegmentToCutPoint1 = neighborCutSegments.get(0).getFirst();
                knotPoint1Final = matchSegmentToCutPoint1.getOther(neighborCutSegments.get(0).getSecond());
                innerSegment1 = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(matchSegmentToCutPoint1),
                        Segment.getLastOrderId(matchSegmentToCutPoint1));
                delta += matchSegmentToCutPoint1.distance;
            }
            if (neighborCutSegments.size() > 1) {
                matchSegmentToCutPoint2 = neighborCutSegments.get(1).getFirst();
                innerSegment2 = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(matchSegmentToCutPoint1),
                        Segment.getLastOrderId(matchSegmentToCutPoint1));
                delta += matchSegmentToCutPoint2.distance;
            }
            if (neighborCutSegments.size() > 2) {
                matchSegmentOuterKnotPointFinal = neighborCutSegments.get(2).getFirst();
                innerSegment3 = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(matchSegmentToCutPoint1),
                        Segment.getLastOrderId(matchSegmentToCutPoint1));
                delta += matchSegmentOuterKnotPointFinal.distance;
            }
            matchSegmentAcrossFinal = cp1.getClosestSegment(external2, null);
            delta += matchSegmentAcrossFinal.distance;
            if (innerSegment1 != null && innerSegment1.equals(innerSegment2)
                    || innerSegment1 != null && innerSegment1.equals(innerSegment3)) {
                cutSegment2Final = innerSegment1;
            } else if (innerSegment2 != null && innerSegment2.equals(innerSegment3)) {
                cutSegment2Final = innerSegment2;
            } else {
                cutSegment2Final = innerSegment1;
            }
            cutSegmentFinal = cutSegment1;
            minDelta = delta - cutSegment2Final.distance;
            shell.buff.add("SIMPLE CUT MATCH: " + matchSegmentAcrossFinal + " matches: "
                    + matchSegmentOuterKnotPointFinal + " " + matchSegmentToCutPoint1 + " " + matchSegmentToCutPoint2
                    + "CUTS: " + cutSegmentFinal + " " + cutSegment2Final + " minDelta " + minDelta + "");
            CutMatchList result = new CutMatchList(shell, sbe);
            result.addTwoCutTwoMatch(cutSegmentFinal, cutSegment2Final,
                    kp1.getClosestSegment(external1, null), kp1,
                    upperMatchSegment, upperKnotPoint, new Segment[] {
                            matchSegmentToCutPoint1,
                            matchSegmentToCutPoint2,
                            matchSegmentOuterKnotPointFinal,
                            matchSegmentAcrossFinal },
                    bothKnotPointsInside, c);
            shell.buff.add("REEEE:  " + result.toString());
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
                boolean leftHasOneOut = CutEngine.marchUntilHasOneKnotPoint(cutSegment1.first, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                boolean rightHasOneOut = CutEngine.marchUntilHasOneKnotPoint(cutSegment1.last, cutSegment1,
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
                VirtualPoint cp2 = knotPoint22;
                VirtualPoint kp2 = knotPoint21;

                VirtualPoint mirror1;
                VirtualPoint mirror21 = cutSegment1.getOther(kp1);

                VirtualPoint mirror22;
                int idx = knot.knotPoints.indexOf(kp1);
                int idx2 = knot.knotPoints.indexOf(cutSegment1.getOther(kp1));

                int marchDirection = idx2 - idx < 0 ? -1 : 1;
                if (idx == 0 && idx2 == knot.knotPoints.size() - 1) {
                    marchDirection = -1;
                }
                if (idx2 == 0 && idx == knot.knotPoints.size() - 1) {
                    marchDirection = 1;
                }
                int next = idx + marchDirection;
                if (marchDirection < 0 && next < 0) {
                    next = knot.knotPoints.size() - 1;
                } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                    next = 0;
                }
                marchDirection = -marchDirection;
                VirtualPoint curr = knot.knotPoints.get(idx);
                shell.buff.add(curr);
                shell.buff.add(cutSegment1);
                while (true) {
                    curr = knot.knotPoints.get(idx);
                    next = idx + marchDirection;
                    if (marchDirection < 0 && next < 0) {
                        next = knot.knotPoints.size() - 1;
                    } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                        next = 0;
                    }
                    VirtualPoint nextp = knot.knotPoints.get(next);

                    shell.buff.add(curr + " " + nextp);
                    if (cutSegment2.contains(nextp) && cutSegment2.contains(curr)) {
                        mirror1 = curr;
                        mirror22 = nextp;
                        break;
                    }
                    idx = next;
                }
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

                    Segment s12 = knot.getSegment(external2, mirrorCP);
                    Segment s13 = knot.getSegment(otherNeighborPoint, external2);
                    Segment s14 = knot.getSegment(otherNeighborPoint2, mirror1);
                    Segment cut1 = knot.getSegment(mirrorCP, mirrorKP);
                    d1 = s12.distance + s13.distance + s14.distance - cutSegment2.distance;
                    delta = d1 < delta ? d1 : delta;

                    Segment s21 = knot.getSegment(external2, mirror1);
                    Segment s22 = knot.getSegment(external2, otherNeighborPoint);
                    Segment s23 = knot.getSegment(otherNeighborPoint2, mirrorCP);
                    Segment cut2 = knot.getSegment(mirror1, mirrorKP);
                    d2 = s21.distance + s22.distance + s23.distance - cutSegment2.distance;
                    delta = d2 < delta ? d2 : delta;

                    Segment s31 = knot.getSegment(external2, otherNeighborPoint2);
                    Segment s32 = knot.getSegment(external2, mirrorCP);
                    Segment s33 = knot.getSegment(otherNeighborPoint, mirror1);
                    Segment cut3 = knot.getSegment(mirror1, mirrorKP);
                    double d3 = s31.distance + s32.distance + s33.distance - cutSegment2.distance;
                    delta = d3 < delta ? d3 : delta;

                    Segment s42 = knot.getSegment(external2, mirrorCP);
                    Segment s43 = knot.getSegment(otherNeighborPoint, mirror1);
                    Segment s44 = knot.getSegment(otherNeighborPoint2, external2);
                    Segment cut4 = knot.getSegment(mirrorCP, mirrorKP);
                    double d4 = s42.distance + s43.distance + s44.distance - cutSegment2.distance;
                    delta = d4 < delta ? d4 : delta;

                    Segment s51 = knot.getSegment(external2, otherNeighborPoint);
                    Segment s52 = knot.getSegment(external2, mirrorCP);
                    Segment s53 = knot.getSegment(otherNeighborPoint2, mirror1);
                    Segment cut5 = knot.getSegment(mirror1, mirrorKP);
                    double d5 = s51.distance + s52.distance + s53.distance - cutSegment2.distance;
                    delta = d5 < delta ? d5 : delta;

                    Segment s61 = knot.getSegment(external2, otherNeighborPoint2);
                    Segment s62 = knot.getSegment(external2, mirror1);
                    Segment s63 = knot.getSegment(otherNeighborPoint, mirrorCP);
                    Segment cut6 = knot.getSegment(mirror1, mirrorKP);
                    double d6 = s61.distance + s62.distance + s63.distance - cutSegment2.distance;
                    delta = d6 < delta ? d6 : delta;

                    if (delta < minDelta) {
                        if (delta == d1) {
                            matchSegmentToCutPoint1 = s12;
                            matchSegmentToCutPoint2 = s13;
                            matchSegmentOuterKnotPointFinal = s14;
                            cutSegment2Final = cut1;
                            shell.buff.add("d1");

                        } else if (delta == d2) {
                            matchSegmentToCutPoint1 = s21;
                            matchSegmentToCutPoint2 = s22;
                            matchSegmentOuterKnotPointFinal = s23;
                            cutSegment2Final = cut2;
                            shell.buff.add("d2");
                        } else if (delta == d3) {
                            matchSegmentToCutPoint1 = s31;
                            matchSegmentToCutPoint2 = s32;
                            matchSegmentOuterKnotPointFinal = s33;
                            cutSegment2Final = cut3;
                            shell.buff.add("d3");
                        } else if (delta == d4) {
                            matchSegmentToCutPoint1 = s42;
                            matchSegmentToCutPoint2 = s43;
                            matchSegmentOuterKnotPointFinal = s44;
                            cutSegment2Final = cut4;
                            shell.buff.add("d4");
                        } else if (delta == d5) {
                            matchSegmentToCutPoint1 = s51;
                            matchSegmentToCutPoint2 = s52;
                            matchSegmentOuterKnotPointFinal = s53;
                            cutSegment2Final = cut5;
                            shell.buff.add("d5");
                        } else if (delta == d6) {
                            matchSegmentToCutPoint1 = s61;
                            matchSegmentToCutPoint2 = s62;
                            matchSegmentOuterKnotPointFinal = s63;
                            cutSegment2Final = cut6;
                            shell.buff.add("d6");
                        }
                        shell.buff.add("Group1 cut1: " + cutSegmentFinal + " cut2: " + cut1 + " matches: " +
                                s12 + " " + s13 + " " + s14);
                        shell.buff.add("Group2 cut1: " + cutSegmentFinal + " cut2: " + cut2 + " matches: " +
                                s21 + " " + s22 + " " + s23);
                        shell.buff.add("Group3 cut1: " + cutSegmentFinal + " cut2: " + cut3 + " matches: " +
                                s31 + " " + s32 + " " + s33);
                        shell.buff.add("Group4 cut1: " + cutSegmentFinal + " cut2: " + cut4 + " matches: " +
                                s42 + " " + s43 + " " + s44);
                        shell.buff.add("Group5 cut1: " + cutSegmentFinal + " cut2: " + cut5 + " matches: " +
                                s51 + " " + s52 + " " + s53);
                        shell.buff.add("Group6 cut1: " + cutSegmentFinal + " cut2: " + cut6 + " matches: " +
                                s61 + " " + s62 + " " + s63);
                        cutSegment2Final = cutSegment2;
                        cutSegmentFinal = cutSegment1;

                        minDelta = delta;
                        overlapping = 2;

                    }
                } else {
                    Segment s11 = knot.getSegment(external2, mirror1);
                    Segment s12 = knot.getSegment(external2, mirror21);
                    Segment s13 = knot.getSegment(otherNeighborPoint, mirror22);
                    d1 = s11.distance + s12.distance + s13.distance - cutSegment2.distance;
                    delta = d1 < delta ? d1 : delta;

                    Segment s21 = knot.getSegment(external2, mirror1);
                    Segment s22 = knot.getSegment(external2, mirror22);
                    Segment s23 = knot.getSegment(otherNeighborPoint, mirror21);
                    d2 = s21.distance + s22.distance + s23.distance - cutSegment2.distance;
                    delta = d2 < delta ? d2 : delta;
                    if (delta < minDelta) {
                        if (d1 < d2) {
                            matchSegmentToCutPoint1 = s11;
                            matchSegmentToCutPoint2 = s12;
                            matchSegmentOuterKnotPointFinal = s13;
                            cutSegmentFinal = cutSegment1;
                            cutSegment2Final = cutSegment2;
                        } else {
                            matchSegmentToCutPoint1 = s21;
                            matchSegmentToCutPoint2 = s22;
                            matchSegmentOuterKnotPointFinal = s23;
                            cutSegmentFinal = cutSegment1;
                            cutSegment2Final = cutSegment2;

                        }

                        minDelta = delta;
                        overlapping = 2;
                    }

                }

            }
        }
        if (overlapping == 1) {
            CutMatchList result = new CutMatchList(shell, sbe);
            // TODO: need to make this instead of simple match simple match with diffKnot
            result.addCut(cutSegmentFinal, kp1.getClosestSegment(external1, null), matchSegmentToCutPoint1,
                    kp1, knotPoint1Final, c, false, true);
            return result;

        } else if (overlapping == 2) {
            shell.buff.add("cut1: " + cutSegmentFinal + " cut2: " + cutSegment2Final + " matches: " +
                    matchSegmentToCutPoint1 + " " + matchSegmentToCutPoint2 + " " + matchSegmentOuterKnotPointFinal);
            CutMatchList result = new CutMatchList(shell, sbe);
            result.addTwoCutTwoMatch(cutSegmentFinal, cutSegment2Final,
                    kp1.getClosestSegment(external1, null), kp1,
                    upperMatchSegment, upperKnotPoint, new Segment[] { matchSegmentToCutPoint1,
                            matchSegmentToCutPoint2,
                            matchSegmentOuterKnotPointFinal,
                            matchSegmentAcrossFinal },
                    bothKnotPointsInside, c);
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            CutMatchList cml = new CutMatchList(shell, sbe);
            cml.addDumbCutMatch(knot, superKnot);
            throw new SegmentBalanceException(sbe);
        }
    }

}
