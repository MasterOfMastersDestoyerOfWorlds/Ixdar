package shell;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.commons.math3.util.Pair;

public class CutEngine {

    // TODO: Need to overhaul cut knots here is the idea:
    // we get the two external points and loop through a double nested for loop
    // across the knot's segments to cut
    // store the info for each cut segment in a list or just store the min length
    // change, with some minimum set of variables and whether we need to
    // join across or not
    // if the inner segment ""xor'ed"" with the outer segment is partially
    // overlapping,
    // then we do not evaluate it, if it is fully overlapping or not overlapping
    // then evaluate
    // should be roughly N^3 operation N^2 to cut a Knot Times M knots M ~= N/3
    // worst case M = N-3

    public HashMap<Integer, Knot> flatKnots = new HashMap<>();
    int cutKnotNum = 0;

    Shell shell;
    InternalPathEngine internalPathEngine;

    public CutEngine(Shell shell) {
        this.shell = shell;
        this.internalPathEngine = new InternalPathEngine(shell, this);
    }

    public CutMatchList findCutMatchList(Knot knot, VirtualPoint external1, VirtualPoint external2, Knot superKnot,
            Segment kpSegment) throws SegmentBalanceException {

        double minDelta = Double.MAX_VALUE;
        boolean overlapping = true;
        Segment matchSegment1Final = null, matchSegment2Final = null, cutSegmentFinal = null, cutSegment2Final = null;
        VirtualPoint knotPoint1Final = null, knotPoint2Final = null;
        CutMatchList internalCuts = null;
        String segmentName = "";
        for (int a = 0; a < knot.knotPoints.size(); a++) {
            for (int b = 0; b < knot.knotPoints.size(); b++) {
                VirtualPoint knotPoint11 = knot.knotPoints.get(a);
                VirtualPoint knotPoint12 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
                Segment cutSegment1 = knot.getSegment(knotPoint11, knotPoint12);

                VirtualPoint knotPoint21 = knot.knotPoints.get(b);
                VirtualPoint knotPoint22 = knot.knotPoints.get(b + 1 >= knot.knotPoints.size() ? 0 : b + 1);
                Segment cutSegment2 = knot.getSegment(knotPoint21, knotPoint22);
                if (cutSegment1.partialOverlaps(cutSegment2)) {
                    continue;
                }
                if (cutSegment1.equals(cutSegment2)) {

                    Segment s11 = knotPoint11.getClosestSegment(external1, null);
                    Segment s12 = knotPoint12.getClosestSegment(external2, s11);
                    double d1 = s11.distance + s12.distance - cutSegment1.distance;

                    Segment s21 = knotPoint12.getClosestSegment(external1, null);
                    Segment s22 = knotPoint11.getClosestSegment(external2, s21);
                    double d2 = s21.distance + s22.distance - cutSegment1.distance;

                    double delta = d2;
                    if (d1 < d2) {
                        delta = d1;
                    }
                    if (delta < minDelta) {
                        if (d1 < d2) {
                            matchSegment1Final = s11;
                            matchSegment2Final = s12;
                            knotPoint1Final = knotPoint11;
                            knotPoint2Final = knotPoint12;
                        } else {
                            matchSegment1Final = s21;
                            matchSegment2Final = s22;
                            knotPoint1Final = knotPoint12;
                            knotPoint2Final = knotPoint11;
                        }
                        minDelta = delta;
                        overlapping = true;
                        cutSegmentFinal = cutSegment1;
                    }
                } else {
                    double delta = Double.MAX_VALUE;

                    Segment s11 = knotPoint11.getClosestSegment(external1, null);
                    Segment s12 = knotPoint21.getClosestSegment(external2, s11);
                    shell.buff.add("12 -------------------------------------------");
                    CutMatchList internalCuts12 = internalPathEngine.calculateInternalPathLength(
                            knotPoint11, knotPoint12, external1,
                            knotPoint21, knotPoint22, external2, knot);
                    double d1 = s11.distance + s12.distance + internalCuts12.delta - cutSegment1.distance
                            - cutSegment2.distance;
                    delta = d1 < delta ? d1 : delta;
                    if (!internalCuts12.checkCutMatchBalance(s11, s12, cutSegment1, cutSegment2, external1,
                            external2, knot, new ArrayList<>(), knot, false)) {
                        shell.buff.add(knot);
                        shell.buff.add("Cut Info 12: Cut1: knotPoint1: " + knotPoint11 + " cutpointA: " + knotPoint12
                                + " ex1:" + external1 + " knotPoint2: " + knotPoint21 + " cutPointB: " + knotPoint22
                                + " ex2: " + external2);
                        shell.buff.add("%complete this knot: " + 100.0 * (((double) a) * ((double) a - 1) + b)
                                / (((double) knot.knotPoints.size()) * ((double) knot.knotPoints.size())));
                        shell.buff.add(shell.knotName + "_cut" + knotPoint11 + "-" + knotPoint12 + "and" + knotPoint21
                                + "-" + knotPoint22);
                        throw new SegmentBalanceException(shell, internalCuts12, knot,
                                knot.getSegment(knotPoint12, knotPoint11), s11,
                                knot.getSegment(knotPoint21, knotPoint22), s12);
                    } else {
                        shell.buff.flush();
                    }

                    Segment s21 = knotPoint21.getClosestSegment(external1, null);
                    Segment s22 = knotPoint11.getClosestSegment(external2, s21);
                    double d2 = s21.distance + s22.distance + internalCuts12.delta - cutSegment1.distance
                            - cutSegment2.distance;
                    delta = d2 < delta ? d2 : delta;

                    shell.buff.add("34 -------------------------------------------");
                    Segment s31 = knotPoint12.getClosestSegment(external1, null);
                    Segment s32 = knotPoint22.getClosestSegment(external2, s31);
                    CutMatchList internalCuts34 = internalPathEngine.calculateInternalPathLength(
                            knotPoint12, knotPoint11, external1,
                            knotPoint22, knotPoint21, external2, knot);
                    double d3 = s31.distance + s32.distance + internalCuts34.delta - cutSegment1.distance
                            - cutSegment2.distance;
                    delta = d3 < delta ? d3 : delta;
                    if (!internalCuts34.checkCutMatchBalance(s31, s32, cutSegment1, cutSegment2, external1,
                            external2, knot, new ArrayList<>(), knot, false)) {
                        shell.buff.add(knot);
                        shell.buff.add("Cut Info 34: Cut1: knotPoint1: " + knotPoint12 + " cutpointA: " + knotPoint11
                                + " ex1:" + external1 + " knotPoint2: " + knotPoint22 + " cutPointB: " + knotPoint21
                                + " ex2: " + external2);
                        shell.buff.add("%complete this knot: " + 100.0 * (((double) a) * ((double) a - 1) + b)
                                / (((double) knot.knotPoints.size()) * ((double) knot.knotPoints.size())));
                        shell.buff.add(shell.knotName + "_cut" + knotPoint12 + "-" + knotPoint11 + "and" + knotPoint22
                                + "-" + knotPoint21);
                        throw new SegmentBalanceException(shell, internalCuts34, knot,
                                knot.getSegment(knotPoint12, knotPoint11), s31,
                                knot.getSegment(knotPoint21, knotPoint22), s32);
                    } else {
                        shell.buff.flush();
                    }

                    shell.buff.flush();
                    shell.buff.add(" 56 -------------------------------------------");

                    Segment s41 = knotPoint22.getClosestSegment(external1, null);
                    Segment s42 = knotPoint12.getClosestSegment(external2, s41);

                    double d4 = s41.distance + s42.distance + internalCuts34.delta - cutSegment1.distance
                            - cutSegment2.distance;
                    delta = d4 < delta ? d4 : delta;

                    Knot minKnot = internalPathEngine.findMinKnot(knotPoint11, knotPoint12, knotPoint22, knotPoint21,
                            knot);
                    double d5 = Double.MAX_VALUE, d6 = Double.MAX_VALUE;
                    CutMatchList internalCuts56 = null;
                    Segment s51 = null, s52 = null, s61 = null, s62 = null;
                    Knot smallestCommonKnotPointKnot = flatKnots
                            .get(shell.smallestCommonKnotLookup[knotPoint11.id][knotPoint22.id]);
                    if (!minKnot.equals(knot) && !smallestCommonKnotPointKnot.equals(knot)) {

                        internalCuts56 = internalPathEngine.calculateInternalPathLength(
                                knotPoint11, knotPoint12, external1,
                                knotPoint22, knotPoint21, external2, knot);
                        s51 = knotPoint11.getClosestSegment(external1, null);
                        s52 = knotPoint22.getClosestSegment(external2, s51);
                        d5 = s51.distance + s52.distance + internalCuts56.delta - cutSegment1.distance
                                - cutSegment2.distance;

                        s61 = knotPoint12.getClosestSegment(external1, null);
                        s62 = knotPoint21.getClosestSegment(external2, s61);
                        d6 = s61.distance + s62.distance + internalCuts56.delta - cutSegment1.distance
                                - cutSegment2.distance;

                        if (!internalCuts56.checkCutMatchBalance(s51, s52, cutSegment1, cutSegment2, external1,
                                external2, knot, new ArrayList<>(), knot, false)) {
                            shell.buff.add(knot);
                            shell.buff.add("Cut Info 56: Cut1: knotPoint1: " + knotPoint11 + " cutpointA: "
                                    + knotPoint12
                                    + " ex1:" + external1 + " knotPoint2: " + knotPoint22 + " cutPointB: " + knotPoint21
                                    + " ex2: " + external2);
                            shell.buff.add("%complete this knot: " + 100.0 * (((double) a) * ((double) a - 1) + b)
                                    / (((double) knot.knotPoints.size()) * ((double) knot.knotPoints.size())));
                            shell.buff
                                    .add(shell.knotName + "_cut" + knotPoint11 + "-" + knotPoint12 + "and" + knotPoint22
                                            + "-" + knotPoint21);

                            throw new SegmentBalanceException(shell, internalCuts56, knot,
                                    knot.getSegment(knotPoint12, knotPoint11), s51,
                                    knot.getSegment(knotPoint21, knotPoint22), s52);
                        } else {
                            shell.buff.flush();
                        }

                        shell.buff.flush();

                    }

                    if (delta < minDelta) {
                        if (delta == d1) {
                            matchSegment1Final = s11;
                            matchSegment2Final = s12;
                            knotPoint1Final = knotPoint11;
                            knotPoint2Final = knotPoint21;
                            internalCuts = internalCuts12;
                            cutSegmentFinal = cutSegment1;
                            cutSegment2Final = cutSegment2;
                            segmentName = "d1";
                        } else if (delta == d2) {
                            matchSegment1Final = s21;
                            matchSegment2Final = s22;
                            knotPoint1Final = knotPoint21;
                            knotPoint2Final = knotPoint11;
                            internalCuts = internalCuts12;
                            cutSegmentFinal = cutSegment2;
                            cutSegment2Final = cutSegment1;
                            segmentName = "d2";
                        } else if (delta == d3) {
                            matchSegment1Final = s31;
                            matchSegment2Final = s32;
                            knotPoint1Final = knotPoint12;
                            knotPoint2Final = knotPoint22;
                            internalCuts = internalCuts34;
                            cutSegmentFinal = cutSegment1;
                            cutSegment2Final = cutSegment2;
                            segmentName = "d3";
                        } else if (delta == d4) {
                            matchSegment1Final = s41;
                            matchSegment2Final = s42;
                            knotPoint1Final = knotPoint22;
                            knotPoint2Final = knotPoint12;
                            internalCuts = internalCuts34;
                            cutSegmentFinal = cutSegment2;
                            cutSegment2Final = cutSegment1;
                            segmentName = "d4";
                        } else if (delta == d5) {
                            matchSegment1Final = s51;
                            matchSegment2Final = s52;
                            knotPoint1Final = knotPoint11;
                            knotPoint2Final = knotPoint22;
                            internalCuts = internalCuts56;
                            cutSegmentFinal = cutSegment1;
                            cutSegment2Final = cutSegment2;
                            segmentName = "d5";
                        } else if (delta == d6) {
                            matchSegment1Final = s61;
                            matchSegment2Final = s62;
                            knotPoint1Final = knotPoint22;
                            knotPoint2Final = knotPoint11;
                            internalCuts = internalCuts56;
                            cutSegmentFinal = cutSegment2;
                            cutSegment2Final = cutSegment1;
                            segmentName = "d6";
                        }

                        minDelta = delta;
                        overlapping = false;
                    }

                }
            }
        }
        if (overlapping) {
            SegmentBalanceException sbe = new SegmentBalanceException(shell, null, knot, cutSegmentFinal,
                    matchSegment1Final, cutSegmentFinal, matchSegment2Final);
            CutMatchList result = new CutMatchList(shell, sbe);
            if (superKnot != null) {
                result.addCut(cutSegmentFinal, matchSegment1Final, matchSegment2Final, knot, knotPoint1Final,
                        knotPoint2Final, superKnot, kpSegment, new ArrayList<>(), new ArrayList<>(), null, null, null,
                        true, false);
            } else {
                result.addCut(cutSegmentFinal, matchSegment1Final, matchSegment2Final, knot, knotPoint1Final,
                        knotPoint2Final);
            }
            return result;
        } else {

            SegmentBalanceException sbe = new SegmentBalanceException(shell, null, knot, cutSegmentFinal,
                    matchSegment1Final, cutSegment2Final, matchSegment2Final);
            CutMatchList result = new CutMatchList(shell, sbe);
            if (superKnot != null) {
                result.addTwoCut(cutSegmentFinal, cutSegment2Final, matchSegment1Final, matchSegment2Final, knot,
                        knotPoint1Final, knotPoint2Final, internalCuts, superKnot, kpSegment, new ArrayList<>(),
                        new ArrayList<>(), null, null,
                        null, true);
            } else {
                result.addTwoCut(cutSegmentFinal, cutSegment2Final, matchSegment1Final, matchSegment2Final, knot,
                        knotPoint1Final, knotPoint2Final,
                        internalCuts);
            }

            return result;

        }

    }

