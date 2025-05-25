package shell.cuts.engines;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

import org.apache.commons.math3.util.Pair;

import shell.Toggle;
import shell.cuts.CutInfo;
import shell.cuts.CutMatchDistanceMatrix;
import shell.cuts.CutMatchList;
import shell.cuts.DisjointUnionSets;
import shell.cuts.Edge;
import shell.cuts.enums.Group;
import shell.cuts.enums.RouteType;
import shell.cuts.route.GroupInfo;
import shell.cuts.route.Route;
import shell.cuts.route.RouteComparator;
import shell.cuts.route.RouteInfo;
import shell.cuts.route.RouteMap;
import shell.cuts.route.RoutePair;
import shell.exceptions.BalancerException;
import shell.exceptions.MultipleCyclesFoundException;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Knot;
import shell.knot.Segment;

public class InternalPathEngine {
    public static long totalTimeIxdar = 0;
    public static int ixdarCalls = 0;
    public static long profileTimeIxdar = 0;
    public static long continueCount = 0;
    public static long noncontinueCount = 0;
    public static int comparisons;
    public static final RouteType[] routes = new RouteType[] { RouteType.prevC, RouteType.prevDC, RouteType.nextC,
            RouteType.nextDC };

    public static void resetMetrics() {
        totalTimeIxdar = 0;
        ixdarCalls = 0;
        profileTimeIxdar = 0;
        comparisons = 0;
        continueCount = 0;
        noncontinueCount = 0;
    }

