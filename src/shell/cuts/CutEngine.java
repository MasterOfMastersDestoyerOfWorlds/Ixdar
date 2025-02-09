package shell.cuts;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.collections4.map.MultiKeyMap;

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

    int cutKnotNum = 0;

    public static long clockworkStartTime, clockworkEndTime;
    public static double clockworkTotalTimeSeconds;

    public static void resetMetrics() {
        InternalPathEngine.resetMetrics();
        FlattenEngine.resetMetrics();
        ManifoldEngine.resetMetrics();
        clockworkStartTime = 0;
        clockworkEndTime = 0;
        clockworkTotalTimeSeconds = 0.0;
    }

    Shell shell;
    public FlattenEngine flattenEngine;
    public int totalLayers = -1;

    public CutEngine(Shell shell) {
        this.shell = shell;
        this.flattenEngine = new FlattenEngine(shell, this);
    }

    MultiKeyMap<Integer, CutMatchList> cutLookup = new MultiKeyMap<>();
    double resolved = 0;
    double totalCalls = 0;
    public HashMap<Long, RouteMap> routesBySegment = new HashMap<>();

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
                    flattenEngine.flattenKnots(knot, external1, external2, knotList, layerNum);
                    i = i - 1;
                    continue;
                } else {
                    shell.updateSmallestKnot(knot);
                    shell.updateSmallestCommonKnot(knot);
                    if (!flattenEngine.flatKnots.containsKey(knot.id)) {
                        flattenEngine.setFlatKnot(layerNum, knot, knot);
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
                SortedCutMatchInfo sortedCutMatchLists = ManifoldEngine.findCutMatchList(
                        knot,
                        external1, external2, shell);
                sortedCutMatchInfoLookup.put(knot.id, sortedCutMatchLists);
            }
        }


        if(RunListUtils.containsID(knotList, 65)){
            float z =0;
        }
        clockworkStartTime = System.currentTimeMillis();
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
        ///TODO 726
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

        clockworkEndTime = System.currentTimeMillis();
        clockworkTotalTimeSeconds += ((double) (clockworkEndTime - clockworkStartTime)) / 1000.0;
        return result;

    }

}