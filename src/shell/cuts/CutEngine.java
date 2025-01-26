package shell.cuts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.PriorityQueue;

import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.commons.math3.util.Pair;

import shell.BalanceMap;
import shell.Toggle;
import shell.cuts.enums.RouteType;
import shell.cuts.route.Route;
import shell.cuts.route.RouteInfo;
import shell.cuts.route.RouteMap;
import shell.cuts.route.RoutePair;
import shell.exceptions.BalancerException;
import shell.exceptions.MultipleCyclesFoundException;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Knot;
import shell.knot.Point;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.shell.Shell;
import shell.utils.RunListUtils;

public class CutEngine {

    public HashMap<Integer, Knot> flatKnots = new HashMap<>();
    public HashMap<Integer, Integer> knotToFlatKnot = new HashMap<>();
    public HashMap<Integer, Integer> flatKnotToKnot = new HashMap<>();
    public HashMap<Integer, Integer> flatKnotsHeight = new HashMap<>();
    public HashMap<Integer, Integer> flatKnotsLayer = new HashMap<>();
    public HashMap<Integer, Integer> flatKnotsNumKnots = new HashMap<>();
    public ArrayList<Integer> flattenedKnots = new ArrayList<>();
    int cutKnotNum = 0;

    Shell shell;
    public InternalPathEngine internalPathEngine;
    public int totalLayers = -1;

    public CutEngine(Shell shell) {
        this.shell = shell;
        this.internalPathEngine = new InternalPathEngine(shell, this);
    }

    public static int countSkipped = 0;
    public static int countCalculated = 0;