    public static Pair<CutMatchList, RouteMap> calculateInternalPathLength(CutInfo c, RouteMap neighborRouteMap,
            CutMatchDistanceMatrix d) throws SegmentBalanceException, BalancerException {
        Segment cutSegment1 = c.lowerCutSegment;
        Knot cutPoint1 = c.lowerCutPoint;
        Knot knotPoint1 = c.lowerKnotPoint;
        Knot cutPoint2 = c.upperCutPoint;
        Knot knotPoint2 = c.upperKnotPoint;
        Segment cutSegment2 = c.upperCutSegment;
        Knot knot = c.knot;

        new SegmentBalanceException(c.shell, null, c);

        long startTimeIxdar = System.currentTimeMillis();

        ArrayList<Knot> knotPoints = knot.knotPointsFlattened;
        boolean startingWeights = neighborRouteMap != null;
        RouteMap routeMap1 = new RouteMap(c);
        @SuppressWarnings("unused")
        RouteMap copy = null;
        if (startingWeights) {
            routeMap1 = neighborRouteMap;
            copy = neighborRouteMap.copy();
        }
        PriorityQueue<RoutePair> heap = new PriorityQueue<RoutePair>(new RouteComparator());
        int numPoints = knot.size();
        for (int i = 0; i < numPoints; i++) {
            Knot k1 = knotPoints.get(i);
            RouteInfo r = null;
            if (routeMap1.containsKey(k1.id)) {
                r = routeMap1.get(k1.id);
            } else {
                Knot nextNeighbor = knot.getNext(k1);
                Knot prevNeighbor = knot.getPrev(k1);
                r = new RouteInfo(k1, Double.MAX_VALUE, prevNeighbor, nextNeighbor, null, null, knotPoint1,
                        knotPoint2, cutPoint1, cutPoint2, routeMap1, i);
                routeMap1.put(k1.id, i, r);
            }
            if (k1.equals(cutPoint1)) {
                boolean knotPointIsPrev = knotPoint1.equals(knot.getPrev(cutPoint1));
                if (c.knotPointsConnected) {
                    if (knotPointIsPrev) {
                        r.prevC.delta = 0;
                    } else {
                        r.nextC.delta = 0;
                    }

                    heap.add(new RoutePair(r.getRoute(knotPointIsPrev ? RouteType.prevC : RouteType.nextC)));
                } else {
                    if (knotPointIsPrev) {
                        r.prevDC.delta = 0;
                    } else {
                        r.nextDC.delta = 0;
                    }

                    heap.add(new RoutePair(r.getRoute(knotPointIsPrev ? RouteType.prevDC : RouteType.nextDC)));
                }

                if (r.nextC.delta != Double.MAX_VALUE && r.nextC.delta != 0) {
                    heap.add(new RoutePair(r.nextC));
                }
                if (r.nextDC.delta != Double.MAX_VALUE && r.nextDC.delta != 0) {
                    heap.add(new RoutePair(r.nextDC));
                }
                if (r.prevC.delta != Double.MAX_VALUE && r.prevC.delta != 0) {
                    heap.add(new RoutePair(r.prevC));
                }
                if (r.prevDC.delta != Double.MAX_VALUE && r.prevDC.delta != 0) {
                    heap.add(new RoutePair(r.prevDC));
                }
            } else if (startingWeights) {
                if (r.nextC.delta != Double.MAX_VALUE) {
                    heap.add(new RoutePair(r.nextC));
                }
                if (r.nextDC.delta != Double.MAX_VALUE) {
                    heap.add(new RoutePair(r.nextDC));
                }
                if (r.prevC.delta != Double.MAX_VALUE) {
                    heap.add(new RoutePair(r.prevC));
                }
                if (r.prevDC.delta != Double.MAX_VALUE) {
                    heap.add(new RoutePair(r.prevDC));
                }
            }
        }
        ArrayList<Integer> leftGroup = paintState(c.knotPointsConnected ? Group.Left : Group.Left, knot,
                knotPoint1, cutPoint1, cutSegment2,
                routeMap1);
        ArrayList<Integer> rightGroup = paintState(c.knotPointsConnected ? Group.Right : Group.Right,
                knot, cutPoint1, knotPoint1,
                cutSegment2, routeMap1);

        // 5%
        RouteInfo start = routeMap1.get(cutPoint1.id);
        if (start.group == Group.Left) {
            start.assignGroup(leftGroup, rightGroup, c, routeMap1);
        } else {
            start.assignGroup(rightGroup, leftGroup, c, routeMap1);
        }

        for (RouteInfo r : routeMap1.values()) {
            if (r.group == Group.Left) {
                r.assignGroup(leftGroup, rightGroup, c, routeMap1);
            } else {
                r.assignGroup(rightGroup, leftGroup, c, routeMap1);
            }
            for (int i = 0; i < 4; i++) {
                Route route = r.routes[i];
                for (int j = 0; j < route.ourGroup.size(); j++) {
                    int vp = route.ourGroup.get(j);
                    GroupInfo g = route.groupInfo[vp];
                    if (g == null) {
                        g = new GroupInfo(true, false, j);
                        route.groupInfo[vp] = g;
                    }
                    g.index = j;
                    g.isOurGroup = true;
                }
                for (int j = 0; j < route.otherGroup.size(); j++) {
                    int vp = route.otherGroup.get(j);
                    GroupInfo g = route.groupInfo[vp];
                    if (g == null) {
                        g = new GroupInfo(false, false, j);
                        route.groupInfo[vp] = g;
                    }
                    g.index = j;
                    g.isOurGroup = false;
                }
            }
        }
        RouteInfo end = routeMap1.get(knotPoint2.id);
        Route endRoute = end.nextC;
        if (endRoute.neighbor.id != cutPoint2.id) {
            endRoute = end.prevC;
        }
        bellmanFordDjikstras(knot, c, knotPoints, routeMap1, heap, d);
        RouteMap routeMap = routeMap1;
        ixdarCalls++;
        long endTimeIxdar = System.currentTimeMillis() - startTimeIxdar;
        totalTimeIxdar += endTimeIxdar;
        RouteInfo curr = routeMap.get(knotPoint2.id);
        RouteType prevCutSide = RouteType.None;
        if (curr.prevC.neighbor.id == cutPoint2.id) {
            prevCutSide = RouteType.prevC;
        } else {
            prevCutSide = RouteType.nextC;
        }

        Route route = curr.getRoute(prevCutSide);
        ArrayList<Segment> cutSegments = route.cuts;
        ArrayList<Segment> matchSegments = route.matches;
        DisjointUnionSets unionSet = new DisjointUnionSets(knot.knotPointsFlattened);
        for (Segment s : matchSegments) {
            unionSet.union(s.first.id, s.last.id);
        }
        for (Segment s : knot.manifoldSegments) {
            if (!cutSegments.contains(s) && !s.equals(cutSegment1) && !s.equals(cutSegment2)) {
                unionSet.union(s.first.id, s.last.id);
            }
        }
        cutSegments.remove(cutSegment1);
        cutSegments.remove(cutSegment2);

        CutMatchList cutMatchList = new CutMatchList(c.shell, c, knot);
        try {
            cutMatchList.addLists(cutSegments, matchSegments, knot, "InternalPathEngine");
        } catch (SegmentBalanceException be) {
            throw be;
        }
        if (neighborRouteMap != null && Toggle.IxdarCheckRotationalAnswerSharing.value) {
            RouteMap checkRouteMap = calculateInternalPathLength(c, null, d).getSecond();
            ArrayList<Route> correctList = new ArrayList<>();
            ArrayList<Route> compareList = new ArrayList<>();
            for (RouteInfo correctRI : checkRouteMap.values()) {
                RouteInfo compareRi = neighborRouteMap.get(correctRI.id);
                for (Route correctRoute : correctRI.routes) {
                    Route compareRoute = compareRi.getRoute(correctRoute.routeType);
                    if (compareRoute.delta != correctRoute.delta) {
                        correctList.add(correctRoute);
                        compareList.add(compareRoute);
                    }
                }
            }
            if (compareList.size() != 0) {
                if (unionSet.find(cutPoint1.id) != unionSet.find(cutPoint2.id)
                        || unionSet.find(cutPoint1.id) != unionSet.find(knotPoint1.id)
                        || unionSet.find(cutPoint2.id) != unionSet.find(knotPoint2.id)
                        || unionSet.find(knotPoint1.id) != unionSet.find(knotPoint2.id)) {
                    throw new MultipleCyclesFoundException(c.shell, cutMatchList, matchSegments, cutSegments, c);
                }
            }
        }

        if (unionSet.find(cutPoint1.id) != unionSet.find(cutPoint2.id)
                || unionSet.find(cutPoint1.id) != unionSet.find(knotPoint1.id)
                || unionSet.find(cutPoint2.id) != unionSet.find(knotPoint2.id)
                || unionSet.find(knotPoint1.id) != unionSet.find(knotPoint2.id)) {
            throw new MultipleCyclesFoundException(c.shell, cutMatchList, matchSegments, cutSegments, c);
        }
        return new Pair<>(cutMatchList, routeMap);
    }