    MultiKeyMap<Integer, CutMatchList> cutLookup = new MultiKeyMap<>();
    double resolved = 0;
    double totalCalls = 0;

    public CutMatchList findCutMatchListFixedCut(Knot knot, VirtualPoint external1,
            VirtualPoint external2, Segment cutSegment1, VirtualPoint kp1, VirtualPoint cp1, Knot superKnot,
            Segment kpSegment, ArrayList<Segment> innerNeighborSegments, ArrayList<Segment> neighborSegments,
            Segment upperCutSegment, ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments,
            VirtualPoint topCutPoint,
            boolean needTwoNeighborMatches, boolean bothKnotPointsInside, boolean bothCutPointsOutside,
            VirtualPoint upperKnotPoint,
            Segment upperMatchSegment, VirtualPoint lowerKnotPoint, Segment lowerCutSegment)
            throws SegmentBalanceException {

        if (needTwoNeighborMatches && !bothCutPointsOutside) {

            return findCutMatchListFixedCutNeedTwoMatches(knot, external1, external2,
                    cutSegment1, kp1, cp1, superKnot, kpSegment, innerNeighborSegments,
                    neighborSegments, upperCutSegment, neighborCutSegments, topCutPoint, bothKnotPointsInside,
                    upperKnotPoint, upperMatchSegment);
        } else if (bothKnotPointsInside && !bothCutPointsOutside) {
            return findCutMatchListBothCutsInside(knot, external1, external2, cutSegment1, kp1, cp1, superKnot,
                    kpSegment, innerNeighborSegments, neighborSegments, upperCutSegment, neighborCutSegments,
                    topCutPoint, upperKnotPoint, upperMatchSegment);
        } else if (bothCutPointsOutside) {
            return findCutMatchListBothCutsOutside(knot, external1, external2, cutSegment1, kp1, cp1, superKnot,
                    kpSegment, innerNeighborSegments, neighborSegments, upperCutSegment, neighborCutSegments,
                    topCutPoint, upperKnotPoint, upperMatchSegment, lowerKnotPoint, lowerCutSegment);

        }
        SegmentBalanceException sbe = new SegmentBalanceException(shell, null, superKnot, cutSegment1,
                new Segment(kp1, external1, 0.0), upperCutSegment,
                new Segment(upperCutSegment.getOther(topCutPoint), upperCutSegment.getOther(topCutPoint), 0.0));
        totalCalls++;
        if (cutLookup.containsKey(knot.id, external2.id, kp1.id, cp1.id, superKnot.id)) {
            resolved++;
            // return cutLookup.get(knot.id, external2.id, kp1.id, cp1.id,
            // superKnot.id).copy();
        }

        if (!(knot.contains(cutSegment1.first) && knot.contains(cutSegment1.last))) {
            shell.buff.add(knot);
            shell.buff.add(cutSegment1);
            new CutMatchList(shell, sbe);
            throw new SegmentBalanceException(sbe);
        }
        if ((knot.contains(external1) || knot.contains(external2))) {
            float z = 1 / 0;
        }

        ArrayList<VirtualPoint> innerNeighborSegmentsFlattened = new ArrayList<>();
        for (Segment s : innerNeighborSegments) {
            innerNeighborSegmentsFlattened.add(s.first);
            innerNeighborSegmentsFlattened.add(s.last);
        }
        double minDelta = Double.MAX_VALUE;
        int overlapping = -1;
        Segment matchSegment2Final = null;
        Segment cutSegmentFinal = null;
        Segment cutSegment2Final = null;
        VirtualPoint knotPoint1Final = null;
        VirtualPoint knotPoint2Final = null;
        CutMatchList internalCuts = null;
        for (int a = 0; a < knot.knotPoints.size(); a++) {

            VirtualPoint knotPoint21 = knot.knotPoints.get(a);
            VirtualPoint knotPoint22 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
            Segment cutSegment2 = knot.getSegment(knotPoint21, knotPoint22);
            // I think that the continue state instead of being do the ct sgments partially
            // overlapp each other, should instead
            // be like
            if (cutSegment1.partialOverlaps(cutSegment2) && !cutSegment2.equals(kpSegment)) {
                continue;
            }
            if (cutSegment1.equals(cutSegment2)) {
                if (needTwoNeighborMatches) {
                    continue;
                }

                Segment s11 = kp1.getClosestSegment(external1, null);
                Segment s12 = cp1.getClosestSegment(external2, s11);
                double d1 = s12.distance;

                double delta = d1;

                boolean cutPointsAcross = false;
                for (Segment s : innerNeighborSegments) {
                    if (s.contains(cp1) && s.contains(kp1)) {
                        cutPointsAcross = true;
                    }
                }

                boolean hasSegment = cutPointsAcross;
                if (delta < minDelta && !hasSegment) {
                    matchSegment2Final = s12;
                    knotPoint1Final = kp1;
                    knotPoint2Final = cp1;
                    minDelta = delta;
                    overlapping = 1;
                    cutSegmentFinal = cutSegment1;
                }
            } else {
                double delta = Double.MAX_VALUE;
                VirtualPoint cp2 = knotPoint22;
                VirtualPoint kp2 = knotPoint21;

                boolean orphanFlag = wouldOrphan(cp1, kp1, cp2, kp2, knot.knotPointsFlattened);

                Segment s11 = kp1.getClosestSegment(external1, null);
                Segment s12 = kp2.getClosestSegment(external2, s11);
                boolean innerNeighbor = false;
                for (Segment s : innerNeighborSegments) {
                    if (s.contains(kp2)) {
                        innerNeighbor = true;
                    }
                }

                boolean replicatesNeighbor = false;
                for (Segment s : neighborSegments) {
                    if (s.equals(s12)) {
                        replicatesNeighbor = true;
                    }
                }

                boolean outerNeighbor = false;
                for (Segment s : neighborSegments) {
                    if (s.contains(kp2)) {
                        outerNeighbor = true;
                    }
                }

                boolean cutPointsAcross = false;
                for (Segment s : innerNeighborSegments) {
                    if (s.contains(cp1) && s.contains(cp2)) {
                        cutPointsAcross = true;
                    }
                }
                boolean neighborIntersect = false;
                if (innerNeighborSegmentsFlattened.contains(cp1) && innerNeighborSegmentsFlattened.contains(kp2)) {
                    neighborIntersect = true;
                }
                boolean hasSegment = replicatesNeighbor
                        || (innerNeighbor && outerNeighbor) || neighborIntersect || s12.equals(upperCutSegment);

                if (hasSegment) {
                    shell.buff.add("REEE: cutSeg1: " + cutSegment1 + " cutSeg2: " + cutSegment2 + " s12: " + s12
                            + " kp2 :" + kp2 + " kpSegment " + kpSegment);

                    if (orphanFlag) {
                        shell.buff.add("Would Orphan");
                    }
                }

                CutMatchList internalCuts1 = null;
                double d1 = Double.MAX_VALUE;
                if (!orphanFlag && !hasSegment) {
                    shell.buff.currentDepth++;
                    internalCuts1 = internalPathEngine.calculateInternalPathLength(kp1, cp1, external1, kp2, cp2,
                            external2, knot);
                    shell.buff.currentDepth--;
                    d1 = s12.distance + internalCuts1.delta - cutSegment2.distance;
                    delta = d1 < delta ? d1 : delta;
                }

                boolean orphanFlag2 = wouldOrphan(cp1, kp1, kp2, cp2, knot.knotPointsFlattened);

                Segment s21 = kp1.getClosestSegment(external1, null);
                Segment s22 = cp2.getClosestSegment(external2, s21);

                boolean innerNeighbor2 = false;
                for (Segment s : innerNeighborSegments) {
                    if (s.contains(cp2)) {
                        innerNeighbor2 = true;
                    }
                }

                boolean replicatesNeighbor2 = false;
                for (Segment s : neighborSegments) {
                    if (s.equals(s22)) {
                        replicatesNeighbor2 = true;
                    }
                }

                boolean outerNeighbor2 = false;
                for (Segment s : neighborSegments) {
                    if (s.contains(cp2)) {
                        outerNeighbor2 = true;
                    }
                }

                boolean cutPointsAcross2 = false;
                for (Segment s : innerNeighborSegments) {
                    if (s.contains(cp1) && s.contains(kp2)) {
                        cutPointsAcross2 = true;
                    }
                }
                boolean neighborIntersect2 = false;
                if (innerNeighborSegmentsFlattened.contains(cp1) && innerNeighborSegmentsFlattened.contains(kp2)) {
                    neighborIntersect2 = true;
                }
                boolean hasSegment2 = replicatesNeighbor2
                        || (innerNeighbor2 && outerNeighbor2) || neighborIntersect2 || s22.equals(upperCutSegment);
                // false;//
                // superKnot.hasSegment(s22)
                // ||
                // kpSegment.contains(cp2);

                if (orphanFlag2 || hasSegment2) {
                    shell.buff.add("REEE cutSeg1: " + cutSegment1 + " cutSeg2: " + cutSegment2 + " s22: " + s22
                            + " cp2 :" + cp2 + " kpSegment " + kpSegment);

                    if (orphanFlag2) {
                        shell.buff.add("Would Orphan");
                    }
                }

                CutMatchList internalCuts2 = null;
                double d2 = Double.MAX_VALUE;
                if (!orphanFlag2 && !hasSegment2) {
                    shell.buff.currentDepth++;
                    internalCuts2 = internalPathEngine.calculateInternalPathLength(kp1, cp1, external1, cp2, kp2,
                            external2, knot);
                    shell.buff.currentDepth--;
                    d2 = s22.distance + internalCuts2.delta - cutSegment2.distance;
                    delta = d2 < delta ? d2 : delta;

                }

                if (delta < minDelta) {
                    if (!orphanFlag && !hasSegment) {
                        matchSegment2Final = s12;
                        knotPoint1Final = kp1;
                        knotPoint2Final = kp2;
                        internalCuts = internalCuts1;
                        cutSegmentFinal = cutSegment1;
                        cutSegment2Final = cutSegment2;
                    } else {
                        matchSegment2Final = s22;
                        knotPoint1Final = kp1;
                        knotPoint2Final = cp2;
                        internalCuts = internalCuts2;
                        cutSegmentFinal = cutSegment1;
                        cutSegment2Final = cutSegment2;

                    }

                    minDelta = delta;
                    overlapping = 2;
                }

            }
        }
        if (overlapping == 1) {
            CutMatchList result = new CutMatchList(shell, sbe);
            shell.buff.add("Im gonna pre: " + neighborSegments);
            result.addCut(cutSegmentFinal, kp1.getClosestSegment(external1, null), matchSegment2Final, knot,
                    knotPoint1Final, knotPoint2Final, superKnot,
                    kpSegment, innerNeighborSegments, neighborSegments, neighborCutSegments, upperCutSegment,
                    topCutPoint, false, false);
            cutLookup.put(knot.id, external2.id, kp1.id, cp1.id, superKnot.id, result);
            return result;
        } else if (overlapping == 2) {
            CutMatchList result = new CutMatchList(shell, sbe);
            result.addTwoCut(cutSegmentFinal, cutSegment2Final, kp1.getClosestSegment(external1, null),
                    matchSegment2Final, knot, knotPoint1Final,
                    knotPoint2Final, internalCuts, superKnot, kpSegment, innerNeighborSegments, neighborSegments,
                    neighborCutSegments, upperCutSegment, topCutPoint, false);
            cutLookup.put(knot.id, external2.id, kp1.id, cp1.id, superKnot.id, result);
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            CutMatchList cml = new CutMatchList(shell, sbe);
            cml.addDumbCutMatch(knot, superKnot);
            throw new SegmentBalanceException(sbe);
        }
    }