    public ArrayList<CutMatchList> findCutMatchList(Knot knot, VirtualPoint external1, VirtualPoint external2)
            throws SegmentBalanceException, BalancerException {
        double minDelta = Double.MAX_VALUE;
        ArrayList<CutMatchList> result = new ArrayList<>();
        ArrayList<Pair<Segment, Segment>> segmentPairs = new ArrayList<>();
        for (int a = 0; a < knot.knotPoints.size(); a++) {

            VirtualPoint knotPoint11 = knot.knotPoints.get(a);
            VirtualPoint knotPoint12 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
            Segment cutSegment1 = knotPoint11.getClosestSegment(knotPoint12, null);
            Segment s11 = knotPoint11.getClosestSegment(external1, null);
            Segment s12 = knotPoint12.getClosestSegment(external2, s11);

            CutInfo c1 = new CutInfo(shell, knotPoint11, knotPoint12, cutSegment1, external1, knotPoint12, knotPoint11,
                    cutSegment1, external2, knot, null);
            SegmentBalanceException sbe = new SegmentBalanceException(shell, null, c1);
            BalanceMap balanceMap1 = new BalanceMap(knot, sbe);
            balanceMap1.addCut(knotPoint11, knotPoint12);
            balanceMap1.addExternalMatch(knotPoint11, external1, null);
            balanceMap1.addExternalMatch(knotPoint12, external2, null);
            c1.balanceMap = balanceMap1;

            CutMatchList cutMatch1 = new CutMatchList(shell, sbe, c1.superKnot);
            cutMatch1.addCutMatch(new Segment[] { cutSegment1 }, new Segment[] { s11, s12 }, c1,
                    "CutEngineSegmentsFullyOverlap1");
            double d1 = cutMatch1.delta;

            Segment s21 = knotPoint12.getClosestSegment(external1, null);
            Segment s22 = knotPoint11.getClosestSegment(external2, s21);

            CutInfo c2 = new CutInfo(shell, knotPoint12, knotPoint11, cutSegment1, external1, knotPoint11, knotPoint12,
                    cutSegment1, external2, knot, balanceMap1);

            CutMatchList cutMatch2 = new CutMatchList(shell, sbe, c2.superKnot);
            cutMatch2.addCutMatch(new Segment[] { cutSegment1 }, new Segment[] { s21, s22 }, c2,
                    "CutEngineSegmentsFullyOverlap2");
            double d2 = cutMatch2.delta;

            double delta = d2;
            if (d1 < d2) {
                delta = d1;
            }
            result.add(cutMatch1);
            result.add(cutMatch2);
            if (delta < minDelta) {
                minDelta = delta;
            }
        }
        boolean ixdarSkip = Toggle.IxdarSkip.value;
        boolean answerSharing = Toggle.IxdarMirrorAnswerSharing.value;
        boolean checkAnswer = Toggle.IxdarCheckMirroredAnswerSharing.value;
        for (int a = 0; a < knot.knotPoints.size(); a++) {
            for (int b = a; b < knot.knotPoints.size(); b++) {
                VirtualPoint knotPoint11 = knot.knotPoints.get(a);
                VirtualPoint knotPoint12 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
                Segment cutSegment1 = knotPoint11.getClosestSegment(knotPoint12, null);

                VirtualPoint knotPoint21 = knot.knotPoints.get(b);
                VirtualPoint knotPoint22 = knot.knotPoints.get(b + 1 >= knot.knotPoints.size() ? 0 : b + 1);
                Segment cutSegment2 = knotPoint21.getClosestSegment(knotPoint22, null);

                Pair<Segment, Segment> p = new Pair<Segment, Segment>(cutSegment1, cutSegment2);
                segmentPairs.add(p);
                if (cutSegment1.partialOverlaps(cutSegment2)) {
                    if (knot.size() <= 4) {
                        // provable impossible to make any series of cutmatches that will return to the
                        // singular cutpoint when the cutsegments overlap in a knot of size <= 4
                        continue;
                    }
                    // goal is to throw away any overlapped segments where kp2==cp1, kp1 == cp2, or
                    // kp1 == kp2, the remaining combination where cp1 == cp2 is the one we are
                    // interested in and is only valid for knots of size > 4.
                }
                if (cutSegment1.equals(cutSegment2)) {
                    continue;
                } else {
                    double delta = Double.MAX_VALUE;
                    double regDelta = -cutSegment1.distance - cutSegment2.distance;
                    double d1 = Double.MAX_VALUE;
                    CutMatchList cutMatch1 = null;

                    CutInfo c1 = new CutInfo(shell, knotPoint11, knotPoint12, cutSegment1, external1, knotPoint21,
                            knotPoint22, cutSegment2, external2, knot, null);
                    double mind1 = c1.lowerMatchSegment.distance + c1.upperMatchSegment.distance + regDelta;
                    Pair<CutMatchList, RouteMap<Integer, RouteInfo>> internalCuts12 = null;
                    if ((mind1 < minDelta || !ixdarSkip) && c1.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap<Integer, RouteInfo>>> temp = answerSharing(null,
                                null, c1, result, false, false, true);
                        internalCuts12 = temp.getSecond();
                        cutMatch1 = temp.getFirst();
                        d1 = cutMatch1.delta;
                        delta = d1 < delta ? d1 : delta;
                        result.add(cutMatch1);
                        countCalculated++;
                    } else {
                        countSkipped++;
                    }

                    double d2 = Double.MAX_VALUE;
                    CutMatchList cutMatch2 = null;
                    CutInfo c2 = c1.copyAndSwapExternals();
                    double mind2 = c2.lowerMatchSegment.distance + c2.upperMatchSegment.distance + regDelta;
                    if ((mind2 < minDelta || !ixdarSkip) && c2.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap<Integer, RouteInfo>>> temp = answerSharing(null,
                                internalCuts12, c2, result, false, false, true);
                        internalCuts12 = temp.getSecond();
                        cutMatch2 = temp.getFirst();
                        d2 = cutMatch2.delta;
                        delta = d2 < delta ? d2 : delta;
                        result.add(cutMatch2);
                        countCalculated++;
                    } else {
                        countSkipped++;
                    }

                    CutInfo c3 = new CutInfo(shell, knotPoint12, knotPoint11, cutSegment1, external1, knotPoint22,
                            knotPoint21, cutSegment2, external2, knot, null);
                    Pair<CutMatchList, RouteMap<Integer, RouteInfo>> internalCuts34 = null;
                    CutMatchList cutMatch3 = null;
                    double d3 = Double.MAX_VALUE;
                    double mind3 = c3.lowerMatchSegment.distance + c3.upperMatchSegment.distance + regDelta;
                    if ((mind3 < minDelta || !ixdarSkip) && c3.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap<Integer, RouteInfo>>> temp = answerSharing(null,
                                null, c3, result, false, false, true);
                        internalCuts34 = temp.getSecond();
                        cutMatch3 = temp.getFirst();
                        d3 = cutMatch3.delta;
                        delta = d3 < delta ? d3 : delta;
                        result.add(cutMatch3);
                        countCalculated++;
                    } else {
                        countSkipped++;
                    }

                    CutInfo c4 = c3.copyAndSwapExternals();
                    CutMatchList cutMatch4 = null;
                    double d4 = Double.MAX_VALUE;
                    double mind4 = c4.lowerMatchSegment.distance + c4.upperMatchSegment.distance + regDelta;
                    if ((mind4 < minDelta || !ixdarSkip) && c4.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap<Integer, RouteInfo>>> temp = answerSharing(null,
                                internalCuts34, c4, result, false, false, true);
                        internalCuts34 = temp.getSecond();
                        cutMatch4 = temp.getFirst();
                        d4 = cutMatch4.delta;
                        delta = d4 < delta ? d4 : delta;
                        result.add(cutMatch4);
                        countCalculated++;
                    } else {
                        countSkipped++;
                    }

                    CutInfo c5 = new CutInfo(shell, knotPoint11, knotPoint12, cutSegment1, external1, knotPoint22,
                            knotPoint21, cutSegment2, external2, knot, null);
                    double d5 = Double.MAX_VALUE, d7 = Double.MAX_VALUE, d6 = Double.MAX_VALUE, d8 = Double.MAX_VALUE;
                    CutMatchList cutMatch7 = null, cutMatch8 = null, cutMatch5 = null, cutMatch6 = null;
                    Pair<CutMatchList, RouteMap<Integer, RouteInfo>> internalCuts56 = null;
                    double mind5 = c5.lowerMatchSegment.distance + c5.upperMatchSegment.distance + regDelta;
                    if ((mind5 < minDelta || !ixdarSkip) && c5.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap<Integer, RouteInfo>>> temp = answerSharing(
                                internalCuts12, null, c5, result, answerSharing, checkAnswer, false);
                        internalCuts56 = temp.getSecond();
                        cutMatch5 = temp.getFirst();
                        result.add(cutMatch5);
                        d5 = cutMatch5.delta;
                        delta = d5 < delta ? d5 : delta;
                    }
                    CutInfo c6 = c5.copyAndSwapExternals();
                    double mind6 = c6.lowerMatchSegment.distance + c6.upperMatchSegment.distance + regDelta;
                    if ((mind6 < minDelta || !ixdarSkip) && c6.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap<Integer, RouteInfo>>> temp = answerSharing(
                                internalCuts12, internalCuts56, c6, result, answerSharing, checkAnswer, false);
                        internalCuts56 = temp.getSecond();
                        cutMatch6 = temp.getFirst();
                        result.add(cutMatch6);
                        d6 = cutMatch6.delta;
                        delta = d6 < delta ? d6 : delta;
                    }
                    shell.buff.flush();

                    CutInfo c7 = new CutInfo(shell, knotPoint12, knotPoint11, cutSegment1, external1, knotPoint21,
                            knotPoint22, cutSegment2, external2, knot, null);
                    double mind7 = c7.lowerMatchSegment.distance + c7.upperMatchSegment.distance + regDelta;
                    Pair<CutMatchList, RouteMap<Integer, RouteInfo>> internalCuts78 = null;
                    if ((mind7 < minDelta || !ixdarSkip) && c7.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap<Integer, RouteInfo>>> temp = answerSharing(
                                internalCuts34, null, c7, result, answerSharing, checkAnswer, false);
                        internalCuts78 = temp.getSecond();
                        cutMatch7 = temp.getFirst();
                        result.add(cutMatch7);
                        d7 = cutMatch7.delta;
                        delta = d7 < delta ? d7 : delta;
                    }
                    CutInfo c8 = c7.copyAndSwapExternals();

                    double mind8 = c8.lowerMatchSegment.distance + c8.upperMatchSegment.distance + regDelta;
                    if ((mind8 < minDelta || !ixdarSkip) && c8.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap<Integer, RouteInfo>>> temp = answerSharing(
                                internalCuts34, internalCuts78, c8, result, answerSharing, checkAnswer, false);
                        internalCuts78 = temp.getSecond();
                        cutMatch8 = temp.getFirst();
                        result.add(cutMatch8);
                        d8 = cutMatch8.delta;
                        delta = d8 < delta ? d8 : delta;
                    }
                    shell.buff.flush();

                    if (delta < minDelta) {
                        minDelta = delta;
                    }

                }
            }
        }
        Collections.sort(result, new CutMatchList.CutMatchListComparator());
        return result;

    }