    /**
     * Bellman Ford + Djikstras Algorithm
     * 
     * <pre>
     * Set all verticies v undiscovered, d(v) = inf
     * set d(s) = 0, mark s discovered.
     * Make heap H.
     * 1. Let settled = empty set
     * 2. while heap H is not empty,
     *      delete u with minimum d(u) value from heap H
     *      add u to settled
     *      for each edge (u,v) with w(u,v) >= 0:
     *          if(d(v) > d(u) + w(u,v))
     *              update d(v) = d(u) + w(u,v)
     *              add v to heap
     * 3. for every edge (u,v) with u in settled and w(u,v) < 0
     *      if(d(v) > d(u) + w(u,v))
     *          update(d(v)) = d(u) + w(u,v)
     *          add v to heap
     * 4. if(heap not empty)
     *       go to 1
     * </pre>
     */
    private final static void bellmanFordDjikstras(Knot knot, CutInfo c, ArrayList<Knot> knotPoints,
            RouteMap routeMap1,
            PriorityQueue<RoutePair> heap, CutMatchDistanceMatrix d) throws SegmentBalanceException {

        // 4. if heap not empty got to one
        while (heap.size() != 0) {
            // 1. let settled = empty set
            // 85%
            Set<Route> settled = new HashSet<Route>();
            // Positive Values: while heap H is not empty
            while (heap.size() != 0) {
                // delete u with minimum d(u) value from heap H
                Route u = heap.poll().route;
                settled.add(u);
                relaxShortestPath(u, routeMap1, heap, false, knot, c, d);
                // for each edge (u,v) with w(u,v) > 0:

            }

            // 4%
            // for every edge (u,v) with u in settled
            for (Route u : settled) {
                relaxShortestPath(u, routeMap1, heap, true, knot, c, d);
            }
        }
    }

