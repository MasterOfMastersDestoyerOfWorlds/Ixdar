package shell.cuts;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.commons.math3.util.Pair;

import shell.BalanceMap;
import shell.exceptions.BalancerException;
import shell.exceptions.MultipleCyclesFoundException;
import shell.exceptions.SegmentBalanceException;
import shell.exceptions.ShorterPathNotFoundException;
import shell.knot.Knot;
import shell.knot.Point;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.shell.Shell;

public class CutEngine {

    public HashMap<Integer, Knot> flatKnots = new HashMap<>();
    public HashMap<Integer, Integer> flatKnotsHeight = new HashMap<>();
    public HashMap<Integer, Integer> flatKnotsLayer = new HashMap<>();
    int cutKnotNum = 0;

    Shell shell;
    public InternalPathEngine internalPathEngine;
    public int totalLayers = -1;

    public CutEngine(Shell shell) {
        this.shell = shell;
        this.internalPathEngine = new InternalPathEngine(shell, this);
    }

    public CutMatchList findCutMatchList(Knot knot, VirtualPoint external1, VirtualPoint external2)
            throws SegmentBalanceException, BalancerException {
        double minDelta = Double.MAX_VALUE;
        CutMatchList result = null;
        ArrayList<Pair<Segment, Segment>> segmentPairs = new ArrayList<>();
        for (int a = 0; a < knot.knotPoints.size(); a++) {
            for (int b = a; b < knot.knotPoints.size(); b++) {
                VirtualPoint knotPoint11 = knot.knotPoints.get(a);
                VirtualPoint knotPoint12 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
                Segment cutSegment1 = knot.getSegment(knotPoint11, knotPoint12);

                VirtualPoint knotPoint21 = knot.knotPoints.get(b);
                VirtualPoint knotPoint22 = knot.knotPoints.get(b + 1 >= knot.knotPoints.size() ? 0 : b + 1);
                Segment cutSegment2 = knot.getSegment(knotPoint21, knotPoint22);

                
                if(knot.id == 34 && cutSegment1.hasPoints(9, 7) && cutSegment2.hasPoints(6, 4)){
                    float z = 0;
                }
                Pair<Segment, Segment> p = new Pair<Segment, Segment>(cutSegment1, cutSegment2);
                segmentPairs.add(p);
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
                    balanceMap1.addExternalMatch(knotPoint11, external1, null);
                    balanceMap1.addExternalMatch(knotPoint12, external2, null);
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
                        } else {
                            result = cutMatch2;
                        }
                        minDelta = delta;
                    }
                } else {
                    double delta = Double.MAX_VALUE;

                    Segment s11 = knotPoint11.getClosestSegment(external1, null);
                    Segment s12 = knotPoint21.getClosestSegment(external2, s11);
                    VirtualPoint externalPoint11 = s11.getOther(knotPoint11);
                    VirtualPoint externalPoint12 = s12.getOther(knotPoint21);
                    CutInfo c1 = new CutInfo(shell, knotPoint11, knotPoint12, cutSegment1, externalPoint11, knotPoint21,
                            knotPoint22, cutSegment2,
                            externalPoint12, knot, null);
                    SegmentBalanceException sbe12 = new SegmentBalanceException(shell, null, c1);
                    BalanceMap balanceMap1 = new BalanceMap(knot, sbe12);
                    balanceMap1.addCut(knotPoint11, knotPoint12);
                    balanceMap1.addCut(knotPoint21, knotPoint22);
                    balanceMap1.addExternalMatch(knotPoint11, externalPoint11, null);
                    balanceMap1.addExternalMatch(knotPoint21, externalPoint12, null);
                    c1.balanceMap = balanceMap1;
                    shell.buff.add("12 -------------------------------------------");
                    CutMatchList internalCuts12 = null;
                    try {
                        internalCuts12 = internalPathEngine.calculateInternalPathLength(
                                knotPoint11, knotPoint12, externalPoint11,
                                knotPoint21, knotPoint22, externalPoint12, knot, balanceMap1, c1, true);
                    } catch (SegmentBalanceException sbe) {

                        shell.buff.add("%complete this knot: " + 100.0 * (((double) a) * ((double) a - 1) + b)
                                / (((double) knot.knotPoints.size()) * ((double) knot.knotPoints.size())));
                        throw sbe;
                    }
                    CutMatchList cutMatch1 = new CutMatchList(shell, sbe12, c1.superKnot);
                    cutMatch1.addCutMatch(new Segment[] { cutSegment1, cutSegment2 },
                            new Segment[] { s12, s11 },
                            internalCuts12, c1, "CutEngine1");
                    double d1 = cutMatch1.delta;
                    delta = d1 < delta ? d1 : delta;

                    Segment s21 = knotPoint21.getClosestSegment(external1, null);
                    Segment s22 = knotPoint11.getClosestSegment(external2, s21);
                    VirtualPoint externalPoint21 = s21.getOther(knotPoint21);
                    VirtualPoint externalPoint22 = s22.getOther(knotPoint11);
                    CutInfo c2 = new CutInfo(shell, knotPoint21, knotPoint22, cutSegment2, externalPoint21, knotPoint11,
                            knotPoint12, cutSegment1,
                            externalPoint22, knot, null);
                    SegmentBalanceException sbe2 = new SegmentBalanceException(shell, null, c2);
                    BalanceMap balanceMap2 = new BalanceMap(knot, sbe12);
                    balanceMap2.addCut(knotPoint11, knotPoint12);
                    balanceMap2.addCut(knotPoint21, knotPoint22);
                    balanceMap2.addExternalMatch(knotPoint21, externalPoint21, null);
                    balanceMap2.addExternalMatch(knotPoint11, externalPoint22, null);
                    c2.balanceMap = balanceMap2;

                    CutMatchList cutMatch2 = new CutMatchList(shell, sbe2, c2.superKnot);
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

                    Segment s31 = knotPoint12.getClosestSegment(external1, null);
                    Segment s32 = knotPoint22.getClosestSegment(external2, s31);
                    VirtualPoint externalPoint31 = s31.getOther(knotPoint12);
                    VirtualPoint externalPoint32 = s32.getOther(knotPoint22);
                    CutInfo c3 = new CutInfo(shell, knotPoint12, knotPoint11, cutSegment1, externalPoint31, knotPoint22,
                            knotPoint21, cutSegment2,
                            externalPoint32, knot, null);
                    SegmentBalanceException sbe3 = new SegmentBalanceException(shell, null, c3);
                    BalanceMap balanceMap3 = new BalanceMap(knot, sbe3);
                    balanceMap3.addCut(knotPoint11, knotPoint12);
                    balanceMap3.addCut(knotPoint21, knotPoint22);
                    balanceMap3.addExternalMatch(knotPoint12, externalPoint31, null);
                    balanceMap3.addExternalMatch(knotPoint22, externalPoint32, null);
                    c3.balanceMap = balanceMap3;
                    CutMatchList internalCuts34 = null;
                    try {
                        internalCuts34 = internalPathEngine.calculateInternalPathLength(
                                knotPoint12, knotPoint11, externalPoint31,
                                knotPoint22, knotPoint21, externalPoint32, knot, balanceMap3, c3, true);

                    } catch (SegmentBalanceException sbe) {
                        shell.buff.add("Original Cut Info 34: " + c3);

                        shell.buff.add("%complete this knot: " + 100.0 * (((double) a) * ((double) a - 1) + b)
                                / (((double) knot.knotPoints.size()) * ((double) knot.knotPoints.size())));
                        throw sbe;
                    }

                    CutMatchList cutMatch3 = new CutMatchList(shell, sbe3, c3.superKnot);
                    cutMatch3.addCutMatch(new Segment[] { cutSegment1, cutSegment2 },
                            new Segment[] { s32, s31 },
                            internalCuts34, c3, "CutEngine3");
                    double d3 = cutMatch3.delta;
                    delta = d3 < delta ? d3 : delta;

                    Segment s41 = knotPoint22.getClosestSegment(external1, null);
                    Segment s42 = knotPoint12.getClosestSegment(external2, s41);
                    VirtualPoint externalPoint41 = s41.getOther(knotPoint22);
                    VirtualPoint externalPoint42 = s42.getOther(knotPoint12);
                    CutInfo c4 = new CutInfo(shell, knotPoint22, knotPoint21, cutSegment2, externalPoint41, knotPoint12,
                            knotPoint11, cutSegment1,
                            externalPoint42, knot, null);

                    SegmentBalanceException sbe4 = new SegmentBalanceException(shell, null, c4);
                    BalanceMap balanceMap4 = new BalanceMap(knot, sbe4);
                    balanceMap4.addCut(knotPoint11, knotPoint12);
                    balanceMap4.addCut(knotPoint21, knotPoint22);
                    balanceMap4.addExternalMatch(knotPoint22, externalPoint41, null);
                    balanceMap4.addExternalMatch(knotPoint12, externalPoint42, null);
                    c4.balanceMap = balanceMap4;

                    CutMatchList cutMatch4 = new CutMatchList(shell, sbe4, c4.superKnot);
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
                    shell.buff.add(" 56 -------------------------------------------");

                    Segment s51 = knotPoint11.getClosestSegment(external1, null);
                    Segment s52 = knotPoint22.getClosestSegment(external2, s51);
                    VirtualPoint externalPoint51 = s51.getOther(knotPoint11);
                    VirtualPoint externalPoint52 = s52.getOther(knotPoint22);

                    CutInfo c5 = new CutInfo(shell, knotPoint11, knotPoint12, cutSegment1, externalPoint51, knotPoint22,
                            knotPoint21, cutSegment2, externalPoint52, knot, null);
                    SegmentBalanceException sbe5 = new SegmentBalanceException(shell, null, c5);
                    BalanceMap balanceMap5 = new BalanceMap(knot, sbe5);
                    balanceMap5.addCut(knotPoint11, knotPoint12);
                    balanceMap5.addCut(knotPoint21, knotPoint22);

                    balanceMap5.addExternalMatch(knotPoint11, externalPoint51, null);
                    balanceMap5.addExternalMatch(knotPoint22, externalPoint52, null);
                    c5.balanceMap = balanceMap5;

                    double d5 = Double.MAX_VALUE, d7 = Double.MAX_VALUE, d6 = Double.MAX_VALUE, d8 = Double.MAX_VALUE;
                    CutMatchList cutMatch7 = null, cutMatch8 = null, cutMatch5 = null, cutMatch6 = null;
                    boolean skip = false;
                    if (!skip) {

                        CutMatchList internalCuts56 = internalPathEngine.calculateInternalPathLength(
                                knotPoint11, knotPoint12, externalPoint51,
                                knotPoint22, knotPoint21, externalPoint52, knot, balanceMap5, c5, false);
                        if (internalCuts56.delta == 0.0) {
                            throw new SegmentBalanceException(shell, internalCuts56, c5);
                        }

                        if (!internalCuts56.checkCutMatchBalance(s51, s52, cutSegment1, new Segment[] { cutSegment2 },
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

                            throw new SegmentBalanceException(shell, internalCuts56, c5);
                        }
                        cutMatch5 = new CutMatchList(shell, sbe5, c5.superKnot);
                        cutMatch5.addCutMatch(new Segment[] { cutSegment1, cutSegment2 },
                                new Segment[] { s52, s51 },
                                internalCuts56, c5, "CutEngine5");
                        d5 = cutMatch5.delta;
                        delta = d5 < delta ? d5 : delta;

                        Segment s61 = knotPoint22.getClosestSegment(external1, null);
                        Segment s62 = knotPoint11.getClosestSegment(external2, s61);
                        VirtualPoint externalPoint61 = s61.getOther(knotPoint22);
                        VirtualPoint externalPoint62 = s62.getOther(knotPoint11);
                        CutInfo c6 = new CutInfo(shell, knotPoint22, knotPoint21, cutSegment2, externalPoint61,
                                knotPoint11,
                                knotPoint12, cutSegment1,
                                externalPoint62, knot, null);

                        SegmentBalanceException sbe6 = new SegmentBalanceException(shell, null, c6);
                        BalanceMap balanceMap6 = new BalanceMap(knot, sbe6);
                        balanceMap6.addCut(knotPoint11, knotPoint12);
                        balanceMap6.addCut(knotPoint21, knotPoint22);
                        balanceMap6.addExternalMatch(knotPoint22, externalPoint61, null);
                        balanceMap6.addExternalMatch(knotPoint11, externalPoint62, null);
                        c6.balanceMap = balanceMap6;

                        cutMatch6 = new CutMatchList(shell, sbe6, c6.superKnot);
                        cutMatch6.addCutMatch(new Segment[] { cutSegment1, cutSegment2 },
                                new Segment[] { s62, s61 },
                                internalCuts56, c6, "CutEngine6");
                        d6 = cutMatch6.delta;
                        delta = d6 < delta ? d6 : delta;

                        shell.buff.flush();
                        shell.buff.add(" 78 -------------------------------------------");
                        Segment s71 = knotPoint12.getClosestSegment(external1, null);
                        Segment s72 = knotPoint21.getClosestSegment(external2, s71);
                        VirtualPoint externalPoint71 = s71.getOther(knotPoint12);
                        VirtualPoint externalPoint72 = s72.getOther(knotPoint21);
                        CutInfo c7 = new CutInfo(shell, knotPoint12, knotPoint11, cutSegment1, externalPoint71,
                                knotPoint21,
                                knotPoint22, cutSegment2, externalPoint72, knot, null);

                        SegmentBalanceException sbe7 = new SegmentBalanceException(shell, null, c7);
                        BalanceMap balanceMap7 = new BalanceMap(knot, sbe7);
                        balanceMap7.addCut(knotPoint11, knotPoint12);
                        balanceMap7.addCut(knotPoint21, knotPoint22);
                        balanceMap7.addExternalMatch(knotPoint12, externalPoint71, null);
                        balanceMap7.addExternalMatch(knotPoint21, externalPoint72, null);
                        c7.balanceMap = balanceMap7;
                        CutMatchList internalCuts78 = internalPathEngine.calculateInternalPathLength(
                                knotPoint12, knotPoint11, externalPoint71,
                                knotPoint21, knotPoint22, externalPoint72, knot, balanceMap7, c7, false);
                        cutMatch7 = new CutMatchList(shell, sbe7, c7.superKnot);
                        cutMatch7.addCutMatch(new Segment[] { cutSegment1, cutSegment2 },
                                new Segment[] { s72, s71 },
                                internalCuts78, c7, "CutEngine7");
                        d7 = cutMatch7.delta;
                        delta = d7 < delta ? d7 : delta;

                        Segment s81 = knotPoint21.getClosestSegment(external1, null);
                        Segment s82 = knotPoint12.getClosestSegment(external2, s81);
                        VirtualPoint externalPoint81 = s81.getOther(knotPoint21);
                        VirtualPoint externalPoint82 = s82.getOther(knotPoint12);
                        CutInfo c8 = new CutInfo(shell, knotPoint21, knotPoint22, cutSegment2, externalPoint81,
                                knotPoint12,
                                knotPoint11, cutSegment1,
                                externalPoint82, knot, null);

                        SegmentBalanceException sbe8 = new SegmentBalanceException(shell, null, c8);
                        BalanceMap balanceMap8 = new BalanceMap(knot, sbe8);
                        balanceMap8.addCut(knotPoint11, knotPoint12);
                        balanceMap8.addCut(knotPoint21, knotPoint22);
                        balanceMap8.addExternalMatch(knotPoint21, externalPoint81, null);
                        balanceMap8.addExternalMatch(knotPoint12, externalPoint82, null);
                        c8.balanceMap = balanceMap8;

                        cutMatch8 = new CutMatchList(shell, sbe8, c8.superKnot);
                        cutMatch8.addCutMatch(new Segment[] { cutSegment1, cutSegment2 },
                                new Segment[] { s82, s81 },
                                internalCuts78, c8, "CutEngine8");
                        d8 = cutMatch8.delta;
                        delta = d8 < delta ? d8 : delta;

                    }
                    shell.buff.flush();

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
                        } else if (delta == d7) {
                            result = cutMatch7;
                        } else if (delta == d8) {
                            result = cutMatch8;
                        }

                        minDelta = delta;
                    }

                }
            }
        }
        if(knot.id == 34){
            float z = 0;
        }
        float z = 0;
        return result;

    }

    MultiKeyMap<Integer, CutMatchList> cutLookup = new MultiKeyMap<>();
    double resolved = 0;
    double totalCalls = 0;

    public ArrayList<VirtualPoint> cutKnot(ArrayList<VirtualPoint> knotList, int layerNum)
            throws SegmentBalanceException, BalancerException {
        knotList = new ArrayList<>(knotList);
        // move on to the cutting phase
        VirtualPoint prevPoint = knotList.get(knotList.size() - 1);
        for (int i = 0; i < knotList.size(); i++) {

            VirtualPoint vp = knotList.get(i);
            shell.buff.add("Checking Point: " + vp);
            if (vp.isKnot) {

                Knot knot = (Knot) vp;
                shell.buff.add("Found Knot!" + knot.fullString());

                VirtualPoint external1 = knot.match1;
                VirtualPoint external2 = knot.match2;

                if ((external1.getHeight() > 1 || knot.getHeight() > 1 || external2.getHeight() > 1)) {
                    shell.buff.add("Need to simplify knots internally before matching : knot: " + knot
                            + " external1: " + external1 + " external2: " + external2);
                    Knot knotNew = flattenKnots(knot, external1, external2, knotList, layerNum);
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
                        flatKnotsHeight.put(knot.id, knot.getHeight());
                        flatKnotsLayer.put(knot.id, layerNum);
                    }
                }

                CutMatchList cutMatchList = findCutMatchList(knot, external1, external2);
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
                    shell.buff.add(knotList);

                    if (knotList.contains(addPoint)) {
                        shell.buff.add(finalCut);

                        throw new MultipleCyclesFoundException(shell, cutMatchList, null, null, finalCut.c);
                    }
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
            ArrayList<VirtualPoint> knotList, int layerNum) throws SegmentBalanceException, BalancerException {
            
        
        ArrayList<VirtualPoint> flattenKnots = cutKnot(knot.knotPoints, layerNum + 1);
        Knot knotNew = new Knot(flattenKnots, shell);
        knotNew.copyMatches(knot);
        flatKnots.put(knotNew.id, knotNew);
        flatKnotsHeight.put(knotNew.id, knot.getHeight());
        flatKnotsLayer.put(knotNew.id, layerNum);
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
            flattenKnotsExternal1 = cutKnot(external1Knot.knotPoints, layerNum + 1);
            external1New = new Knot(flattenKnotsExternal1, shell);
            flatKnots.put(external1New.id, external1New);
            flatKnotsHeight.put(external1New.id, external1Knot.getHeight());
            flatKnotsLayer.put(external1New.id, layerNum);
            shell.updateSmallestCommonKnot(external1New);
            external1New.copyMatches(external1);
        }
        Knot external2Knot = null;
        ArrayList<VirtualPoint> flattenKnotsExternal2 = null;
        Knot external2New = null;
        if (makeExternal2) {

            external2Knot = (Knot) external2;
            flattenKnotsExternal2 = cutKnot(external2Knot.knotPoints, layerNum + 1);
            external2New = new Knot(flattenKnotsExternal2, shell);
            external2New.copyMatches(external2);
            shell.updateSmallestCommonKnot(external2New);
            flatKnots.put(external2New.id, external2New);
            flatKnotsHeight.put(external2New.id, external2New.getHeight());
            flatKnotsLayer.put(external2New.id, layerNum);
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