    public Pair<CutMatchList, Pair<CutMatchList, RouteMap<Integer, RouteInfo>>> answerSharing(
            Pair<CutMatchList, RouteMap<Integer, RouteInfo>> cutsOld,
            Pair<CutMatchList, RouteMap<Integer, RouteInfo>> cutsNew, CutInfo c,
            ArrayList<CutMatchList> result, boolean answerSharing,
            boolean checkAnswer, boolean knotPointsConnected) throws SegmentBalanceException {
        CutMatchList cutMatch = null;
        boolean isNull = true;
        if (cutsOld != null && answerSharing) {
            RouteMap<Integer, RouteInfo> routeMap12 = cutsOld.getSecond();
            RouteInfo curr = routeMap12.get(c.upperKnotPoint.id);
            RouteType prevCutSide = RouteType.None;
            if (curr.prevC.neighbor.id == c.upperCutPoint.id) {
                prevCutSide = RouteType.prevDC;
            } else {
                prevCutSide = RouteType.nextDC;
            }

            Route route = curr.getRoute(prevCutSide);
            Route connectedRoute = curr.getRoute(prevCutSide.oppositeConnectionRoute);
            if (connectedRoute.ancestorRouteType != RouteType.None) {
                if (!connectedRoute.ancestorRouteType.isConnected) {
                    route = connectedRoute;
                }
            }
            if (route.ancestorRouteType == RouteType.None) {
                isNull = true;
            } else {
                ArrayList<Segment> cutSegments = route.cuts;
                ArrayList<Segment> matchSegments = route.matches;
                cutSegments.remove(c.lowerCutSegment);
                cutSegments.remove(c.upperCutSegment);

                CutMatchList cutMatchList = new CutMatchList(shell, c.sbe, c.knot);
                try {
                    cutMatchList.addLists(cutSegments, matchSegments, c.knot, "CutEngine5Cheating");
                } catch (SegmentBalanceException be) {
                    throw be;
                }
                cutMatch = new CutMatchList(shell, c.sbe, c.superKnot);
                try {
                    cutMatch.addCutMatch(new Segment[] { c.lowerCutSegment, c.upperCutSegment },
                            new Segment[] { c.lowerMatchSegment, c.upperMatchSegment }, cutMatchList, c, "CutEngine5");
                } catch (SegmentBalanceException be) {
                    throw be;
                }
                isNull = false;
                countSkipped++;
            }
        }
        if (isNull) {
            if (cutsNew == null) {
                cutsNew = internalPathEngine.calculateInternalPathLength(c.lowerKnotPoint, c.lowerCutPoint,
                        c.lowerExternal, c.upperKnotPoint, c.upperCutPoint, c.upperExternal, c.knot, c.balanceMap, c,
                        knotPointsConnected);
            }
            cutMatch = new CutMatchList(shell, c.sbe, c.superKnot);
            cutMatch.addCutMatch(new Segment[] { c.lowerCutSegment, c.upperCutSegment },
                    new Segment[] { c.lowerMatchSegment, c.upperMatchSegment }, cutsNew.getFirst(), c, "CutEngine5");

            countCalculated++;
        }
        if (answerSharing && checkAnswer) {
            if (cutsNew == null) {

                cutsNew = internalPathEngine.calculateInternalPathLength(c.lowerKnotPoint, c.lowerCutPoint,
                        c.lowerExternal, c.upperKnotPoint, c.upperCutPoint, c.upperExternal, c.knot, c.balanceMap, c,
                        knotPointsConnected);
                CutMatchList cutMatchCheck = new CutMatchList(shell, c.sbe, c.superKnot);
                cutMatchCheck.addCutMatch(new Segment[] { c.lowerCutSegment, c.upperCutSegment },
                        new Segment[] { c.lowerMatchSegment, c.upperMatchSegment }, cutsNew.getFirst(), c,
                        "CutEngine5");

                if (cutMatchCheck.delta != cutMatch.delta) {
                    cutMatch = cutMatchCheck;
                }
            }
        }
        result.add(cutMatch);
        return new Pair<CutMatchList, Pair<CutMatchList, RouteMap<Integer, RouteInfo>>>(cutMatch, cutsNew);

    }