    private final static void relaxShortestPath(Route u, RouteMap routeMap1,
            PriorityQueue<RoutePair> heap, boolean negativeWeights, Knot knot, CutInfo c, CutMatchDistanceMatrix d)
            throws SegmentBalanceException {
        ArrayList<Edge> edges = null;

        // 0.08%
        double uDelta = u.delta;
        RouteInfo uParent = u.parent;
        int uIdx = uParent.index;
        if (negativeWeights) {
            edges = d.negativeEdges.get(uIdx);
        } else {
            edges = d.positiveEdges.get(uIdx);
        }

        // 79%
        for (Edge e : edges) {
            // 0.17%
            RouteInfo v = routeMap1.routeInfos[e.idx];
            // 41-51%
            for (int j = 0; j < 2; j++) {
                // 2.3%

                Route vRoute = v.routes[e.routeOffset + j];

                // 3.89-3.94%
                // with w(u,v) >= 0:
                // if(d(v) > d(u) + w(u,v))
                double distance = d.matrix[uIdx][e.matIdx];
                double newDistancePrevNeighbor = uDelta + distance;
                if (newDistancePrevNeighbor >= vRoute.delta) {
                    continue;
                }
                // each edge (u,v)
                // 14%
                RouteType vRouteType = vRoute.routeType;
                Segment cutSeg = e.cutSegment;
                Segment acrossSeg = e.acrossSegment;
                Boolean canConnet = canConnect(u, v, vRoute, vRouteType, acrossSeg, cutSeg, uParent, knot, c,
                        routeMap1);

                if (!canConnet) {
                    continue;
                }

                // 24=26%
                // update d(v) = d(u) + w(u,v)

                v.updateRoute(newDistancePrevNeighbor, uParent.node, vRouteType, u.routeType, u, acrossSeg);
                // add v to heap
                // 1.75%
                if (!cutSeg.equals(c.upperCutSegment)) {
                    RoutePair routePair = new RoutePair(vRoute);
                    heap.add(routePair);
                }
            }
        }
    }

