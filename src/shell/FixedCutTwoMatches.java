package shell;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.commons.math3.util.Pair;

public class FixedCutTwoMatches extends FixedCut {

    public FixedCutTwoMatches(CutInfo c) {
        super(c);
    }

    @Override
    public CutMatchList findCutMatchListFixedCut() throws SegmentBalanceException, BalancerException {
        // TODO Auto-generated method stubSegmentBalanceException sbe = new
        // SegmentBalanceException(shell, null, superKnot, cutSegment1,

        if (upperMatchSegment == null) {
            new CutMatchList(shell, sbe, c.superKnot);
            throw new SegmentBalanceException(sbe);
        }
        if (!(knot.contains(cutSegment1.first) && knot.contains(cutSegment1.last))) {
            new CutMatchList(shell, sbe, c.superKnot);
            throw new SegmentBalanceException(sbe);
        }
        if ((knot.contains(external1) || knot.contains(external2))) {
            new CutMatchList(shell, sbe, c.superKnot);
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
        CutMatchList result = null;

        Segment implicitCut = null;
        if (numUnique == 1) {
            Segment zSegment = external2.getClosestSegment(c.upperKnotPoint, null);
            implicitCut = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(zSegment),
                    Segment.getLastOrderId(zSegment));

            if (implicitCut == null) {
                new CutMatchList(shell, sbe, superKnot);
                throw new SegmentBalanceException(sbe);
            }

            boolean kmck = Utils.marchUntilHasOneKnotPoint(c.upperKnotPoint, implicitCut, c.lowerCutSegment,
                    c.upperKnotPoint, c.lowerKnotPoint, knot);
            if (kmck == false) {
                implicitCut = knot.getOtherSegment(implicitCut, c.upperKnotPoint);
                if (implicitCut == null) {
                    knot.getOtherSegment(innerNeighborSegmentLookup.get(Segment.getFirstOrderId(zSegment),
                            Segment.getLastOrderId(zSegment)), c.upperCutPoint);
                    new CutMatchList(shell, sbe, superKnot);
                    throw new SegmentBalanceException(sbe);
                }
            }
        }
        // TODO: I think the first cut we check against needs to be the simple cut
        for (int a = 0; a < knot.knotPoints.size(); a++) {
            if (c.cutID == 156) {
                float z = 1;
            }
            VirtualPoint knotPoint21 = knot.knotPoints.get(a);
            VirtualPoint knotPoint22 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
            Segment cutSegment2 = knot.getSegment(knotPoint21, knotPoint22);
            shell.buff.add("cut 2: " + cutSegment2);
            if (c.cutID == 69) {
                float z = 1;
            }
            // I think that the continue state instead of being do the ct sgments partially
            // overlapp each other, should instead
            // be like
            // in the both knot points inside we only want to check cuts where each section
            // has one knotpoint cna we just check march contain for knot point one and knot
            // point two breaking at the next cut segment?
            if ((cutSegment1.partialOverlaps(cutSegment2)) && !cutSegment2.equals(kpSegment)) {
                shell.buff.add("OVERLAPP CONTINUING " + cutSegment2);
                // really what we need to be asking is: are there three out points?
                // continue;
            }
            boolean continueFlag = false;
            boolean unbalancedFlag = false;
            if (bothKnotPointsInside) {
                boolean leftHasOneOut = Utils.marchUntilHasOneKnotPoint(cutSegment1.first, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                boolean rightHasOneOut = Utils.marchUntilHasOneKnotPoint(cutSegment1.last, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                if (!(leftHasOneOut && rightHasOneOut) || !((cutSegment1.contains(kp1) || cutSegment2.contains(kp1))
                        && (cutSegment1.contains(upperKnotPoint) || cutSegment2.contains(upperKnotPoint)))) {
                    shell.buff.add("ONE SIDE WOULD BE UNBALANCED " + cutSegment2);
                    continueFlag = true;
                }

            }
            if (knot.size() == 3 && numUnique == 1
                    && (!implicitCut.equals(cutSegment2) && !implicitCut.equals(cutSegment1)
                            && !cutSegment2.equals(cutSegment1))) {
                continue;
            }
            double delta = Double.MAX_VALUE;

            VirtualPoint mirror1;
            VirtualPoint mirror21 = cutSegment1.getOther(kp1);

            VirtualPoint mirror22;
            Pair<VirtualPoint, VirtualPoint> mirrors = Utils.marchLookup(knot, kp1, cutSegment1.getOther(kp1),
                    cutSegment2);

            mirror1 = mirrors.getFirst();
            mirror22 = mirrors.getSecond();

            shell.buff.add(kp1);
            if (bothKnotPointsInside) {
                VirtualPoint mirrorCP = mirror21;
                VirtualPoint mirrorKP = mirror22;

                if (mirror21.equals(upperKnotPoint)) {
                    mirrorCP = mirror22;
                    mirrorKP = mirror21;
                }
                if (numUnique == 1) {

                    boolean canMatch = true;
                    if (c.cutID == 11 && cutSegment2.hasPoints(0, 1)) {
                        float z = 1;
                    }
                    Segment checkSegment = external2.getClosestSegment(knotPoint21, null);
                    if (innerNeighborSegmentLookup.containsKey(Segment.getFirstOrderId(checkSegment),
                            Segment.getLastOrderId(checkSegment))) {
                        canMatch = false;
                    }

                    Segment previousMatch = kp1.getClosestSegment(external1, null);
                    Segment previousCut = kp1.getClosestSegment(cp1, null);
                    Segment otherCut = implicitCut;
                    VirtualPoint otherKnotPoint = c.upperKnotPoint;
                    if (otherCut == null || otherCut.getOther(otherKnotPoint) == null
                            || c.upperKnotPoint == null) {
                        new CutMatchList(shell, sbe, superKnot);
                        throw new SegmentBalanceException(sbe);
                    }
                    if (c.cutID == 390) {
                        float z = 1;
                    }
                    Segment matchSegment1 = null;
                    CutMatchList internalCuts1 = null;
                    CutMatchList cutMatch1 = null;
                    double d1 = Double.MAX_VALUE;
                    Knot minKnot = cutEngine.internalPathEngine.findMinKnot(otherKnotPoint,
                            otherCut.getOther(otherKnotPoint), knotPoint21, knotPoint22, knot, sbe);
                    if (!minKnot.hasSegment(otherCut)) {
                        otherCut = previousCut;
                        otherKnotPoint = kp1;
                    }
                    if (canMatch) {

                        if (c.cutID == 176) {
                            float z = 1;
                        }
                        matchSegment1 = knotPoint21.getClosestSegment(external2, null);
                        internalCuts1 = new CutMatchList(shell, sbe, knot);
                        BalanceMap balanceMap = new BalanceMap(c.balanceMap, knot, sbe);

                        if (minKnot.contains(c.lowerKnotPoint) && minKnot.contains(knotPoint21)
                                && minKnot.contains(c.upperKnotPoint) && minKnot.hasSegment(implicitCut)
                                && minKnot.hasSegment(cutSegment1) && minKnot.hasSegment(cutSegment2)) {
                        } else {
                            try {
                                balanceMap.addCut(implicitCut.first, implicitCut.last);
                                if (!otherCut.equals(implicitCut)) {
                                    balanceMap.addCut(otherCut.first, otherCut.last);
                                }
                                balanceMap.addCut(cutSegment2.first, cutSegment2.last);
                                balanceMap.addExternalMatch(otherKnotPoint);
                                balanceMap.addExternalMatch(knotPoint21);
                            } catch (BalancerException be) {
                                throw be;
                            }
                            internalCuts1 = cutEngine.internalPathEngine.calculateInternalPathLength(
                                    otherKnotPoint, otherCut.getOther(otherKnotPoint), external1,
                                    knotPoint21, knotPoint22, external2,
                                    knot, balanceMap);
                        }

                        cutMatch1 = new CutMatchList(shell, sbe, c.superKnot);
                        if (matchSegment1.equals(c.upperCutSegment)) {
                            float z = 1;
                        }
                        try {
                            cutMatch1.addTwoCut(cutSegment1, new Segment[] { implicitCut, cutSegment2 }, previousMatch,
                                    matchSegment1, kp1,
                                    knotPoint21, internalCuts1, c, false,
                                    "FixedCutTwoMatchesKnotPointsInsideOneUnique1");
                        } catch (SegmentBalanceException e) {
                            throw e;
                        }
                        d1 = cutMatch1.delta;
                        delta = d1 < delta ? d1 : delta;
                    }

                    boolean canMatch2 = true;

                    Segment checkSegment2 = external2.getClosestSegment(knotPoint22, null);
                    if (innerNeighborSegmentLookup.containsKey(Segment.getFirstOrderId(checkSegment2),
                            Segment.getLastOrderId(checkSegment2))) {
                        canMatch2 = false;
                    }
                    Segment matchSegment2 = null;
                    CutMatchList internalCuts2 = null;
                    CutMatchList cutMatch2 = null;
                    double d2 = Double.MAX_VALUE;
                    Knot minKnot2 = cutEngine.internalPathEngine.findMinKnot(otherKnotPoint,
                            otherCut.getOther(otherKnotPoint), knotPoint21, knotPoint22, knot, sbe);
                    VirtualPoint otherKnotPoint2 = c.upperKnotPoint;
                    Segment otherCut2 = null;
                    Segment zSegment2 = external2.getClosestSegment(otherKnotPoint2, null);
                    otherCut2 = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(zSegment2),
                            Segment.getLastOrderId(zSegment2));
                    if (!minKnot2.hasSegment(otherCut2)) {
                        otherCut2 = previousCut;
                        otherKnotPoint2 = kp1;
                    }
                    if (canMatch2) {
                        matchSegment2 = knotPoint22.getClosestSegment(external2, null);
                        if (minKnot2.contains(c.lowerKnotPoint) && minKnot2.contains(knotPoint22)
                                && minKnot2.contains(c.upperKnotPoint) && minKnot2.hasSegment(implicitCut)
                                && minKnot2.hasSegment(cutSegment1) && minKnot2.hasSegment(cutSegment2)) {
                            internalCuts2 = new CutMatchList(shell, sbe, knot);
                        } else {

                            BalanceMap balanceMap2 = new BalanceMap(c.balanceMap, knot, sbe);
                            balanceMap2.addCut(implicitCut.first, implicitCut.last);
                            balanceMap2.addCut(otherCut2.first, otherCut2.last);
                            balanceMap2.addCut(cutSegment2.first, cutSegment2.last);
                            balanceMap2.addExternalMatch(otherKnotPoint2);
                            balanceMap2.addExternalMatch(knotPoint22);
                            internalCuts2 = cutEngine.internalPathEngine.calculateInternalPathLength(
                                    otherKnotPoint2, otherCut2.getOther(otherKnotPoint2), external1,
                                    knotPoint22, knotPoint21, external2,
                                    knot, balanceMap2);
                        }

                        cutMatch2 = new CutMatchList(shell, sbe, c.superKnot);
                        cutMatch2.addTwoCut(cutSegment1, new Segment[] { implicitCut, cutSegment2 }, previousMatch,
                                matchSegment2, kp1,
                                knotPoint21, internalCuts2, c, false,
                                "FixedCutTwoMatchesKnotPointsInsideOneUnique2");

                        d2 = cutMatch2.delta;
                        delta = d2 < delta ? d2 : delta;
                    }
                    if (delta < minDelta) {
                        if (delta == d1) {
                            result = cutMatch1;
                        } else if (delta == d2) {
                            result = cutMatch2;

                        }
                        minDelta = delta;
                        overlapping = 2;
                    }
                }
                if (numUnique == 2) {

                    boolean canMatch3 = !c.upperCutSegment.contains(mirrorCP);
                    CutMatchList cutMatch3 = null;
                    double d3 = Double.MAX_VALUE;
                    if (canMatch3) {
                        cutMatch3 = new CutMatchList(shell, sbe, c.superKnot);

                        BalanceMap balanceMap3 = new BalanceMap(c.balanceMap, knot, sbe);
                        try {
                            balanceMap3.addCut(cutSegment2.first, cutSegment2.last);
                            balanceMap3.addExternalMatch(mirrorCP);
                            balanceMap3.addExternalMatch(mirror1);
                        } catch (BalancerException be) {
                            throw be;
                        }
                        cutMatch3.addCutMatch(new Segment[] { cutSegment2 },
                                new Segment[] {
                                        knot.getSegment(external2, mirrorCP),
                                        knot.getSegment(otherNeighborPoint, mirror1) },
                                c, "FixedCutTwoMatchesKnotPointsInsideTwoUnique3");

                        d3 = cutMatch3.delta;
                        delta = d3 < delta ? d3 : delta;
                    }

                    boolean canMatch6 = !c.upperCutSegment.contains(mirror1);
                    CutMatchList cutMatch6 = null;
                    double d6 = Double.MAX_VALUE;
                    if (canMatch6) {
                        cutMatch6 = new CutMatchList(shell, sbe, c.superKnot);
                        BalanceMap balanceMap6 = new BalanceMap(c.balanceMap, knot, sbe);
                        try {
                            balanceMap6.addCut(cutSegment2.first, cutSegment2.last);
                            balanceMap6.addExternalMatch(mirrorCP);
                            balanceMap6.addExternalMatch(mirror1);
                        } catch (BalancerException be) {
                            throw be;
                        }
                        cutMatch6.addCutMatch(new Segment[] { cutSegment2 },
                                new Segment[] {
                                        knot.getSegment(external2, mirror1),
                                        knot.getSegment(otherNeighborPoint, mirrorCP) },
                                c, "FixedCutTwoMatchesKnotPointsInsideTwoUnique6");

                        d6 = cutMatch6.delta;
                    }

                    if (continueFlag) {
                        continue;
                    }
                    if (delta < minDelta) {
                        if (delta == d3 && canMatch3) {
                            result = cutMatch3;
                        } else if (delta == d6 && canMatch6) {
                            result = cutMatch6;
                        }

                        minDelta = delta;
                        overlapping = 2;
                    }

                }
                if (numUnique == 3) {
                    CutMatchList cutMatch2 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch2.addCutMatch(new Segment[] { cutSegment2 },
                            new Segment[] {
                                    knot.getSegment(external2, mirror1),
                                    knot.getSegment(external2, otherNeighborPoint),
                                    knot.getSegment(otherNeighborPoint2, mirrorCP) },
                            c, "FixedCutTwoMatchesKnotPointsInsideThreeUnique");

                    double d2 = cutMatch2.delta;
                    delta = d2 < delta ? d2 : delta;

                    CutMatchList cutMatch3 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch3.addCutMatch(new Segment[] { cutSegment2 },
                            new Segment[] {
                                    knot.getSegment(external2, otherNeighborPoint2),
                                    knot.getSegment(external2, mirrorCP),
                                    knot.getSegment(otherNeighborPoint, mirror1) },
                            c, "FixedCutTwoMatchesKnotPointsInsideThreeUnique");

                    double d3 = cutMatch3.delta;
                    delta = d3 < delta ? d3 : delta;

                    CutMatchList cutMatch5 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch5.addCutMatch(new Segment[] { cutSegment2 },
                            new Segment[] {
                                    knot.getSegment(external2, otherNeighborPoint),
                                    knot.getSegment(external2, mirrorCP),
                                    knot.getSegment(otherNeighborPoint2, mirror1) },
                            c, "FixedCutTwoMatchesKnotPointsInsideThreeUnique");

                    double d5 = cutMatch5.delta;

                    CutMatchList cutMatch6 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch6.addCutMatch(new Segment[] { cutSegment2 },
                            new Segment[] {
                                    knot.getSegment(external2, otherNeighborPoint2),
                                    knot.getSegment(external2, mirror1),
                                    knot.getSegment(otherNeighborPoint, mirrorCP) },
                            c, "FixedCutTwoMatchesKnotPointsInsideThreeUnique");

                    double d6 = cutMatch6.delta;

                    if (continueFlag) {
                        continue;
                    }
                    if (delta < minDelta) {
                        if (delta == d2) {
                            result = cutMatch2;
                        } else if (delta == d3) {
                            result = cutMatch3;
                        } else if (delta == d5) {
                            result = cutMatch5;
                        } else if (delta == d6) {
                            result = cutMatch6;
                        }

                        minDelta = delta;
                        overlapping = 2;
                    }
                }
            } else {
                if (numUnique == 2) {

                    if (c.cutID == 401) {
                        float z = 1;
                    }
                    CutMatchList cutMatch1 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch1.addCutMatch(new Segment[] { cutSegment2 },
                            new Segment[] {
                                    knot.getSegment(external2, mirror1),
                                    knot.getSegment(external2, mirror21),
                                    knot.getSegment(otherNeighborPoint, mirror22) },
                            c, "FixedCutTwoMatchesKnotPointOutsideTwoUnique");
                    double d1 = cutMatch1.delta;
                    delta = d1 < delta ? d1 : delta;

                    CutMatchList cutMatch2 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch2.addCutMatch(new Segment[] { cutSegment2 },
                            new Segment[] {
                                    knot.getSegment(external2, mirror1),
                                    knot.getSegment(external2, mirror22),
                                    knot.getSegment(otherNeighborPoint, mirror21) },
                            c, "FixedCutTwoMatchesKnotPointOutsideTwoUnique");

                    double d2 = cutMatch2.delta;
                    delta = d2 < delta ? d2 : delta;

                    if (delta < minDelta) {
                        if (d1 < d2) {
                            result = cutMatch1;
                        } else {
                            result = cutMatch2;
                        }

                        minDelta = delta;
                        overlapping = 2;
                    }
                } else if (numUnique == 3) {

                    CutMatchList cutMatch1 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch1.addCutMatch(new Segment[] { cutSegment2 },
                            new Segment[] {
                                    knot.getSegment(external2, mirror1),
                                    knot.getSegment(otherNeighborPoint2, mirror21),
                                    knot.getSegment(otherNeighborPoint, mirror22) },
                            c, "FixedCutTwoMatchesKnotPointOutsideThreeUnique");
                    double d1 = cutMatch1.delta;
                    delta = d1 < delta ? d1 : delta;

                    CutMatchList cutMatch2 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch2.addCutMatch(new Segment[] { cutSegment2 },
                            new Segment[] {
                                    knot.getSegment(external2, mirror1),
                                    knot.getSegment(otherNeighborPoint2, mirror22),
                                    knot.getSegment(otherNeighborPoint, mirror21) },
                            c, "FixedCutTwoMatchesKnotPointOutsideThreeUnique");

                    double d2 = cutMatch2.delta;
                    delta = d2 < delta ? d2 : delta;

                    CutMatchList cutMatch3 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch3.addCutMatch(new Segment[] { cutSegment2 },
                            new Segment[] {
                                    knot.getSegment(external2, mirror21),
                                    knot.getSegment(otherNeighborPoint2, mirror22),
                                    knot.getSegment(otherNeighborPoint, mirror1) },
                            c, "FixedCutTwoMatchesKnotPointOutsideThreeUnique");

                    double d3 = cutMatch3.delta;
                    delta = d3 < delta ? d3 : delta;

                    CutMatchList cutMatch4 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch4.addCutMatch(new Segment[] { cutSegment2 },
                            new Segment[] {
                                    knot.getSegment(external2, mirror21),
                                    knot.getSegment(otherNeighborPoint2, mirror1),
                                    knot.getSegment(otherNeighborPoint, mirror22) },
                            c, "FixedCutTwoMatchesKnotPointOutsideThreeUnique");

                    double d4 = cutMatch4.delta;
                    delta = d4 < delta ? d4 : delta;

                    CutMatchList cutMatch5 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch5.addCutMatch(new Segment[] { cutSegment2 },
                            new Segment[] {
                                    knot.getSegment(external2, mirror22),
                                    knot.getSegment(otherNeighborPoint2, mirror21),
                                    knot.getSegment(otherNeighborPoint, mirror1) },
                            c, "FixedCutTwoMatchesKnotPointOutsideThreeUnique");

                    double d5 = cutMatch5.delta;
                    delta = d5 < delta ? d5 : delta;

                    CutMatchList cutMatch6 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch6.addCutMatch(new Segment[] { cutSegment2 },
                            new Segment[] {
                                    knot.getSegment(external2, mirror22),
                                    knot.getSegment(otherNeighborPoint2, mirror1),
                                    knot.getSegment(otherNeighborPoint, mirror21) },
                            c, "FixedCutTwoMatchesKnotPointOutsideThreeUnique");

                    double d6 = cutMatch5.delta;
                    delta = d6 < delta ? d6 : delta;

                    if (delta < minDelta) {
                        if (delta == d1) {
                            result = cutMatch1;
                        } else if (delta == d2) {
                            result = cutMatch2;
                        } else if (delta == d3) {
                            result = cutMatch3;
                        } else if (delta == d4) {
                            result = cutMatch4;
                        } else if (delta == d5) {
                            result = cutMatch5;
                        } else if (delta == d6) {
                            result = cutMatch6;
                        }

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
            CutMatchList cml = new CutMatchList(shell, sbe, c.superKnot);
            cml.addDumbCutMatch(knot, superKnot, "FixedCutTwoMatchesNoAvailableCuts");
            throw new SegmentBalanceException(sbe);
        }
    }

    public Triple<Set<Segment>, Set<Segment>, Pair<VirtualPoint, VirtualPoint>> findSimpleMatch(
            Segment cutSegment1Segment) {
        Segment matchSegment1 = null;
        Segment matchSegment2 = null;
        Segment matchSegment3 = null;
        Segment cutSegment2Final = null;
        Segment innerSegment1 = null;
        Segment innerSegment2 = null;
        Segment innerSegment3 = null;
        Set<Segment> previousMatches = new HashSet<>();
        Set<Segment> internalCuts = new HashSet<>();
        if (neighborCutSegments.size() > 0) {
            matchSegment1 = neighborCutSegments.get(0).getFirst();
            previousMatches.add(matchSegment1);
            innerSegment1 = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(matchSegment1),
                    Segment.getLastOrderId(matchSegment1));
            internalCuts.add(innerSegment1);
        }
        if (neighborCutSegments.size() > 1) {
            matchSegment2 = neighborCutSegments.get(1).getFirst();
            previousMatches.add(matchSegment2);
            innerSegment2 = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(matchSegment2),
                    Segment.getLastOrderId(matchSegment2));
            internalCuts.add(innerSegment2);
        }
        if (neighborCutSegments.size() > 2) {
            matchSegment3 = neighborCutSegments.get(2).getFirst();
            previousMatches.add(matchSegment3);
            innerSegment3 = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(matchSegment3),
                    Segment.getLastOrderId(matchSegment3));
            internalCuts.add(innerSegment3);
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

        return new ImmutableTriple<>(previousMatches, internalCuts, mirrors);
    }

}