    public CutMatchList findCutMatchListFixedCutNeedTwoMatches(Knot knot, VirtualPoint external1,
            VirtualPoint external2, Segment cutSegment1, VirtualPoint kp1, VirtualPoint cp1, Knot superKnot,
            Segment kpSegment, ArrayList<Segment> innerNeighborSegments, ArrayList<Segment> neighborSegments,
            Segment upperCutSegment, ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments,
            VirtualPoint topCutPoint, boolean bothKnotPointsInside,
            VirtualPoint upperKnotPoint, Segment upperMatchSegment)
            throws SegmentBalanceException {
        SegmentBalanceException sbe = new SegmentBalanceException(shell, null, superKnot, cutSegment1,
                new Segment(kp1, external1, 0.0), upperCutSegment,
                new Segment(upperCutSegment.getOther(topCutPoint), upperCutSegment.getOther(topCutPoint), 0.0));

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
            if (!uniqueNeighborPoints.contains(p.getSecond())) {
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
        Segment cutSegmentFinal = null;
        Segment cutSegment2Final = null;
        VirtualPoint knotPoint1Final = null;
        VirtualPoint knotPoint2Final = null;
        VirtualPoint knotPoint3Final = null;
        CutMatchList internalCuts = null;
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
                boolean leftHasOneOut = marchUntilHasOneKnotPoint(cutSegment1.first, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                boolean rightHasOneOut = marchUntilHasOneKnotPoint(cutSegment1.last, cutSegment1,
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

                    Segment s11 = knot.getSegment(external2, mirror1);
                    Segment s12 = knot.getSegment(external2, mirrorCP);
                    Segment s13 = knot.getSegment(otherNeighborPoint, mirrorKP);
                    Segment s14 = knot.getSegment(otherNeighborPoint2, mirrorCP);
                    Segment cut1 = knot.getSegment(mirrorCP, mirrorKP);
                    d1 = s11.distance + s12.distance + s13.distance + s14.distance;
                    delta = d1 < delta ? d1 : delta;

                    Segment s21 = knot.getSegment(external2, mirror1);
                    Segment s22 = knot.getSegment(external2, otherNeighborPoint);
                    Segment s23 = knot.getSegment(otherNeighborPoint2, mirrorCP);
                    Segment s24 = knot.getSegment(mirrorCP, mirrorKP);
                    Segment cut2 = knot.getSegment(mirror1, mirrorKP);
                    d2 = s21.distance + s22.distance + s23.distance + s24.distance;
                    delta = d2 < delta ? d2 : delta;

                    Segment s31 = knot.getSegment(external2, otherNeighborPoint2);
                    Segment s32 = knot.getSegment(external2, mirrorCP);
                    Segment s33 = knot.getSegment(otherNeighborPoint, mirror1);
                    Segment s34 = knot.getSegment(mirrorCP, mirrorKP);
                    Segment cut3 = knot.getSegment(mirror1, mirrorKP);
                    double d3 = s31.distance + s32.distance + s33.distance + s34.distance;
                    delta = d3 < delta ? d3 : delta;

                    Segment s41 = knot.getSegment(external2, mirror1);
                    Segment s42 = knot.getSegment(external2, mirrorCP);
                    Segment s43 = knot.getSegment(otherNeighborPoint, mirrorCP);
                    Segment s44 = knot.getSegment(otherNeighborPoint2, mirrorKP);
                    Segment cut4 = knot.getSegment(mirrorCP, mirrorKP);
                    double d4 = s41.distance + s42.distance + s43.distance + s44.distance;
                    delta = d4 < delta ? d4 : delta;

                    Segment s51 = knot.getSegment(external2, otherNeighborPoint);
                    Segment s52 = knot.getSegment(external2, mirrorCP);
                    Segment s53 = knot.getSegment(otherNeighborPoint2, mirror1);
                    Segment s54 = knot.getSegment(mirrorCP, mirrorKP);
                    Segment cut5 = knot.getSegment(mirror1, mirrorKP);
                    double d5 = s51.distance + s52.distance + s53.distance + s54.distance;
                    delta = d5 < delta ? d5 : delta;

                    Segment s61 = knot.getSegment(external2, otherNeighborPoint2);
                    Segment s62 = knot.getSegment(external2, mirror1);
                    Segment s63 = knot.getSegment(otherNeighborPoint, mirrorCP);
                    Segment s64 = knot.getSegment(mirrorCP, mirrorKP);
                    Segment cut6 = knot.getSegment(mirror1, mirrorKP);
                    double d6 = s61.distance + s62.distance + s63.distance + s64.distance;
                    delta = d6 < delta ? d6 : delta;

                    if (delta < minDelta) {
                        if (delta == d1) {
                            matchSegmentToCutPoint1 = s12;
                            matchSegmentToCutPoint2 = s13;
                            matchSegmentOuterKnotPointFinal = s14;
                            knotPoint1Final = mirrorCP;
                            knotPoint2Final = mirrorKP;
                            knotPoint3Final = mirrorCP;
                            cutSegment2Final = cut1;
                            shell.buff.add("d1");

                        } else if (delta == d2) {
                            matchSegmentToCutPoint1 = s21;
                            matchSegmentToCutPoint2 = s22;
                            matchSegmentOuterKnotPointFinal = s23;
                            knotPoint1Final = mirror1;
                            knotPoint2Final = otherNeighborPoint;
                            knotPoint3Final = mirrorCP;
                            cutSegment2Final = cut2;
                            shell.buff.add("d2");
                        } else if (delta == d3) {
                            matchSegmentToCutPoint1 = s31;
                            matchSegmentToCutPoint2 = s32;
                            matchSegmentOuterKnotPointFinal = s33;
                            knotPoint1Final = otherNeighborPoint2;
                            knotPoint2Final = mirrorCP;
                            knotPoint3Final = mirror1;
                            cutSegment2Final = cut3;
                            shell.buff.add("d3");
                        } else if (delta == d4) {
                            matchSegmentToCutPoint1 = s42;
                            matchSegmentToCutPoint2 = s43;
                            matchSegmentOuterKnotPointFinal = s44;
                            knotPoint1Final = mirrorCP;
                            knotPoint2Final = mirrorCP;
                            knotPoint3Final = mirrorKP;
                            cutSegment2Final = cut4;
                            shell.buff.add("d4");
                        } else if (delta == d5) {
                            matchSegmentToCutPoint1 = s51;
                            matchSegmentToCutPoint2 = s52;
                            matchSegmentOuterKnotPointFinal = s53;
                            knotPoint1Final = otherNeighborPoint;
                            knotPoint2Final = mirrorCP;
                            knotPoint3Final = mirror1;
                            cutSegment2Final = cut5;
                            shell.buff.add("d5");
                        } else if (delta == d6) {
                            matchSegmentToCutPoint1 = s61;
                            matchSegmentToCutPoint2 = s62;
                            matchSegmentOuterKnotPointFinal = s63;
                            knotPoint1Final = otherNeighborPoint2;
                            knotPoint2Final = mirror1;
                            knotPoint3Final = mirrorCP;
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
                            knotPoint1Final = mirror1;
                            knotPoint2Final = mirror21;
                            knotPoint3Final = mirror22;
                            cutSegmentFinal = cutSegment1;
                            cutSegment2Final = cutSegment2;
                        } else {
                            matchSegmentToCutPoint1 = s21;
                            matchSegmentToCutPoint2 = s22;
                            matchSegmentOuterKnotPointFinal = s23;
                            knotPoint1Final = mirror1;
                            knotPoint2Final = mirror22;
                            knotPoint3Final = mirror21;
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
            result.addCut(cutSegmentFinal, kp1.getClosestSegment(external1, null), matchSegmentToCutPoint1, knot,
                    kp1, knotPoint1Final, superKnot,
                    kpSegment, innerNeighborSegments, neighborSegments, neighborCutSegments, upperCutSegment,
                    topCutPoint, false, true);
            return result;

        } else if (overlapping == 2) {
            shell.buff.add("cut1: " + cutSegmentFinal + " cut2: " + cutSegment2Final + " matches: " +
                    matchSegmentToCutPoint1 + " " + matchSegmentToCutPoint2 + " " + matchSegmentOuterKnotPointFinal);
            CutMatchList result = new CutMatchList(shell, sbe);
            result.addTwoCutTwoMatch(cutSegmentFinal, cutSegment2Final,
                    kp1.getClosestSegment(external1, null), kp1,
                    upperMatchSegment, upperKnotPoint,
                    matchSegmentToCutPoint1, knotPoint1Final,
                    matchSegmentToCutPoint2, knotPoint2Final,
                    matchSegmentOuterKnotPointFinal, knotPoint3Final,
                    bothKnotPointsInside, knot, superKnot, kpSegment, innerNeighborSegments, neighborSegments,
                    neighborCutSegments, upperCutSegment, topCutPoint);
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            CutMatchList cml = new CutMatchList(shell, sbe);
            cml.addDumbCutMatch(knot, superKnot);
            throw new SegmentBalanceException(sbe);
        }
    }

    private CutMatchList findCutMatchListBothCutsOutside(Knot knot, VirtualPoint external1, VirtualPoint external2,
            Segment cutSegment1, VirtualPoint kp1, VirtualPoint cp1, Knot superKnot, Segment kpSegment,
            ArrayList<Segment> innerNeighborSegments, ArrayList<Segment> neighborSegments, Segment upperCutSegment,
            ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments, VirtualPoint topCutPoint,
            VirtualPoint upperKnotPoint, Segment upperMatchSegment, VirtualPoint lowerKnotPoint,
            Segment lowerCutSegment) throws SegmentBalanceException {

        SegmentBalanceException sbe = new SegmentBalanceException(shell, null, superKnot, cutSegment1,
                new Segment(kp1, external1, 0.0), upperCutSegment,
                new Segment(upperKnotPoint, upperKnotPoint, 0.0));
        double minDelta = Double.MAX_VALUE;
        int overlapping = -1;
        Segment matchSegmentToCutPoint1 = null;
        Segment matchSegmentToCutPoint2 = null;
        Segment matchSegmentOuterKnotPointFinal = null;
        Segment cutSegmentFinal = null;
        Segment cutSegment2Final = null;
        VirtualPoint knotPoint1Final = null;
        VirtualPoint knotPoint2Final = null;
        VirtualPoint knotPoint3Final = null;
        CutMatchList internalCuts = null;


        VirtualPoint leftNeighbor = upperCutSegment.getOther(upperKnotPoint);

        VirtualPoint rightNeighbor = lowerCutSegment.getOther(lowerKnotPoint);
        for (int a = 0; a < knot.knotPoints.size(); a++) {

            VirtualPoint knotPoint21 = knot.knotPoints.get(a);
            VirtualPoint knotPoint22 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
            Segment cutSegment2 = knot.getSegment(knotPoint21, knotPoint22);

            if (cutSegment1.equals(cutSegment2)) {
                continue;
            } else {

                boolean leftHasOneOut = marchUntilHasOneKnotPoint(cutSegment1.first, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                boolean rightHasOneOut = marchUntilHasOneKnotPoint(cutSegment1.last, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                if (!(leftHasOneOut && rightHasOneOut) || !((cutSegment1.contains(kp1) || cutSegment2.contains(kp1))
                        && (cutSegment1.contains(upperKnotPoint) || cutSegment2.contains(upperKnotPoint)))) {
                    shell.buff.add("ONE SIDE WOULD BE UNBALANCED " + cutSegment2);

                    continue;
                }
                double delta = Double.MAX_VALUE;
                VirtualPoint mirror1 = null;
                VirtualPoint mirror2 = null;
                if(!cutSegment1.first.equals(upperKnotPoint) && !cutSegment1.first.equals(lowerKnotPoint)){
                    mirror1 = cutSegment1.first;
                }
                if(!cutSegment1.last.equals(upperKnotPoint) && !cutSegment1.last.equals(lowerKnotPoint)){
                    if(mirror1 == null){
                        mirror1 = cutSegment1.last;
                    }else{
                        mirror2 = cutSegment1.last;
                    }
                }
                if(!cutSegment2.first.equals(upperKnotPoint) && !cutSegment2.first.equals(lowerKnotPoint)){
                    if(mirror1 == null){
                        mirror1 = cutSegment2.first;
                    }else if (mirror2 == null){
                        mirror2 = cutSegment2.first;
                    }
                }
                if(!cutSegment2.last.equals(upperKnotPoint) && !cutSegment2.last.equals(lowerKnotPoint)){
                    if(mirror1 == null){
                        mirror1 = cutSegment2.last;
                    }else if (mirror2 == null){
                        mirror2 = cutSegment2.last;
                    }
                }
                if(cutSegment1.equals(kpSegment)){
                    if(mirror2 == null){
                        mirror2 = cutSegment2.getOther(mirror1);
                    }
                }
                
                if(cutSegment2.equals(kpSegment)){
                    if(mirror2 == null){
                        mirror2 = cutSegment1.getOther(mirror1);
                    }
                }

                double d2 = Double.MAX_VALUE;
                double d1 = Double.MAX_VALUE;
                if(mirror2 == null || mirror1 == null){
                    CutMatchList cml = new CutMatchList(shell, sbe);
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
                        knotPoint1Final = mirror1;
                        knotPoint2Final = mirror2;
                        cutSegment2Final = cutSegment2;
                        shell.buff.add("d1");

                    } else if (delta == d2) {
                        matchSegmentToCutPoint1 = s21;
                        matchSegmentToCutPoint2 = s22;
                        knotPoint1Final = mirror2;
                        knotPoint2Final = mirror1;
                        cutSegment2Final = cutSegment2;
                        shell.buff.add("d2");
                    }
                    shell.buff.add("Group1 cut1: " + cutSegmentFinal + " cut2: " + cutSegment2 + " matches: " +
                            s11 + " " + s12);
                    shell.buff.add("Group2 cut1: " + cutSegmentFinal + " cut2: " + cutSegment2 + " matches: " +
                            s21 + " " + s22);

                    cutSegmentFinal = cutSegment1;

                    minDelta = delta;
                    overlapping = 2;

                }
            }
        }
        if (overlapping == 2) {
            shell.buff.add("cut1: " + cutSegmentFinal + " cut2: " + cutSegment2Final + " matches: " +
                    matchSegmentToCutPoint1 + " " + matchSegmentToCutPoint2 + " " + matchSegmentOuterKnotPointFinal);
            CutMatchList result = new CutMatchList(shell, sbe);
            result.addTwoCutTwoMatch(cutSegmentFinal, cutSegment2Final,
                    kp1.getClosestSegment(external1, null), kp1,
                    upperMatchSegment, upperKnotPoint,
                    matchSegmentToCutPoint1, knotPoint1Final,
                    matchSegmentToCutPoint2, knotPoint2Final,
                    matchSegmentOuterKnotPointFinal, knotPoint3Final,
                    true, knot, superKnot, kpSegment, innerNeighborSegments, neighborSegments,
                    neighborCutSegments, upperCutSegment, topCutPoint);
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            CutMatchList cml = new CutMatchList(shell, sbe);
            cml.addDumbCutMatch(knot, superKnot);
            throw new SegmentBalanceException(sbe);
        }
    }

    public CutMatchList findCutMatchListBothCutsInside(Knot knot, VirtualPoint external1,
            VirtualPoint external2, Segment cutSegment1, VirtualPoint kp1, VirtualPoint cp1, Knot superKnot,
            Segment kpSegment, ArrayList<Segment> innerNeighborSegments, ArrayList<Segment> neighborSegments,
            Segment upperCutSegment, ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments,
            VirtualPoint topCutPoint, VirtualPoint upperKnotPoint, Segment upperMatchSegment)
            throws SegmentBalanceException {

        SegmentBalanceException sbe = new SegmentBalanceException(shell, null, superKnot, cutSegment1,
                new Segment(kp1, external1, 0.0), upperCutSegment,
                new Segment(upperCutSegment.getOther(topCutPoint), upperCutSegment.getOther(topCutPoint), 0.0));

        double minDelta = Double.MAX_VALUE;
        int overlapping = -1;
        Segment matchSegmentToCutPoint1 = null;
        Segment matchSegmentToCutPoint2 = null;
        Segment matchSegmentOuterKnotPointFinal = null;
        Segment cutSegmentFinal = null;
        Segment cutSegment2Final = null;
        VirtualPoint knotPoint1Final = null;
        VirtualPoint knotPoint2Final = null;
        VirtualPoint knotPoint3Final = null;
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
                knotPoint1Final = leftKnotPoint;
                knotPoint2Final = leftCutPoint;
                knotPoint3Final = rightKnotPoint;
                cutSegment2Final = cut1;
                shell.buff.add("d1");
                neighborCutSegments.remove(1);
                neighborSegments.remove(rightCut);

            } else if (delta == d2) {
                matchSegmentToCutPoint1 = s21;
                matchSegmentToCutPoint2 = s22;
                matchSegmentOuterKnotPointFinal = s23;
                knotPoint1Final = rightKnotPoint;
                knotPoint2Final = rightCutPoint;
                knotPoint3Final = leftKnotPoint;
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

            result.addTwoCutTwoMatch(cutSegmentFinal, cutSegment2Final,
                    kp1.getClosestSegment(external1, null), kp1,
                    upperMatchSegment, upperKnotPoint,
                    matchSegmentToCutPoint1, knotPoint1Final,
                    matchSegmentToCutPoint2, knotPoint2Final,
                    matchSegmentOuterKnotPointFinal, knotPoint3Final,
                    true, knot, superKnot, kpSegment, innerNeighborSegments, neighborSegments,
                    neighborCutSegments, upperCutSegment, topCutPoint);
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            CutMatchList cml = new CutMatchList(shell, sbe);
            cml.addDumbCutMatch(knot, superKnot);
            throw new SegmentBalanceException(sbe);
        }
    }

    private boolean wouldOrphan(VirtualPoint cutp1, VirtualPoint knotp1, VirtualPoint cutp2, VirtualPoint knotp2,
            ArrayList<VirtualPoint> knotList) {
        int cp1 = knotList.indexOf(cutp1);
        int kp1 = knotList.indexOf(knotp1);

        int cp2 = knotList.indexOf(cutp2);
        int kp2 = knotList.indexOf(knotp2);

        if ((cp1 > kp1 && cp1 < kp2 && cp2 > kp1 && cp2 < kp2)
                ||
                (cp1 > kp2 && cp1 < kp1 && cp2 > kp2 && cp2 < kp1)
                ||
                (kp1 > cp2 && kp1 < cp1 && kp2 > cp2 && kp2 < cp1)
                ||
                (kp1 > cp1 && kp1 < cp2 && kp2 > cp1 && kp2 < cp2)
                ||
                (kp1 > cp1 && kp1 > cp2 && kp2 > cp1 && kp2 > cp2)
                ||
                (kp1 < cp1 && kp1 < cp2 && kp2 < cp1 && kp2 < cp2)) {
            return true;
        }

        return false;
    }

    private boolean marchUntilHasOneKnotPoint(VirtualPoint startPoint, Segment awaySegment,
            Segment untilSegment, VirtualPoint kp1, VirtualPoint kp2, Knot knot) {
        int idx = knot.knotPoints.indexOf(startPoint);
        int idx2 = knot.knotPoints.indexOf(awaySegment.getOther(startPoint));
        int marchDirection = idx2 - idx < 0 ? -1 : 1;
        if (idx == 0 && idx2 == knot.knotPoints.size() - 1) {
            marchDirection = -1;
        }
        if (idx2 == 0 && idx == knot.knotPoints.size() - 1) {
            marchDirection = 1;
        }
        marchDirection = -marchDirection;
        int totalIter = 0;
        int numKnotPoints = 0;
        VirtualPoint first = knot.knotPoints.get(idx);
        if (first.equals(kp1) || first.equals(kp2)) {
            numKnotPoints++;
        }
        while (true) {
            VirtualPoint k1 = knot.knotPoints.get(idx);
            int next = idx + marchDirection;
            if (marchDirection < 0 && next < 0) {
                next = knot.knotPoints.size() - 1;
            } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                next = 0;
            }
            VirtualPoint k2 = knot.knotPoints.get(next);
            if (knot.getSegment(k1, k2).equals(untilSegment)) {
                return true;
            }
            if (k2.equals(kp1)) {
                numKnotPoints++;
            }
            if (k2.equals(kp2)) {
                numKnotPoints++;
            }
            if (numKnotPoints >= 2) {
                return false;
            }
            idx = next;
            totalIter++;
            if (totalIter > knot.knotPoints.size()) {
                float z = 1 / 0;

            }
        }
    }

    public ArrayList<VirtualPoint> cutKnot(ArrayList<VirtualPoint> knotList) throws SegmentBalanceException {
        knotList = new ArrayList<>(knotList);
        // move on to the cutting phase
        VirtualPoint prevPoint = knotList.get(knotList.size() - 1);
        for (int i = 0; i < knotList.size(); i++) {

            VirtualPoint vp = knotList.get(i);
            shell.buff.add("Checking Point: " + vp);
            if (vp.isKnot) {

                // Cases:
                // 1. cut segments are the same vps and opposite orientation
                // very cool, un tie the knot normally without length checks
                // 2. cut segments are the same vps and same orientation
                // figure out which external point is best to match to first
                // 3. cut segments have the same knot points but different cut points
                // look at knotPoint's matches and figure out which orientation is smallest
                // 4. cut segments have different knot points but the same cut point
                // look at both cuts and figure out which is smaller
                // 5.
                Knot knot = (Knot) vp;
                shell.buff.add("Found Knot!" + knot.fullString());

                VirtualPoint external1 = knot.match1;
                VirtualPoint external2 = knot.match2;

                if ((external1.getHeight() > 1 || knot.getHeight() > 1 || external2.getHeight() > 1)) {
                    shell.buff.add("Need to simplify knots internally before matching : knot: " + knot
                            + " external1: " + external1 + " external2: " + external2);
                    Knot knotNew = flattenKnots(knot, external1, external2, knotList);
                    int prevIdx = knotList.indexOf(knotNew) - 1;
                    if (prevIdx < 0) {
                        prevIdx = knotList.size() - 1;
                    }
                    prevPoint = knotList.get(prevIdx);
                    i = i - 1;
                    continue;
                } else {
                    shell.updateSmallestKnot(knot);
                    shell.updateSmallestCommonKnot(knot);
                    if (!flatKnots.containsKey(knot.id)) {
                        flatKnots.put(knot.id, knot);
                    }
                }

                CutMatchList cutMatchList = findCutMatchList(knot, external1, external2, null, null);
                external1.reset(knot);
                external2.reset(knot);

                shell.buff.add("===================================================");
                shell.buff.add(knot);
                shell.buff.add(knotList);
                shell.buff.add(cutMatchList);

                shell.buff.add("===================================================");
                ArrayList<CutMatch> cutMatches = cutMatchList.cutMatches;
                for (int j = 0; j < cutMatches.size(); j++) {
                    CutMatch cutMatch = cutMatches.get(j);
                    for (Segment cutSegment : cutMatch.cutSegments) {

                        Point pcut1 = (Point) cutSegment.first;
                        Point pcut2 = (Point) cutSegment.last;
                        pcut1.reset(pcut2);
                        pcut2.reset(pcut1);
                    }
                    for (Segment matchSegment : cutMatch.matchSegments) {

                        Point pMatch1 = (Point) matchSegment.first;
                        VirtualPoint match1 = pMatch1;
                        if (external1.contains(pMatch1)) {
                            match1 = external1;
                        } else if (external2.contains(pMatch1)) {
                            match1 = external2;
                        }
                        Point pMatch2 = (Point) matchSegment.last;
                        VirtualPoint match2 = pMatch2;
                        if (external1.contains(pMatch2)) {
                            match2 = external1;
                        } else if (external2.contains(pMatch2)) {
                            match2 = external2;
                        }
                        if (!match1.hasMatch(match2, pMatch2, pMatch1, matchSegment)) {
                            match1.setMatch2(match2, pMatch2, pMatch1, matchSegment);
                        }
                        if (!match2.hasMatch(match1, pMatch1, pMatch2, matchSegment)) {
                            match2.setMatch2(match1, pMatch1, pMatch2, matchSegment);
                        }

                    }
                }
                knotList.remove(vp);
                CutMatch finalCut = cutMatchList.cutMatches.get(0);
                VirtualPoint addPoint = finalCut.kp2;
                if (finalCut.kp1.match1.equals(prevPoint) || finalCut.kp1.match2.equals(prevPoint)) {
                    addPoint = finalCut.kp1;
                }
                VirtualPoint prevPointTemp = prevPoint;
                for (int j = 0; j < knot.knotPoints.size(); j++) {
                    shell.buff.add("adding: " + addPoint.fullString());
                    knotList.add(i + j, addPoint);
                    if (prevPointTemp.equals(addPoint.match2)) {
                        prevPointTemp = addPoint;
                        addPoint = addPoint.match1;
                    } else {
                        prevPointTemp = addPoint;
                        addPoint = addPoint.match2;
                    }
                }
                shell.buff.add(flatKnots);
                shell.buff.add(knotList);

                cutKnotNum++;
                if (cutKnotNum > 20) {
                    float z = 1 / 0;
                }
                i = i - 1;
            }
            if (!vp.isKnot) {
                prevPoint = vp;
            }
        }

        shell.buff.add(" " + resolved / totalCalls * 100 + " %");
        return knotList;
    }

    public Knot flattenKnots(Knot knot, VirtualPoint external1, VirtualPoint external2,
            ArrayList<VirtualPoint> knotList) throws SegmentBalanceException {

        ArrayList<VirtualPoint> flattenKnots = cutKnot(knot.knotPoints);
        Knot knotNew = new Knot(flattenKnots, shell);
        knotNew.copyMatches(knot);
        flatKnots.put(knotNew.id, knotNew);
        shell.updateSmallestCommonKnot(knotNew);
        shell.buff.add(flatKnots);

        boolean makeExternal1 = external1.isKnot;

        boolean same = external1.equals(external2);
        boolean makeExternal2 = external2.isKnot && !same;

        Knot external1Knot = null;
        ArrayList<VirtualPoint> flattenKnotsExternal1 = null;
        Knot external1New = null;
        if (makeExternal1) {

            external1Knot = (Knot) external1;
            flattenKnotsExternal1 = cutKnot(external1Knot.knotPoints);
            external1New = new Knot(flattenKnotsExternal1, shell);
            flatKnots.put(external1New.id, external1New);
            shell.updateSmallestCommonKnot(external1New);
            external1New.copyMatches(external1);
        }
        Knot external2Knot = null;
        ArrayList<VirtualPoint> flattenKnotsExternal2 = null;
        Knot external2New = null;
        if (makeExternal2) {

            external2Knot = (Knot) external2;
            flattenKnotsExternal2 = cutKnot(external2Knot.knotPoints);
            external2New = new Knot(flattenKnotsExternal2, shell);
            external2New.copyMatches(external2);
            shell.updateSmallestCommonKnot(external2New);
            flatKnots.put(external2New.id, external2New);
        }

        if (external1.contains(knot.match1endpoint)) {
            if (makeExternal1) {
                knotNew.match1 = external1New;
            } else if (!same || (!makeExternal1 && !makeExternal2)) {
                knotNew.match1 = external1;
            }
        }
        if (external1.contains(knot.match2endpoint)) {
            if (makeExternal1) {
                knotNew.match2 = external1New;
            } else if (!same || (!makeExternal1 && !makeExternal2)) {
                knotNew.match2 = external1;
            }
        }
        if (external2.contains(knot.match1endpoint)) {
            if (makeExternal2) {
                knotNew.match1 = external2New;
            } else if (!same || (!makeExternal1 && !makeExternal2)) {
                knotNew.match1 = external2;
            }
        }
        if (external2.contains(knot.match2endpoint)) {
            if (makeExternal2) {
                knotNew.match2 = external2New;
            } else if (!same || (!makeExternal1 && !makeExternal2)) {
                knotNew.match2 = external2;
            }
        }

        if (knotNew.contains(external1.match1endpoint)) {
            if (makeExternal1) {

                external1New.match1 = knotNew;
            } else {
                external1.match1 = knotNew;
            }
        }
        if (knotNew.contains(external1.match2endpoint)) {
            if (makeExternal1) {
                external1New.match2 = knotNew;
            } else {
                external1.match2 = knotNew;
            }
        }

        if (knotNew.contains(external2.match1endpoint)) {
            if (makeExternal2) {
                external2New.match1 = knotNew;
            } else {
                external2.match1 = knotNew;
            }
        }
        if (knotNew.contains(external2.match2endpoint)) {
            if (makeExternal2) {
                external2New.match2 = knotNew;
            } else {
                external2.match2 = knotNew;
            }
        }
        if (makeExternal1 && external1New.contains(external2.match1endpoint)) {
            if (makeExternal2) {
                external2New.match1 = external1New;
            } else {
                external2.match1 = external1New;
            }
        }
        if (makeExternal1 && external1New.contains(external2.match2endpoint)) {
            if (makeExternal2) {
                external2New.match2 = external1New;
            } else {
                external2.match2 = external1New;
            }
        }
        if (makeExternal2 && external2New.contains(external1.match1endpoint)) {
            if (makeExternal1) {
                external1New.match1 = external2New;
            } else {
                external1.match1 = external2New;
            }
        }
        if (makeExternal2 && external2New.contains(external1.match2endpoint)) {
            if (makeExternal1) {
                external1New.match2 = external2New;
            } else {
                external1.match2 = external2New;
            }
        }
        if (makeExternal1) {
            if (external1New.contains(external1New.match1.match1endpoint)) {
                external1New.match1.match1 = external1New;
            }

            if (external1New.contains(external1New.match1.match2endpoint)) {
                external1New.match1.match2 = external1New;
            }
            shell.buff.add(external1New.fullString());
            shell.buff.add(external2New != null ? external2New.fullString() : "null");
            shell.buff.add(knotNew.fullString());
            if (external1New.contains(external1New.match2.match1endpoint)) {
                external1New.match2.match1 = external1New;
            }

            if (external1New.contains(external1New.match2.match2endpoint)) {
                external1New.match2.match2 = external1New;
            }
        }
        if (makeExternal2) {
            if (external2New.contains(external2New.match1.match1endpoint)) {
                external2New.match1.match1 = external2New;
            }

            if (external2New.contains(external2New.match1.match2endpoint)) {
                external2New.match1.match2 = external2New;
            }
            if (external2New.contains(external2New.match2.match1endpoint)) {
                external2New.match2.match1 = external2New;
            }

            if (external2New.contains(external2New.match2.match2endpoint)) {
                external2New.match2.match2 = external2New;
            }
        }

        if (makeExternal1) {
            int idx = knotList.indexOf(external1);
            knotList.add(idx, external1New);
            knotList.remove(external1);
        }

        if (makeExternal2) {
            int idx = knotList.indexOf(external2);
            knotList.add(idx, external2New);
            knotList.remove(external2);
        }
        int idx2 = knotList.indexOf(knot);
        knotList.add(idx2, knotNew);
        knotList.remove(knot);

        shell.buff.add(external1New);
        shell.buff.add(external1);
        shell.buff.add(external2New);
        shell.buff.add(external2);
        shell.buff.add(knotNew);
        shell.buff.add(knotList);
        shell.buff.add(knotNew.fullString());
        return knotNew;
    }

}