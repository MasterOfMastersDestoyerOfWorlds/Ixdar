package shell.cuts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.commons.math3.util.Pair;

import shell.BalanceMap;
import shell.Toggle;
import shell.cuts.enums.RouteType;
import shell.cuts.route.Route;
import shell.cuts.route.RouteInfo;
import shell.cuts.route.RouteMap;
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

    public SortedCutMatchInfo findCutMatchList(Knot knot,
            VirtualPoint external1, VirtualPoint external2)
            throws SegmentBalanceException, BalancerException {
        double minDelta = Double.MAX_VALUE;
        SortedCutMatchInfo sortedCutMatchInfo = new SortedCutMatchInfo();
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
            sortedCutMatchInfo.add(cutMatch1, cutSegment1, knotPoint11);
            sortedCutMatchInfo.add(cutMatch2, cutSegment1, knotPoint12);
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
                                null, c1, sortedCutMatchInfo, false, false, true);
                        internalCuts12 = temp.getSecond();
                        cutMatch1 = temp.getFirst();
                        d1 = cutMatch1.delta;
                        delta = d1 < delta ? d1 : delta;
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
                                internalCuts12, c2, sortedCutMatchInfo, false, false, true);
                        internalCuts12 = temp.getSecond();
                        cutMatch2 = temp.getFirst();
                        d2 = cutMatch2.delta;
                        delta = d2 < delta ? d2 : delta;
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
                                null, c3, sortedCutMatchInfo, false, false, true);
                        internalCuts34 = temp.getSecond();
                        cutMatch3 = temp.getFirst();
                        d3 = cutMatch3.delta;
                        delta = d3 < delta ? d3 : delta;
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
                                internalCuts34, c4, sortedCutMatchInfo, false, false, true);
                        internalCuts34 = temp.getSecond();
                        cutMatch4 = temp.getFirst();
                        d4 = cutMatch4.delta;
                        delta = d4 < delta ? d4 : delta;
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
                                internalCuts12, null, c5, sortedCutMatchInfo, answerSharing, checkAnswer, false);
                        internalCuts56 = temp.getSecond();
                        cutMatch5 = temp.getFirst();
                        d5 = cutMatch5.delta;
                        delta = d5 < delta ? d5 : delta;
                    }
                    CutInfo c6 = c5.copyAndSwapExternals();
                    double mind6 = c6.lowerMatchSegment.distance + c6.upperMatchSegment.distance + regDelta;
                    if ((mind6 < minDelta || !ixdarSkip) && c6.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap<Integer, RouteInfo>>> temp = answerSharing(
                                internalCuts12, internalCuts56, c6, sortedCutMatchInfo, answerSharing,
                                checkAnswer, false);
                        internalCuts56 = temp.getSecond();
                        cutMatch6 = temp.getFirst();
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
                                internalCuts34, null, c7, sortedCutMatchInfo, answerSharing, checkAnswer, false);
                        internalCuts78 = temp.getSecond();
                        cutMatch7 = temp.getFirst();
                        d7 = cutMatch7.delta;
                        delta = d7 < delta ? d7 : delta;
                    }
                    CutInfo c8 = c7.copyAndSwapExternals();

                    double mind8 = c8.lowerMatchSegment.distance + c8.upperMatchSegment.distance + regDelta;
                    if ((mind8 < minDelta || !ixdarSkip) && c8.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap<Integer, RouteInfo>>> temp = answerSharing(
                                internalCuts34, internalCuts78, c8, sortedCutMatchInfo, answerSharing,
                                checkAnswer, false);
                        internalCuts78 = temp.getSecond();
                        cutMatch8 = temp.getFirst();
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
        sortedCutMatchInfo.sort();
        return sortedCutMatchInfo;

    }
    // Would like to 
    public Pair<CutMatchList, Pair<CutMatchList, RouteMap<Integer, RouteInfo>>> answerSharing(
            Pair<CutMatchList, RouteMap<Integer, RouteInfo>> cutsOld,
            Pair<CutMatchList, RouteMap<Integer, RouteInfo>> cutsNew, 
            CutInfo c, SortedCutMatchInfo sortedCutMatchInfo,
            boolean answerSharing, boolean checkAnswer, boolean knotPointsConnected)
            throws SegmentBalanceException {

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
        sortedCutMatchInfo.add(cutMatch, c.lowerCutSegment, c.lowerKnotPoint);
        sortedCutMatchInfo.add(cutMatch, c.upperCutSegment, c.upperKnotPoint);
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
        HashMap<Integer, SortedCutMatchInfo> sortedCutMatchInfoLookup = new HashMap<>();
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
                SortedCutMatchInfo sortedCutMatchLists = findCutMatchList(
                        knot,
                        external1, external2);
                sortedCutMatchInfoLookup.put(knot.id, sortedCutMatchLists);
            }
        }
        // Planning phase now that we have all of the cut match lists for every knot we
        // need to align which cut match list is used for what knot. We do this in the
        // following way:
        // We take our all of our knots and assign it's lowest delta cut match with the
        // two external match points we expect to match to which cut.
        // Then when we go to our neighbor and we can only change the neighbors cut that
        // is assigned to us and only if the total delta goes down. we repeat for all
        // neighbors. to achieve this it would be prudent to be able to look up cut
        // matches by what their ending cuts are this way we can get a list of all of
        // the ways we can change rotationally.
        if (RunListUtils.containsID(knotList, 80)) {
            float z = 0;
        }
        HashMap<Integer, Clockwork> clockwork = new HashMap<>();
        int size = knotList.size();
        for (int i = 0; i < size; i++) {
            VirtualPoint prev = knotList.get(Math.floorMod(i - 1, size));
            VirtualPoint vp = knotList.get(i);
            VirtualPoint next = knotList.get(Math.floorMod(i + 1, size));
            if (vp.isKnot) {
                if (!clockwork.containsKey(vp.id)) {
                    CutMatchList currCML = sortedCutMatchInfoLookup.get(vp.id).sortedCutMatchLists.get(0);
                    Clockwork cw = new Clockwork(this, currCML, next, prev);
                    clockwork.put(vp.id, cw);
                }
            }
        }
        if (RunListUtils.containsID(knotList, 32)) {
            float z = 0;
        }
        for (int i = 0; i < size; i++) {
            VirtualPoint prev = knotList.get(Math.floorMod(i - 1, size));
            VirtualPoint vp = knotList.get(i);
            VirtualPoint next = knotList.get(Math.floorMod(i + 1, size));
            if (vp.isKnot) {
                Clockwork cw = clockwork.get(vp.id);
                Clockwork prevCw = null;
                if (prev.isKnot) {
                    prevCw = clockwork.get(prev.id);
                    cw.prevExternal = prevCw.nextKnotPoint;
                    cw.prevClockwork = prevCw;
                } else {
                    cw.prevExternal = prev;
                }
                cw.prevExternalSegment = cw.prevKnotPoint.getClosestSegment(cw.prevExternal, null);
                Clockwork nextCw = null;
                if (next.isKnot) {
                    nextCw = clockwork.get(next.id);
                    cw.nextExternal = nextCw.prevKnotPoint;
                    cw.nextClockwork = nextCw;
                } else {
                    cw.nextExternal = next;
                }
                cw.nextExternalSegment = cw.nextKnotPoint.getClosestSegment(cw.nextExternal, null);
            }
        }
        if (RunListUtils.containsID(knotList, 32)) {
            float z = 0;
        }
        for (int i = 0; i < size; i++) {

            VirtualPoint prev = knotList.get(Math.floorMod(i - 1, size));
            VirtualPoint vp = knotList.get(i);
            VirtualPoint next = knotList.get(Math.floorMod(i + 1, size));
            if (vp.isKnot && prev.isKnot && next.isKnot) {

                Clockwork prevCw = clockwork.get(prev.id);
                Clockwork nextCw = clockwork.get(next.id);
                Clockwork cw = clockwork.get(vp.id);
                Segment sCurrPrev = cw.nextKnotPoint.getSegment(cw.nextExternal);
                Segment sOtherPrev = cw.nextKnotPoint.getSegment(cw.prevExternal);
                Segment sCurrNext = cw.prevKnotPoint.getSegment(cw.prevExternal);
                Segment sOtherNext = cw.prevKnotPoint.getSegment(cw.nextExternal);
                if (sCurrNext.distance + sCurrPrev.distance > sOtherPrev.distance + sOtherNext.distance) {
                    cw.swapExternals();
                    cw.flip();
                    prevCw.swapExternals(nextCw);
                }
            } else if (vp.isKnot && !prev.isKnot && !next.isKnot) {
                Clockwork cw = clockwork.get(vp.id);
                Segment sCurrPrev = cw.nextKnotPoint.getSegment(cw.nextExternal);
                Segment sOtherPrev = cw.nextKnotPoint.getSegment(cw.prevExternal);
                Segment sCurrNext = cw.prevKnotPoint.getSegment(cw.prevExternal);
                Segment sOtherNext = cw.prevKnotPoint.getSegment(cw.nextExternal);
                if (sCurrNext.distance + sCurrPrev.distance > sOtherPrev.distance + sOtherNext.distance) {
                    cw.swapExternals();
                    cw.flip();
                }
            }
        }
        // Now that we have everything initialized we need to search each knot for its
        // best set of cuts. First we find what the current cost of connecting the
        // neighbor knot is with the current knot where C(k) = the cost of the
        // segment connecting the k to its neighbor + the internal delta of the current
        // knot + the internal delta of neighbor knot. This will be our minimum bar to
        // change which cut we are using.

        // The method searching is as follows, go find the knot point of the neighbors
        // other match, then get all of the cut match lists that use that knot point in
        // one of their cuts. lookup the same information for the current knot's
        // knot point that goes to the other neighbor. search both of these lists in a
        // double for loop and look if we can improve the cost function

        // this is a good start but rather do both neighbors at once so that we are not
        // trapped by the other neighbor

        if (RunListUtils.containsID(knotList, 40)) {
            float z = 0;
        }
        for (Clockwork cw : clockwork.values()) {
            if (cw.nextClockwork != null) {
                Clockwork nextCw = cw.nextClockwork;
                boolean found = false;
                CutMatchList minCurrent = cw.cml;
                CutMatchList minNeighbor = nextCw.cml;
                VirtualPoint minCurrentVp = cw.nextKnotPoint;
                VirtualPoint minCurrentOtherVp = cw.prevKnotPoint;
                VirtualPoint minNeighborVp = nextCw.prevKnotPoint;
                double minCost = Clockwork.cost(minCurrent, minCurrentVp, minNeighbor, minNeighborVp,
                        cw.prevExternalSegment);
                ArrayList<CutMatchList> neighborCmls = sortedCutMatchInfoLookup
                        .get(nextCw.c.knot.id).sortedCutMatchListsByKnotPoint.get(nextCw.nextKnotPoint.id);
                ArrayList<CutMatchList> currentCmls = sortedCutMatchInfoLookup
                        .get(cw.c.knot.id).sortedCutMatchLists;
                if (currentCmls == null) {
                    float z = 0;
                }
                for (CutMatchList current : currentCmls) {
                    for (CutMatchList neighbor : neighborCmls) {
                        VirtualPoint neighborVp = neighbor.getOtherKp(nextCw.nextKnotPoint);
                        VirtualPoint currentVp = current.getClosestKnotPoint(neighborVp, cw.prevExternal);
                        VirtualPoint currentOtherVp = current.getOtherKp(currentVp);

                        Segment externalPrev = currentOtherVp.getSegment(cw.prevExternal);
                        double cost = Clockwork.cost(current, currentVp, neighbor, neighborVp, externalPrev);
                        if (cost < minCost) {
                            minCost = cost;
                            minCurrent = current;
                            minNeighbor = neighbor;
                            minCurrentOtherVp = currentOtherVp;
                            minCurrentVp = currentVp;
                            minNeighborVp = neighborVp;
                            found = true;
                        }
                    }
                }
                if (found) {
                    cw.setNextCwUpdateCML(nextCw, minCurrent, minNeighbor, minCurrentVp, minCurrentOtherVp,
                            minNeighborVp);
                }
            }
            if (cw.prevClockwork != null) {
                Clockwork prevCw = cw.prevClockwork;
                boolean found = false;
                CutMatchList minCurrent = cw.cml;
                CutMatchList minNeighbor = prevCw.cml;
                VirtualPoint minCurrentVp = cw.prevKnotPoint;
                VirtualPoint minCurrentOtherVp = cw.nextKnotPoint;
                VirtualPoint minNeighborVp = prevCw.nextKnotPoint;
                double minCost = Clockwork.cost(minCurrent, minCurrentVp, minNeighbor, minNeighborVp,
                        cw.nextExternalSegment);
                ArrayList<CutMatchList> neighborCmls = sortedCutMatchInfoLookup
                        .get(prevCw.c.knot.id).sortedCutMatchListsByKnotPoint.get(prevCw.prevKnotPoint.id);
                ArrayList<CutMatchList> currentCmls = sortedCutMatchInfoLookup
                        .get(cw.c.knot.id).sortedCutMatchLists;
                if (currentCmls == null) {
                    float z = 0;
                }
                for (CutMatchList current : currentCmls) {
                    for (CutMatchList neighbor : neighborCmls) {
                        VirtualPoint neighborVp = neighbor.getOtherKp(prevCw.prevKnotPoint);
                        VirtualPoint currentVp = current.getClosestKnotPoint(neighborVp, cw.nextExternal);
                        VirtualPoint currentOtherVp = current.getOtherKp(currentVp);

                        Segment externalNext = currentOtherVp.getSegment(cw.nextExternal);
                        double cost = Clockwork.cost(current, currentVp, neighbor, neighborVp, externalNext);
                        if (cost < minCost) {
                            minCost = cost;
                            minCurrent = current;
                            minNeighbor = neighbor;
                            minCurrentOtherVp = currentOtherVp;
                            minCurrentVp = currentVp;
                            minNeighborVp = neighborVp;
                            found = true;
                        }
                    }
                }
                if (found) {
                    cw.setPrevCwUpdateCML(prevCw, minCurrent, minNeighbor, minCurrentVp, minCurrentOtherVp,
                            minNeighborVp);
                }
            }
        }

        if (RunListUtils.containsID(knotList, 17)) {
            float z = 0;
        }
        // cutting phase
        int flattenedSize = 0;
        for (int i = 0; i < size; i++) {
            VirtualPoint vp = knotList.get(i);
            flattenedSize += vp.size();
            if (vp.isKnot) {

                Knot knot = (Knot) vp;
                // External cutmatches
                Clockwork cw = clockwork.get(vp.id);
                cw.nextKnotPoint.reset(cw.nextCut);
                if (cw.nextKnotPoint.match2 == null) {
                    cw.nextKnotPoint.setMatch2(cw.nextExternal, (Point) cw.nextExternal, (Point) cw.nextKnotPoint,
                            cw.nextExternalSegment);
                }
                Clockwork nextCw = cw.nextClockwork;
                if (nextCw != null) {
                    nextCw.prevKnotPoint.reset(nextCw.prevCut);
                    if (nextCw.prevKnotPoint.match2 == null) {
                        nextCw.prevKnotPoint.setMatch2(nextCw.prevExternal, (Point) nextCw.prevExternal,
                                (Point) nextCw.prevKnotPoint, nextCw.prevExternalSegment);
                    }
                } else {
                    cw.nextExternal.reset(knot);
                    cw.nextExternal.setMatch2(cw.nextKnotPoint);
                }
                cw.prevKnotPoint.reset(cw.prevCut);
                if (cw.prevKnotPoint.match2 == null) {
                    cw.prevKnotPoint.setMatch2(cw.prevExternal, (Point) cw.prevExternal, (Point) cw.prevKnotPoint,
                            cw.prevExternalSegment);
                }
                Clockwork prevCw = cw.prevClockwork;
                if (prevCw != null) {
                    prevCw.nextKnotPoint.reset(prevCw.nextCut);
                    if (prevCw.nextKnotPoint.match2 == null) {
                        prevCw.nextKnotPoint.setMatch2(prevCw.nextExternal, (Point) prevCw.nextExternal,
                                (Point) prevCw.nextKnotPoint, prevCw.nextExternalSegment);
                    }
                } else {
                    cw.prevExternal.reset(knot);
                    cw.prevExternal.setMatch2(cw.prevKnotPoint);
                }
                // Internal cutmatches
                ArrayList<CutMatch> cutMatches = cw.cml.cutMatches;
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

                        Point pMatch2 = (Point) matchSegment.last;
                        VirtualPoint match2 = pMatch2;
                        if (knot.contains(match1) && knot.contains(match2)) {
                            if (!match1.hasMatch(match2, pMatch2, pMatch1, matchSegment)) {
                                match1.setMatch2(match2, pMatch2, pMatch1, matchSegment);
                            }
                            if (!match2.hasMatch(match1, pMatch1, pMatch2, matchSegment)) {
                                match2.setMatch2(match1, pMatch1, pMatch2, matchSegment);
                            }
                        }
                    }
                }
            }
        }
        // remake the knotlist form the point level
        if (RunListUtils.containsID(knotList, 38)) {
            float z = 0;
        }
        ArrayList<VirtualPoint> result = new ArrayList<>();
        VirtualPoint vp = knotList.get(0);
        if (vp.isKnot) {
            vp = vp.knotPointsFlattened.get(0);
        }
        VirtualPoint addPoint = vp;
        VirtualPoint prevPoint = addPoint.match1;
        for (int j = 0; j < flattenedSize; j++) {
            if (addPoint == null) {
                Clockwork cw = clockwork.get(vp.topKnot.id);
                throw new MultipleCyclesFoundException(cw.cml.sbe);
            }
            result.add(addPoint);
            if (prevPoint.equals(addPoint.match2)) {
                prevPoint = addPoint;
                addPoint = addPoint.match1;
            } else {
                prevPoint = addPoint;
                addPoint = addPoint.match2;
            }
        }

        return result;

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

    class SortedCutMatchInfo {
        ArrayList<CutMatchList> sortedCutMatchLists;
        HashMap<Long, ArrayList<CutMatchList>> sortedCutMatchListsBySegment;
        HashMap<Integer, ArrayList<CutMatchList>> sortedCutMatchListsByKnotPoint;

        public SortedCutMatchInfo() {
            sortedCutMatchLists = new ArrayList<>();
            sortedCutMatchListsBySegment = new HashMap<>();
            sortedCutMatchListsByKnotPoint = new HashMap<>();
        }

        public void add(CutMatchList cutMatch, Segment cutSegment, VirtualPoint knotPoint) {
            sortedCutMatchLists.add(cutMatch);
            ArrayList<CutMatchList> segmentList = sortedCutMatchListsBySegment.getOrDefault(cutSegment.id,
                    new ArrayList<>());
            segmentList.add(cutMatch);
            sortedCutMatchListsBySegment.put(cutSegment.id, segmentList);

            ArrayList<CutMatchList> knotPointList = sortedCutMatchListsByKnotPoint.getOrDefault(knotPoint.id,
                    new ArrayList<>());
            knotPointList.add(cutMatch);
            sortedCutMatchListsByKnotPoint.put(knotPoint.id, knotPointList);
        }

        public void sort() {
            Collections.sort(sortedCutMatchLists, new CutMatchList.CutMatchListComparator());
            for (ArrayList<CutMatchList> lst : sortedCutMatchListsBySegment.values()) {
                Collections.sort(lst, new CutMatchList.CutMatchListComparator());
            }
            for (ArrayList<CutMatchList> lst : sortedCutMatchListsByKnotPoint.values()) {
                Collections.sort(lst, new CutMatchList.CutMatchListComparator());
            }
        }
    }

}