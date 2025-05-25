package shell.cuts.engines;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.collections4.map.MultiKeyMap;

import shell.cuts.Clockwork;
import shell.cuts.CutMatch;
import shell.cuts.CutMatchList;
import shell.cuts.SortedCutMatchInfo;
import shell.cuts.route.RouteMap;
import shell.exceptions.BalancerException;
import shell.exceptions.MultipleCyclesFoundException;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.shell.Shell;

public class CutEngine {

    int cutKnotNum = 0;

    public static double clockworkTotalTimeSeconds;
    public static double reeTotalTimeSeconds;

    public static void resetMetrics() {
        InternalPathEngine.resetMetrics();
        FlattenEngine.resetMetrics();
        ManifoldEngine.resetMetrics();
        clockworkTotalTimeSeconds = 0.0;
        reeTotalTimeSeconds = 0.0;
    }

    public Shell shell;
    public FlattenEngine flattenEngine;
    public int totalLayers = -1;
    public HashMap<Integer, HashMap<Integer, SortedCutMatchInfo>> cutMatchInfoByLayer;
    public HashMap<Integer, SortedCutMatchInfo> sortedCutMatchInfoLookup;

    public CutEngine(Shell shell) {
        this.shell = shell;
        this.flattenEngine = new FlattenEngine(shell, this);
        cutMatchInfoByLayer = new HashMap<>();
        sortedCutMatchInfoLookup = new HashMap<>();
    }

    MultiKeyMap<Integer, CutMatchList> cutLookup = new MultiKeyMap<>();
    double resolved = 0;
    double totalCalls = 0;
    public HashMap<Long, RouteMap> routesBySegment = new HashMap<>();
    public HashMap<Integer, Clockwork> clockwork = new HashMap<>();