    private static boolean canConnect(Route u, RouteInfo v, Route vRoute, RouteType vRouteType, Segment acrossSeg,
            Segment cutSeg, RouteInfo uParent, Knot knot, CutInfo c, RouteMap map) {
        Knot neighbor = vRoute.neighbor;
        // you cannot connect to cut segment 1
        if (v.id == c.lowerCutPoint.id && neighbor.id == c.lowerKnotPoint.id) {
            return false;
        }
        if (v.id == c.lowerKnotPoint.id && neighbor.id == c.lowerCutPoint.id) {
            return false;
        }

        // 4%
        // you cannot connect to your neighbor
        if (acrossSeg.contains(uParent.prevC.neighbor) || acrossSeg.contains(uParent.nextC.neighbor)) {
            return false;
        }
        // you cannot form a cycle by going backwards in your history of cuts
        if (u.cuts.contains(cutSeg)) {
            return false;
        }

        // 4-6%
        // you cannot make a connection where you form a cycle with your own tail:
        // e.g. if our group is 1 2 3 4 and u is 4 and the cut is [1 2]
        // you cannot match 2 to 4 and can only match 1 to 4 otherwise you'd form a
        // cycle 2 3 4 2 3 4 ...

        int neighborIdx = u.groupInfo[neighbor.id].index;
        int vIdx = u.groupInfo[v.node.id].index;
        boolean neighborInGroup = u.groupInfo[neighbor.id].isOurGroup;
        if (neighborInGroup) {
            if (neighborIdx < vIdx) {
                return false;
            }
        }

        // 8%
        boolean vIsConnected = vRouteType.isConnected;
        boolean uIsConnected = u.routeType.isConnected;
        // we can only finish if we are in the connected state
        if (neighbor.id == c.upperCutPoint.id && v.id == c.upperKnotPoint.id) {
            if (!uIsConnected || !vIsConnected) {
                return false;
            }
        } else if (neighborInGroup) {
            // if we are connecting to our own group we must maintain our connectedness
            // state, i.e. you can only go from connected to disconnected or visa versa by
            // connecting to the other group
            if (uIsConnected ^ vIsConnected) {
                return false;
            }
        } else {

            // get any knotpoint in the other group
            ArrayList<Integer> grp = u.otherGroup;
            int knotPoint = grp.get(0);
            int knotPointIdx = 0;
            if (!(c.lowerKnotPoint.id == knotPoint || c.upperKnotPoint.id == knotPoint)) {
                knotPointIdx = grp.size() - 1;
            }
            // checks wether the cut is facing away from or toward the knotpoint
            // e.g. #1
            // if we have knotpoints 1 and 4 and the other group is 1 2 3 4 then no
            // matter what orientation our cut is we will enter the connected state
            // e.g. #2
            // if we have knotpoint 1 and 99 and the other group is 1 2 3 4 then if we cut
            // [2 3] and match to 3 we will remain in the connected state
            // e.g. #3
            // if we have knotpoint 1 and 99 and the other group is 1 2 3 4 then if we cut
            // [2 3] and match to 2 we will enter the disconnected state
            boolean between = false;
            if ((neighborIdx >= knotPointIdx && neighborIdx < vIdx) ||
                    (neighborIdx <= knotPointIdx && neighborIdx > vIdx)) {
                between = true;
            }
            // see example #1 we cannot connect to the other group from the disconnected
            // state and remain disconnected since if u is disconnected then both ends of
            // the other group are knotpoints
            if (!uIsConnected && !vIsConnected) {
                return false;
            }
            // see example #2 if our cut is facing so that the neighbor is farther away than
            // the node from the knotpoint then we must enter the connected state
            if (!between && !vIsConnected) {
                return false;
            }
            // see example #3 if the cut is facing so that the neighbor is closer than the
            // node to the knotpoint then we must enter the disconnected state. again if we
            // are coming from disconnected state then this does not matter and we can
            // always go to the connected state.
            if (between && vIsConnected && uIsConnected) {
                return false;
            }
        }
        return true;

    }

    public static ArrayList<Integer> paintState(Group group, Knot knot, Knot knotPoint,
            Knot cutPoint, Segment cutSegment,
            RouteMap routeInfo) {
        ArrayList<Integer> result = new ArrayList<>();
        int idx = knot.knotPoints.indexOf(knotPoint);
        int idx2 = knot.knotPoints.indexOf(cutPoint);
        int marchDirection = idx2 - idx < 0 ? -1 : 1;
        if (idx == 0 && idx2 == knot.knotPoints.size() - 1) {
            marchDirection = -1;
        }
        if (idx2 == 0 && idx == knot.knotPoints.size() - 1) {
            marchDirection = 1;
        }
        marchDirection = -marchDirection;
        int totalIter = 0;
        Knot prev2 = knot.knotPoints.get(idx2);
        while (true) {
            Knot curr = knot.knotPoints.get(idx);
            if (cutSegment.contains(curr) && cutSegment.contains(prev2)) {
                break;
            }
            if (group != Group.None) {
                result.add(curr.id);
            }
            RouteInfo r = routeInfo.get(curr.id);
            if (group != Group.None) {
                r.group = group;
                r.rotDist = totalIter;
            }
            prev2 = curr;
            int next = idx + marchDirection;
            if (marchDirection < 0 && next < 0) {
                next = knot.knotPoints.size() - 1;
            } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                next = 0;
            }
            idx = next;
            totalIter++;
            if (totalIter > knot.knotPoints.size()) {
                break;
            }
        }
        return result;
    }
}