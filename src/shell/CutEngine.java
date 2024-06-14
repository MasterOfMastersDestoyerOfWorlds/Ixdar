package shell;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.collections4.map.MultiKeyMap;

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
            Segment kpSegment) throws SegmentBalanceException, BalancerException {

        double minDelta = Double.MAX_VALUE;
        boolean overlapping = true;
        CutMatchList result = null;
        String resultloc = "";

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

                    CutInfo c1 = new CutInfo(shell, knotPoint11, knotPoint12, cutSegment1, external1, knotPoint12,
                            knotPoint11, cutSegment1,
                            external2, knot, null);
                    SegmentBalanceException sbe = new SegmentBalanceException(shell, null, c1);
                    BalanceMap balanceMap1 = new BalanceMap(knot, sbe);
                    balanceMap1.addCut(knotPoint11, knotPoint12);
                    balanceMap1.addExternalMatch(knotPoint11, external1);
                    balanceMap1.addExternalMatch(knotPoint12, external2);
                    c1.balanceMap = balanceMap1;

                    CutMatchList cutMatch1 = new CutMatchList(shell, sbe, c1.superKnot);
                    cutMatch1.addCutMatch(new Segment[] { cutSegment1 },
                            new Segment[] { s11, s12 }, c1,
                            "CutEngineSegmentsFullyOverlap1");
                    double d1 = cutMatch1.delta;

                    Segment s21 = knotPoint12.getClosestSegment(external1, null);
                    Segment s22 = knotPoint11.getClosestSegment(external2, s21);

                    CutInfo c2 = new CutInfo(shell, knotPoint12, knotPoint11, cutSegment1, external1, knotPoint11,
                            knotPoint12, cutSegment1,
                            external2, knot, balanceMap1);

                    CutMatchList cutMatch2 = new CutMatchList(shell, sbe, c2.superKnot);
                    cutMatch2.addCutMatch(new Segment[] { cutSegment1 },
                            new Segment[] { s21, s22 }, c2,
                            "CutEngineSegmentsFullyOverlap2");
                    double d2 = cutMatch2.delta;

                    double delta = d2;
                    if (d1 < d2) {
                        delta = d1;
                    }
                    if (delta < minDelta) {
                        if (d1 < d2) {
                            result = cutMatch1;
                            resultloc = "overlap1";
                        } else {
                            result = cutMatch2;
                            resultloc = "overlap2";
                        }
                        minDelta = delta;
                        overlapping = true;
                    }
                } else {
                    double delta = Double.MAX_VALUE;

                    CutInfo c1 = new CutInfo(shell, knotPoint11, knotPoint12, cutSegment1, external1, knotPoint21,
                            knotPoint22, cutSegment2,
                            external2, knot, null);
                    SegmentBalanceException sbe12 = new SegmentBalanceException(shell, null, c1);
                    BalanceMap balanceMap12 = new BalanceMap(knot, sbe12);
                    balanceMap12.addCut(knotPoint11, knotPoint12);
                    balanceMap12.addCut(knotPoint21, knotPoint22);
                    balanceMap12.addExternalMatch(knotPoint11, external1);
                    balanceMap12.addExternalMatch(knotPoint21, external2);
                    c1.balanceMap = balanceMap12;
                    Segment s11 = knotPoint11.getClosestSegment(external1, null);
                    Segment s12 = knotPoint21.getClosestSegment(external2, s11);
                    shell.buff.add("12 -------------------------------------------");
                    CutMatchList internalCuts12 = internalPathEngine.calculateInternalPathLength(
                            knotPoint11, knotPoint12, external1,
                            knotPoint21, knotPoint22, external2, knot, balanceMap12);
                    CutMatchList cutMatch1 = new CutMatchList(shell, sbe12, c1.superKnot);
                    cutMatch1.addCutMatch(new Segment[] { cutSegment1, cutSegment2 },
                            new Segment[] { s12, s11 },
                            internalCuts12, c1, "CutEngine1");
                    double d1 = cutMatch1.delta;
                    delta = d1 < delta ? d1 : delta;

                    CutInfo c2 = new CutInfo(shell, knotPoint21, knotPoint22, cutSegment2, external1, knotPoint11,
                            knotPoint12, cutSegment1,
                            external2, knot, balanceMap12);

                    Segment s21 = knotPoint21.getClosestSegment(external1, null);
                    Segment s22 = knotPoint11.getClosestSegment(external2, s21);
                    CutMatchList cutMatch2 = new CutMatchList(shell, sbe12, c2.superKnot);
                    cutMatch2.addCutMatch(new Segment[] { cutSegment1, cutSegment2 },
                            new Segment[] { s22, s21 },
                            internalCuts12, c2, "CutEngine2");
                    double d2 = cutMatch2.delta;
                    delta = d2 < delta ? d2 : delta;

                    boolean foundShorter12 = internalCuts12.delta <= knotPoint12.getClosestSegment(knotPoint22,
                            null).distance;
                    boolean balanced12 = internalCuts12.checkCutMatchBalance(s11, s12, cutSegment1,
                            new Segment[] { cutSegment2 },
                            c1, false, true);
                    if (!balanced12 || !foundShorter12) {
                        shell.buff.add(knot);
                        shell.buff.add("Cut Info 12: Cut1: knotPoint1: " + knotPoint11 + " cutpointA: " + knotPoint12
                                + " ex1:" + external1 + " knotPoint2: " + knotPoint21 + " cutPointB: " + knotPoint22
                                + " ex2: " + external2);
                        shell.buff.add("%complete this knot: " + 100.0 * (((double) a) * ((double) a - 1) + b)
                                / (((double) knot.knotPoints.size()) * ((double) knot.knotPoints.size())));
                        shell.buff.add(shell.knotName + "_cut" + knotPoint11 + "-" + knotPoint12 + "and" + knotPoint21
                                + "-" + knotPoint22);

                        if (!balanced12) {
                            shell.buff.add("UNBALANCED SEGMENTS");
                            throw new SegmentBalanceException(shell, internalCuts12, c1);
                        }
                        if (!foundShorter12) {
                            shell.buff.add("NO SHORTER PATH FOUND THAN SIMPLE CUT : ");
                            throw new ShorterPathNotFoundException(shell, internalCuts12, c1);
                        }
                    } else {
                        shell.buff.flush();
                    }

                    shell.buff.add("34 -------------------------------------------");

                    CutInfo c3 = new CutInfo(shell, knotPoint12, knotPoint11, cutSegment1, external1, knotPoint22,
                            knotPoint21, cutSegment2,
                            external2, knot, null);
                    SegmentBalanceException sbe34 = new SegmentBalanceException(shell, null, c3);
                    BalanceMap balanceMap34 = new BalanceMap(knot, sbe34);
                    balanceMap34.addCut(knotPoint11, knotPoint12);
                    balanceMap34.addCut(knotPoint21, knotPoint22);
                    balanceMap34.addExternalMatch(knotPoint12, external1);
                    balanceMap34.addExternalMatch(knotPoint22, external2);
                    c3.balanceMap = balanceMap34;
                    Segment s31 = knotPoint12.getClosestSegment(external1, null);
                    Segment s32 = knotPoint22.getClosestSegment(external2, s31);
                    CutMatchList internalCuts34 = internalPathEngine.calculateInternalPathLength(
                            knotPoint12, knotPoint11, external1,
                            knotPoint22, knotPoint21, external2, knot, balanceMap34);

                    CutMatchList cutMatch3 = new CutMatchList(shell, sbe34, c3.superKnot);
                    cutMatch3.addCutMatch(new Segment[] { cutSegment1, cutSegment2 },
                            new Segment[] { s32, s31 },
                            internalCuts34, c3, "CutEngine3");
                    double d3 = cutMatch3.delta;
                    delta = d3 < delta ? d3 : delta;

                    CutInfo c4 = new CutInfo(shell, knotPoint22, knotPoint21, cutSegment2, external1, knotPoint12,
                            knotPoint11, cutSegment1,
                            external2, knot, balanceMap34);

                    Segment s41 = knotPoint22.getClosestSegment(external1, null);
                    Segment s42 = knotPoint12.getClosestSegment(external2, s41);

                    CutMatchList cutMatch4 = new CutMatchList(shell, sbe34, c4.superKnot);
                    cutMatch4.addCutMatch(new Segment[] { cutSegment1, cutSegment2 },
                            new Segment[] { s42, s41 },
                            internalCuts34, c4, "CutEngine4");
                    double d4 = cutMatch4.delta;
                    delta = d4 < delta ? d4 : delta;

                    boolean foundShorter34 = internalCuts34.delta <= knotPoint11.getClosestSegment(knotPoint21,
                            null).distance;
                    boolean balanced34 = internalCuts34.checkCutMatchBalance(s31, s32, cutSegment1,
                            new Segment[] { cutSegment2 }, c3,
                            false, true);
                    if (!balanced34 || !foundShorter34) {
                        shell.buff.add(knot);
                        shell.buff.add("Cut Info 34: Cut1: knotPoint1: " + knotPoint12 + " cutpointA: " + knotPoint11
                                + " ex1:" + external1 + " knotPoint2: " + knotPoint22 + " cutPointB: " + knotPoint21
                                + " ex2: " + external2);
                        shell.buff.add("%complete this knot: " + 100.0 * (((double) a) * ((double) a - 1) + b)
                                / (((double) knot.knotPoints.size()) * ((double) knot.knotPoints.size())));
                        shell.buff.add(shell.knotName + "_cut" + knotPoint12 + "-" + knotPoint11 + "and" + knotPoint22
                                + "-" + knotPoint21);
                        if (!foundShorter34) {
                            shell.buff.add("NO SHORTER PATH FOUND THAN SIMPLE CUT");

                            throw new ShorterPathNotFoundException(shell, internalCuts34, c3);
                        }
                        if (!balanced34) {
                            shell.buff.add("SEGMENTS UNBALANCED");
                            throw new SegmentBalanceException(shell, internalCuts34, c3);
                        }
                    } else {
                        shell.buff.flush();
                    }

                    shell.buff.flush();
                    shell.buff.add(" 5 -------------------------------------------");

                    CutInfo c5 = new CutInfo(shell, knotPoint11, knotPoint12, cutSegment1, external1, knotPoint22,
                            knotPoint21, cutSegment2, external2, knot, null);
                    SegmentBalanceException sbe56 = new SegmentBalanceException(shell, null, c5);
                    BalanceMap balanceMap5 = new BalanceMap(knot, sbe56);
                    balanceMap5.addCut(knotPoint11, knotPoint12);
                    balanceMap5.addCut(knotPoint21, knotPoint22);
                    balanceMap5.addExternalMatch(knotPoint11, external1);
                    balanceMap5.addExternalMatch(knotPoint22, external2);
                    c5.balanceMap = balanceMap5;

                    Knot minKnot = internalPathEngine.findMinKnot(knotPoint11, knotPoint12, external1, knotPoint22,
                            knotPoint21, external2,
                            knot, balanceMap5);
                    double d5 = Double.MAX_VALUE, d6 = Double.MAX_VALUE;
                    CutMatchList cutMatch6 = null, cutMatch5 = null;
                    Knot smallestCommonKnotPointKnot = flatKnots
                            .get(shell.smallestCommonKnotLookup[knotPoint11.id][knotPoint22.id]);
                    if (!minKnot.equals(knot) && !smallestCommonKnotPointKnot.equals(knot)) {

                        CutMatchList internalCuts5 = internalPathEngine.calculateInternalPathLength(
                                knotPoint11, knotPoint12, external1,
                                knotPoint22, knotPoint21, external2, knot, balanceMap5);
                        if (internalCuts5.delta == 0.0) {
                            throw new SegmentBalanceException(shell, internalCuts5, c5);
                        }

                        Segment s51 = knotPoint11.getClosestSegment(external1, null);
                        Segment s52 = knotPoint22.getClosestSegment(external2, s51);

                        if (!internalCuts5.checkCutMatchBalance(s51, s52, cutSegment1, new Segment[] { cutSegment2 },
                                c5, false,
                                true)) {
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

                            throw new SegmentBalanceException(shell, internalCuts5, c5);
                        }

                        SegmentBalanceException sbe5 = new SegmentBalanceException(shell, internalCuts5, c5);
                        cutMatch5 = new CutMatchList(shell, sbe5, c5.superKnot);
                        cutMatch5.addCutMatch(new Segment[] { cutSegment1, cutSegment2 },
                                new Segment[] { s52, s51 },
                                internalCuts5, c5, "CutEngine5");
                        d5 = cutMatch5.delta;

                        shell.buff.flush();
                        shell.buff.add(" 6 -------------------------------------------");
                        
                        CutInfo c6 = new CutInfo(shell, knotPoint12, knotPoint11, cutSegment1, external1, knotPoint21,
                                knotPoint22, cutSegment2, external2, knot, null);
                        SegmentBalanceException sbe6noic = new SegmentBalanceException(shell, null, c6);
                        BalanceMap balanceMap6 = new BalanceMap(knot, sbe56);
                        balanceMap6.addCut(knotPoint11, knotPoint12);
                        balanceMap6.addCut(knotPoint21, knotPoint22);
                        balanceMap6.addExternalMatch(knotPoint12, external1);
                        balanceMap6.addExternalMatch(knotPoint21, external2);
                        c6.balanceMap = balanceMap6;
                        CutMatchList internalCuts6 = internalPathEngine.calculateInternalPathLength(
                                knotPoint12, knotPoint11, external1,
                                knotPoint21, knotPoint22, external2, knot, balanceMap6);
                        Segment s61 = knotPoint12.getClosestSegment(external1, null);
                        Segment s62 = knotPoint21.getClosestSegment(external2, s61);

                        SegmentBalanceException sbe6 = new SegmentBalanceException(shell, internalCuts6, c6);
                        cutMatch6 = new CutMatchList(shell, sbe6, c6.superKnot);
                        cutMatch6.addCutMatch(new Segment[] { cutSegment1, cutSegment2 },
                                new Segment[] { s62, s61 },
                                internalCuts6, c6, "CutEngine6");
                        d6 = cutMatch6.delta;

                        shell.buff.flush();

                    }

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
                        overlapping = false;
                    }

                }
            }
        }
        return result;

    }

    MultiKeyMap<Integer, CutMatchList> cutLookup = new MultiKeyMap<>();
    double resolved = 0;
    double totalCalls = 0;

    public ArrayList<VirtualPoint> cutKnot(ArrayList<VirtualPoint> knotList)
            throws SegmentBalanceException, BalancerException {
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
                if (cutMatchList.cutMatches.size() == 0) {
                    throw cutMatchList.sbe;
                }
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
            ArrayList<VirtualPoint> knotList) throws SegmentBalanceException, BalancerException {

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