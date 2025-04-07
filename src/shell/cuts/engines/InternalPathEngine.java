package shell.cuts.engines;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

import org.apache.commons.math3.util.Pair;


import shell.BalanceMap;
import shell.Toggle;
import shell.cuts.CutInfo;
import shell.cuts.CutMatchList;
import shell.cuts.DisjointUnionSets;
import shell.cuts.enums.Group;
import shell.cuts.enums.RouteType;
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
import shell.knot.VirtualPoint;

public class InternalPathEngine {
    public static long totalTimeIxdar = 0;
    public static int ixdarCalls = 0;
    public static long profileTimeIxdar = 0;
    public static int comparisons;
    public static final RouteType[] routes = new RouteType[] { RouteType.prevC, RouteType.prevDC, RouteType.nextC,
            RouteType.nextDC };

    public static void resetMetrics() {
        totalTimeIxdar = 0;
        ixdarCalls = 0;
        profileTimeIxdar = 0;
        comparisons = 0;
    }

    public static Pair<CutMatchList, RouteMap> calculateInternalPathLength(
            VirtualPoint knotPoint1, VirtualPoint cutPoint1, VirtualPoint external1,
            VirtualPoint knotPoint2, VirtualPoint cutPoint2, VirtualPoint external2,
            Knot knot, BalanceMap balanceMap, CutInfo c, boolean knotPointsConnected,
            RouteMap neighborRouteMap, RouteMap neighborRouteMapActual)
            throws SegmentBalanceException, BalancerException {
        Segment cutSegment1 = knotPoint1.getClosestSegment(cutPoint1, null);
        Segment cutSegment2 = knotPoint2.getClosestSegment(cutPoint2, null);
        SegmentBalanceException sbe = new SegmentBalanceException(c.shell, null,
                new CutInfo(c.shell, knotPoint1, cutPoint1, cutSegment1, external1,
                        knotPoint2, cutPoint2, cutSegment2, external2, knot,
                        balanceMap, knotPointsConnected));

        long startTimeIxdar = System.currentTimeMillis();

        ArrayList<VirtualPoint> knotPoints = knot.knotPointsFlattened;
        boolean startingWeights = neighborRouteMap != null;
        RouteMap routeMap1 = new RouteMap(c);
        RouteMap copy = null;
        if (startingWeights) {
            routeMap1 = neighborRouteMap;
            copy = neighborRouteMap.copy();

        }
        PriorityQueue<RoutePair> heap = new PriorityQueue<RoutePair>(new RouteComparator());
        int numPoints = knot.size();
        for (int i = 0; i < numPoints; i++) {
            VirtualPoint k1 = knotPoints.get(i);
            RouteInfo r = null;
            if (routeMap1.containsKey(k1.id)) {
                r = routeMap1.get(k1.id);
            } else {
                VirtualPoint nextNeighbor = knot.getNext(k1);
                VirtualPoint prevNeighbor = knot.getPrev(k1);
                r = new RouteInfo(k1, Double.MAX_VALUE, prevNeighbor, nextNeighbor, null, null, knotPoint1,
                        knotPoint2, cutPoint1, cutPoint2, routeMap1);
                routeMap1.put(k1.id, r);
            }
            if (k1.equals(cutPoint1)) {
                boolean knotPointIsPrev = knotPoint1.equals(knot.getPrev(cutPoint1));
                if (knotPointsConnected) {
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
        ArrayList<Integer> leftGroup = paintState(knotPointsConnected ? Group.Left : Group.Left, knot,
                knotPoint1, cutPoint1, cutSegment2,
                routeMap1);
        ArrayList<Integer> rightGroup = paintState(knotPointsConnected ? Group.Right : Group.Right,
                knot, cutPoint1, knotPoint1,
                cutSegment2, routeMap1);
        if (startingWeights) {
            float z = 0;
        }
        //18%
        long startTimeProfileIxdar = System.currentTimeMillis();
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
        }

        
        long endTimeProfileIxdar = System.currentTimeMillis();
        profileTimeIxdar += endTimeProfileIxdar - startTimeProfileIxdar;
        RouteInfo end = routeMap1.get(knotPoint2.id);
        Route endRoute = end.nextC;
        if (endRoute.neighbor.id != cutPoint2.id) {
            endRoute = end.prevC;
        }

        // calculateInternalPathLength(knotPoint1, cutPoint1, external1, knotPoint2,
        // cutPoint2, external2, knot, balanceMap, c, knotPointsConnected,null)
        int knotNumber = Integer.MAX_VALUE;
        if (Toggle.IxdarKnotDistance.value) {
            knotNumber = c.knotDistance();
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

        // 4. if heap not empty got to one
        while (heap.size() != 0) {
            // 1. let settled = empty set
            Set<Route> settled = new HashSet<Route>();
            // Positive Values: while heap H is not empty
            while (heap.size() != 0) {
                // delete u with minimum d(u) value from heap H
                Route u = heap.poll().route;
                // add u to settled
                settled.add(u);
                relaxShortestPath(u, knotPoints, routeMap1, heap, false, knot, c);
                // for each edge (u,v) with w(u,v) > 0:

            }

            // for every edge (u,v) with u in settled
            for (Route u : settled) {
                relaxShortestPath(u, knotPoints, routeMap1, heap, true, knot, c);
            }
        }
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

        CutMatchList cutMatchList = new CutMatchList(c.shell, sbe, knot);
        try {
            cutMatchList.addLists(cutSegments, matchSegments, knot, "InternalPathEngine");
        } catch (SegmentBalanceException be) {
            throw be;
        }
        if (neighborRouteMap != null && Toggle.IxdarCheckRotationalAnswerSharing.value) {
            RouteMap checkRouteMap = calculateInternalPathLength(knotPoint1, cutPoint1, external1, knotPoint2,
                    cutPoint2, external2, knot, balanceMap, c, knotPointsConnected, null, null).getSecond();
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
                float z = 0;
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

    private static void relaxShortestPath(Route u, ArrayList<VirtualPoint> knotPoints, RouteMap routeMap1,
            PriorityQueue<RoutePair> heap, boolean negativeWeights, Knot knot, CutInfo c)
            throws SegmentBalanceException {
        for (int i = 0; i < knotPoints.size(); i++) {
            // 2-3.71%
            RouteInfo uParent = u.parent;
            RouteInfo v = routeMap1.get(knotPoints.get(i).id);
            if (uParent.id == v.id) {
                continue;
            }
            for (RouteType vRouteType : routes) {
                // 1.18%
                Route vRoute = v.getRoute(vRouteType);
                VirtualPoint neighbor = vRoute.neighbor;
                // 1.4%
                Segment acrossSeg = uParent.node.pointSegmentLookup[neighbor.id];
                Segment cutSeg = vRoute.neighborSegment;

                if (acrossSeg == null || cutSeg == null) {
                    continue;
                }
                // 5%

                double edgeDistance = acrossSeg.distance;
                double cutDistance = cutSeg.distance;
                double distance = edgeDistance - cutDistance;
                boolean negative = distance < 0;
                if (negativeWeights ^ negative) {
                    continue;
                }
                // 11%
                // with w(u,v) >= 0:
                // if(d(v) > d(u) + w(u,v))
                double newDistancePrevNeighbor = u.delta + distance;
                if (newDistancePrevNeighbor >= vRoute.delta) {
                    continue;
                }
                // each edge (u,v)
                // 23%
                boolean canConnet = canConnect(u, v, vRouteType, neighbor, acrossSeg, cutSeg, uParent, knot, c);
                if (!canConnet) {
                    continue;
                }
                // 19%
                // update d(v) = d(u) + w(u,v)
                v.updateRoute(newDistancePrevNeighbor, uParent.node, vRouteType, u.routeType, u);
                // add v to heap
                if (!cutSeg.equals(c.upperCutSegment)) {
                    RoutePair routePair = new RoutePair(vRoute);
                    heap.add(routePair);
                }
            }
        }
    }

    private static boolean canConnect(Route u, RouteInfo v, RouteType vRouteType, VirtualPoint neighbor,
            Segment acrossSeg,
            Segment cutSeg, RouteInfo uParent, Knot knot, CutInfo c) {

        boolean retValue = true;
        // 2%
        if (u.delta == Double.MAX_VALUE) {
            return false;
        }

        if (uParent.node.id == neighbor.id) {
            return false;
        }

        if (v.id == uParent.cutPoint1.id && neighbor.id == uParent.knotPoint1.id) {
            return false;
        }
        if (v.id == uParent.knotPoint1.id && neighbor.id == uParent.cutPoint1.id) {
            return false;
        }

        // 4%
        if (acrossSeg.contains(uParent.prevC.neighbor) || acrossSeg.contains(uParent.nextC.neighbor)) {
            return false;
        }
        if (u.cuts.contains(cutSeg)) {
            return false;
        }

        // 7%
        boolean neighborInGroup = u.ourGroup.contains(neighbor.id);
        if (neighborInGroup) {
            int nIdx = u.ourGroup.indexOf(neighbor.id);
            int vIdx = u.ourGroup.indexOf(v.node.id);
            if (nIdx < vIdx) {
                return false;
            }
        }

        // 8%
        boolean vIsConnected = vRouteType.isConnected;
        boolean uIsConnected = u.routeType.isConnected;
        if (neighbor.id == uParent.cutPoint2.id && v.id == uParent.knotPoint2.id) {
            if (!uIsConnected || !vIsConnected) {
                return false;
            }
        } else if (neighborInGroup) {
            if (uIsConnected ^ vIsConnected) {
                return false;
            }
        } else {

            ArrayList<Integer> grp = u.otherGroup;
            int knotPoint = grp.get(0);
            int knotPointIdx = 0;
            if (!(uParent.knotPoint1.id == knotPoint || uParent.knotPoint2.id == knotPoint)) {
                knotPointIdx = grp.size() - 1;
                knotPoint = grp.get(knotPointIdx);
            }
            int neighborIdx = grp.indexOf(neighbor.id);
            int vIdx = grp.indexOf(v.node.id);
            boolean between = false;
            if ((neighborIdx >= knotPointIdx && neighborIdx < vIdx) ||
                    (neighborIdx <= knotPointIdx && neighborIdx > vIdx)) {
                between = true;
            }
            if (!uIsConnected && !vIsConnected) {
                return false;
            }
            if (!between && !vIsConnected) {
                return false;
            }
            if (between && vIsConnected && uIsConnected) {
                return false;
            }
        }

        return retValue;

    }

    public static ArrayList<Integer> paintState(Group group, Knot knot, VirtualPoint knotPoint,
            VirtualPoint cutPoint, Segment cutSegment,
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
        VirtualPoint prev2 = knot.knotPoints.get(idx2);
        while (true) {
            VirtualPoint curr = knot.knotPoints.get(idx);
            if (cutSegment.contains(curr) && cutSegment.contains(prev2)) {
                break;
            }
            if (group != Group.None) {
                result.add(curr.id);
            }
            RouteInfo r = routeInfo.get(curr.id);
            if (group != Group.None) {
                r.group = group;
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