    MultiKeyMap<Integer, CutMatchList> cutLookup = new MultiKeyMap<>();
    double resolved = 0;
    double totalCalls = 0;
    public HashMap<Long, RouteMap<Integer, RouteInfo>> routesBySegment = new HashMap<>();

    public ArrayList<VirtualPoint> cutKnot(ArrayList<VirtualPoint> knotList, int layerNum)
            throws SegmentBalanceException, BalancerException {
        knotList = new ArrayList<>(knotList);

        // First we need to find all of the route maps for all of the knots, this way we
        // can figure out how to cut each knot in light of its externals and how to cut
        // them. This should take N^4 in order to find the route maps for each knot and
        // (K*S)^2 (where K is the number of knots in the level and S is the maximum
        // number of segments in any of the knots manifolds) to find the correct cut
        // for each knot at the level. Each Knot only has to worry about its neighbors.
        // unclear exactly how to decide the shortest given that how you enter a
        // neighbor effects where you can exit it and therefore its delta seems like an
        // np hard problem but I'm probably stupid.
        HashMap<Integer, ArrayList<CutMatchList>> sortedCutMatchListsLookup = new HashMap<>();
        for (int i = 0; i < knotList.size(); i++) {
            VirtualPoint vp = knotList.get(i);
            if (vp.isKnot) {
                Knot knot = (Knot) vp;
                VirtualPoint external1 = knot.match1;
                VirtualPoint external2 = knot.match2;

                if ((knot.getHeight() > 1)) {
                    flattenKnots(knot, external1, external2, knotList, layerNum);
                    i = i - 1;
                    continue;
                } else {
                    shell.updateSmallestKnot(knot);
                    shell.updateSmallestCommonKnot(knot);
                    if (!flatKnots.containsKey(knot.id)) {
                        setFlatKnot(layerNum, knot, knot);
                    }
                }
            }
        }
        for (int i = 0; i < knotList.size(); i++) {

            VirtualPoint vp = knotList.get(i);
            if (vp.isKnot) {
                Knot knot = (Knot) vp;
                VirtualPoint external1 = knot.match1;
                VirtualPoint external2 = knot.match2;
                ArrayList<CutMatchList> sortedCutMatchLists = findCutMatchList(knot, external1, external2);
                sortedCutMatchListsLookup.put(knot.id, sortedCutMatchLists);
            }
        }
        // move on to the cutting phase
        VirtualPoint prevPoint = knotList.get(knotList.size() - 1);
        if (RunListUtils.containsID(knotList, 74)) {
            float z = 0;
        }
        for (int i = 0; i < knotList.size(); i++) {
            VirtualPoint vp = knotList.get(i);
            if (vp.isKnot) {
                int prevIdx = knotList.indexOf(vp) - 1;
                if (prevIdx < 0) {
                    prevIdx = knotList.size() - 1;
                }
                prevPoint = knotList.get(prevIdx);
                ArrayList<CutMatchList> sortedCutMatchLists = sortedCutMatchListsLookup.get(vp.id);
                if (sortedCutMatchLists == null) {
                    float z = 0;
                }
                CutMatchList cutMatchList = sortedCutMatchLists.get(0);
                Knot knot = (Knot) vp;
                VirtualPoint external1 = knot.match1;
                VirtualPoint external2 = knot.match2;
                external1.reset(knot);
                external2.reset(knot);
                knot.reset();
                ArrayList<CutMatch> cutMatches = cutMatchList.cutMatches;            
                if(vp.id == 60){
                    float z =0;
                }
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
                        } else if (!vp.contains(pMatch1)) {
                            if (cutMatch.c.external1.contains(pMatch1)) {
                                if (cutMatch.c.external1.id == external1.id) {
                                    match1 = external1;
                                    pMatch1 = (Point) external1;
                                } else {
                                    match1 = external2;
                                    pMatch1 = (Point) external2;
                                }
                            } else if (cutMatch.c.external2.contains(pMatch1)) {
                                if (cutMatch.c.external2.id == external2.id) {
                                    match1 = external1;
                                    pMatch1 = (Point) external1;
                                } else {
                                    match1 = external2;
                                    pMatch1 = (Point) external2;
                                }
                            } else {
                                float z = 1 / 0;
                            }
                        }
                        Point pMatch2 = (Point) matchSegment.last;
                        VirtualPoint match2 = pMatch2;
                        if (external1.contains(pMatch2)) {
                            match2 = external1;
                        } else if (external2.contains(pMatch2)) {
                            match2 = external2;
                        } else if (!vp.contains(pMatch2)) {
                            if (cutMatch.c.external1.contains(pMatch2)) {
                                if (cutMatch.c.external1.id == external1.id) {
                                    match2 = external1;
                                    pMatch2 = (Point) external1;
                                } else {
                                    match2= external2;
                                    pMatch2 = (Point) external2;
                                }
                            } else if (cutMatch.c.external2.contains(pMatch2)) {
                                if (cutMatch.c.external2.id == external2.id) {
                                    match2 = external1;
                                    pMatch2 = (Point) external1;
                                } else {
                                    match2 = external2;
                                    pMatch2 = (Point) external2;
                                }
                            } else {
                                float z = 1 / 0;
                            }
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

                    if (!vp.contains(addPoint)) {
                        throw new MultipleCyclesFoundException(shell, cutMatchList, null, null, finalCut.c);
                    }
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
            } else {
                prevPoint = vp;
            }

            i = i + vp.size() - 1;
        }
        return knotList;
    }