    public ArrayList<Knot> cutKnot(ArrayList<Knot> knotList, int layerNum)
            throws SegmentBalanceException, BalancerException {
        knotList = new ArrayList<>(knotList);

        // First we need to find all of the route maps for all of the knots, this way we
        // can figure out how to cut each knot in light of its externals and how to cut
        // them. This should take N^4 in order to find the route maps for each knot and
        // (K*S)^2 (where K is the number of knots in the level and S is the maximum
        // number of segments in any of the knots manifolds) to find the correct cut
        // for each knot at the level. Each Knot only has to worry about its neighbors.
        long reeStartTime = System.currentTimeMillis();
        cutMatchInfoByLayer.put(layerNum, sortedCutMatchInfoLookup);
        for (int i = 0; i < knotList.size(); i++) {
            Knot vp = knotList.get(i);
            if (!vp.isSingleton()) {
                Knot knot = (Knot) vp;
                Knot external1 = knot.match1;
                Knot external2 = knot.match2;

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

            Knot vp = knotList.get(i);
            if (!vp.isSingleton()) {
                Knot knot = (Knot) vp;
                Knot external1 = knot.match1;
                Knot external2 = knot.match2;
                SortedCutMatchInfo sortedCutMatchLists = ManifoldEngine.findCutMatchList(
                        knot,
                        external1, external2, shell);
                sortedCutMatchInfoLookup.put(knot.id, sortedCutMatchLists);
            }
        }
        long reeEndTime = System.currentTimeMillis();
        reeTotalTimeSeconds = ((double) (reeEndTime - reeStartTime)) / 1000.0;
        long clockworkStartTime = System.currentTimeMillis();
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
        int size = knotList.size();
        for (int i = 0; i < size; i++) {
            Knot prev = knotList.get(Math.floorMod(i - 1, size));
            Knot vp = knotList.get(i);
            Knot next = knotList.get(Math.floorMod(i + 1, size));
            if (!vp.isSingleton()) {
                if (!clockwork.containsKey(vp.id)) {
                    SortedCutMatchInfo scmi = sortedCutMatchInfoLookup.get(vp.id);
                    CutMatchList currCML = scmi.sortedCutMatchLists.get(0);
                    Clockwork cw = new Clockwork(this, currCML, next, prev);
                    scmi.cw = cw;
                    clockwork.put(vp.id, cw);
                }
            }
        }
        for (int i = 0; i < size; i++) {
            Knot prev = knotList.get(Math.floorMod(i - 1, size));
            Knot vp = knotList.get(i);
            Knot next = knotList.get(Math.floorMod(i + 1, size));
            if (!vp.isSingleton()) {
                Clockwork cw = clockwork.get(vp.id);
                Clockwork prevCw = null;
                if (!prev.isSingleton()) {
                    prevCw = clockwork.get(prev.id);
                    cw.prevExternal = prevCw.nextKnotPoint;
                    cw.prevClockwork = prevCw;
                } else {
                    cw.prevExternal = prev;
                }
                cw.prevExternalSegment = cw.prevKnotPoint.getClosestSegment(cw.prevExternal, null);
                Clockwork nextCw = null;
                if (!next.isSingleton()) {
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

            Knot prev = knotList.get(Math.floorMod(i - 1, size));
            Knot vp = knotList.get(i);
            Knot next = knotList.get(Math.floorMod(i + 1, size));
            if (!vp.isSingleton() && !prev.isSingleton() && !next.isSingleton()) {

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
            } else if (!vp.isSingleton() && !!prev.isSingleton() && !!next.isSingleton()) {
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
                Knot minCurrentVp = cw.nextKnotPoint;
                Knot minCurrentOtherVp = cw.prevKnotPoint;
                Knot minNeighborVp = nextCw.prevKnotPoint;
                double minCost = Clockwork.cost(minCurrent, minCurrentVp, minNeighbor, minNeighborVp,
                        cw.prevExternalSegment);
                ArrayList<CutMatchList> neighborCmls = sortedCutMatchInfoLookup
                        .get(nextCw.c.knot.id).sortedCutMatchListsByKnotPoint.get(nextCw.nextKnotPoint.id);
                ArrayList<CutMatchList> currentCmls = sortedCutMatchInfoLookup
                        .get(cw.c.knot.id).sortedCutMatchLists;
                for (CutMatchList current : currentCmls) {
                    for (CutMatchList neighbor : neighborCmls) {
                        Knot neighborVp = neighbor.getOtherKp(nextCw.nextKnotPoint);
                        Knot currentVp = current.getClosestKnotPoint(neighborVp, cw.prevExternal);
                        Knot currentOtherVp = current.getOtherKp(currentVp);

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
                Knot minCurrentVp = cw.prevKnotPoint;
                Knot minCurrentOtherVp = cw.nextKnotPoint;
                Knot minNeighborVp = prevCw.nextKnotPoint;
                double minCost = Clockwork.cost(minCurrent, minCurrentVp, minNeighbor, minNeighborVp,
                        cw.nextExternalSegment);
                ArrayList<CutMatchList> neighborCmls = sortedCutMatchInfoLookup
                        .get(prevCw.c.knot.id).sortedCutMatchListsByKnotPoint.get(prevCw.prevKnotPoint.id);
                ArrayList<CutMatchList> currentCmls = sortedCutMatchInfoLookup
                        .get(cw.c.knot.id).sortedCutMatchLists;
                for (CutMatchList current : currentCmls) {
                    for (CutMatchList neighbor : neighborCmls) {
                        Knot neighborVp = neighbor.getOtherKp(prevCw.prevKnotPoint);
                        Knot currentVp = current.getClosestKnotPoint(neighborVp, cw.nextExternal);
                        Knot currentOtherVp = current.getOtherKp(currentVp);

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
            Knot vp = knotList.get(i);
            flattenedSize += vp.size();
            if (!vp.isSingleton()) {

                Knot knot = (Knot) vp;
                // External cutmatches
                Clockwork cw = clockwork.get(vp.id);
                cw.nextKnotPoint.reset(cw.nextCut);
                if (cw.nextKnotPoint.match2 == null) {
                    cw.nextKnotPoint.setMatch2(cw.nextExternal, cw.nextExternal, cw.nextKnotPoint,
                            cw.nextExternalSegment);
                }
                Clockwork nextCw = cw.nextClockwork;
                if (nextCw != null) {
                    nextCw.prevKnotPoint.reset(nextCw.prevCut);
                    if (nextCw.prevKnotPoint.match2 == null) {
                        nextCw.prevKnotPoint.setMatch2(nextCw.prevExternal, nextCw.prevExternal,
                                nextCw.prevKnotPoint, nextCw.prevExternalSegment);
                    }
                } else {
                    cw.nextExternal.reset(knot);
                    cw.nextExternal.setMatch2(cw.nextKnotPoint);
                }
                cw.prevKnotPoint.reset(cw.prevCut);
                if (cw.prevKnotPoint.match2 == null) {
                    cw.prevKnotPoint.setMatch2(cw.prevExternal, cw.prevExternal, cw.prevKnotPoint,
                            cw.prevExternalSegment);
                }
                Clockwork prevCw = cw.prevClockwork;
                if (prevCw != null) {
                    prevCw.nextKnotPoint.reset(prevCw.nextCut);
                    if (prevCw.nextKnotPoint.match2 == null) {
                        prevCw.nextKnotPoint.setMatch2(prevCw.nextExternal, prevCw.nextExternal,
                                prevCw.nextKnotPoint, prevCw.nextExternalSegment);
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

                        Knot pcut1 = cutSegment.first;
                        Knot pcut2 = cutSegment.last;
                        pcut1.reset(pcut2);
                        pcut2.reset(pcut1);
                    }
                    for (Segment matchSegment : cutMatch.matchSegments) {

                        Knot pMatch1 = matchSegment.first;
                        Knot match1 = pMatch1;

                        Knot pMatch2 = matchSegment.last;
                        Knot match2 = pMatch2;
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
        ArrayList<Knot> result = new ArrayList<>();
        Knot vp = knotList.get(0);
        if (!vp.isSingleton()) {
            vp = vp.knotPointsFlattened.get(0);
        }
        Knot addPoint = vp;
        Knot prevPoint = addPoint.match1;
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

        long clockworkEndTime = System.currentTimeMillis();
        clockworkTotalTimeSeconds += ((double) (clockworkEndTime - clockworkStartTime)) / 1000.0;
        return result;

    }

}