    public void setFlatKnot(int layerNum, Knot flatKnot, Knot k) {
        flatKnots.put(flatKnot.id, flatKnot);
        flatKnotsHeight.put(flatKnot.id, k.getHeight());
        flatKnotsLayer.put(flatKnot.id, layerNum);
        flatKnotsNumKnots.put(flatKnot.id, k.numKnots);
        knotToFlatKnot.put(flatKnot.id, flatKnot.id);
        knotToFlatKnot.put(k.id, flatKnot.id);
        flatKnotToKnot.put(flatKnot.id, k.id);
    }

    public Knot flattenKnots(Knot knot, VirtualPoint external1, VirtualPoint external2,
            ArrayList<VirtualPoint> knotList, int layerNum) throws SegmentBalanceException, BalancerException {

        ArrayList<VirtualPoint> flattenKnots = cutKnot(knot.knotPoints, layerNum + 1);
        Knot knotNew = new Knot(flattenKnots, shell);
        knotNew.copyMatches(knot);
        if (!flattenedKnots.contains(knot.id) && !flatKnots.containsKey(knot.id)) {
            setFlatKnot(layerNum, knotNew, knot);
            flattenedKnots.add(knot.id);
            shell.updateSmallestCommonKnot(knotNew);
            shell.buff.add(flatKnots);
        }
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

            if (!flattenedKnots.contains(external1Knot.id)) {
                setFlatKnot(layerNum, external1New, external1Knot);
                shell.updateSmallestCommonKnot(external1New);
                external1New.copyMatches(external1);
                flattenedKnots.add(external1Knot.id);
            }
        }
        Knot external2Knot = null;
        ArrayList<VirtualPoint> flattenKnotsExternal2 = null;
        Knot external2New = null;
        if (makeExternal2) {

            external2Knot = (Knot) external2;
            flattenKnotsExternal2 = cutKnot(external2Knot.knotPoints, layerNum + 1);
            external2New = new Knot(flattenKnotsExternal2, shell);
            external2New.copyMatches(external2);
            if (!flattenedKnots.contains(external2Knot.id)) {
                setFlatKnot(layerNum, external2New, external2Knot);
                shell.updateSmallestCommonKnot(external2New);
                flattenedKnots.add(external2Knot.id);
            }
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

        return knotNew;
    }

}