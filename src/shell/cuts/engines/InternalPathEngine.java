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
            RouteMap neighborRouteMap)
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
        if (startingWeights) {
            routeMap1 = neighborRouteMap;
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
        ArrayList<VirtualPoint> leftGroup = paintState(knotPointsConnected ? Group.Left : Group.Left, knot,
                knotPoint1, cutPoint1, cutSegment2,
                routeMap1);
        ArrayList<VirtualPoint> rightGroup = paintState(knotPointsConnected ? Group.Right : Group.Right,
                knot, cutPoint1, knotPoint1,
                cutSegment2, routeMap1);

        RouteInfo start = routeMap1.get(cutPoint1.id);
        if (start.group == Group.Left) {
            start.assignGroup(leftGroup, rightGroup);
        } else {
            start.assignGroup(rightGroup, leftGroup);
        }

        for (RouteInfo r : routeMap1.values()) {
            if (r.group == Group.Left) {
                r.assignGroup(leftGroup, rightGroup);
            } else {
                r.assignGroup(rightGroup, leftGroup);
            }
        }
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

        RouteType[] routes = new RouteType[] { RouteType.prevC, RouteType.prevDC, RouteType.nextC,
                RouteType.nextDC };
        // 4. if heap not empty got to one
        while (heap.size() != 0) {
            // 1. let settled = empty set
            Set<Route> settled = new HashSet<Route>();
            // Positive Values: while heap H is not empty
            while (heap.size() != 0) {
                // delete u with minimum d(u) value from heap H
                Route u = heap.poll().route;
                RouteInfo uParent = u.parent;
                // add u to settled
                settled.add(u);

                // for each edge (u,v) with w(u,v) > 0:
                for (int i = 0; i < knotPoints.size(); i++) {
                    RouteInfo v = routeMap1.get(knotPoints.get(i).id);

                    if (uParent.id == v.id) {
                        continue;
                    }
                    if (v.id != end.id && u.matches.size() == knotNumber) {
                        continue;
                    }
                    for (RouteType vRouteType : routes) {
                        Route vRoute = v.getRoute(vRouteType);
                        VirtualPoint neighbor = vRoute.neighbor;


                        Segment acrossSeg = neighbor.getSegment(uParent.node);
                        Segment cutSeg = neighbor.getSegment(v.node);
                        // each edge (u,v)
                        if (!canConnect(u, v, vRouteType, neighbor, acrossSeg, cutSeg, uParent, knot)) {
                            continue;
                        }
                        
                        // with w(u,v) >= 0:

                        double edgeDistance = acrossSeg.distance;
                        double cutDistance = cutSeg.distance;
                        double distance = edgeDistance - cutDistance;

                        if (distance < 0) {
                            continue;
                        }
                        // if(d(v) > d(u) + w(u,v))
                        double newDistancePrevNeighbor = u.delta + edgeDistance - cutDistance;
                        if (newDistancePrevNeighbor < vRoute.delta) {
                            // update d(v) = d(u) + w(u,v)
                            v.updateRoute(newDistancePrevNeighbor, uParent.node, vRouteType, u.routeType, u);
                            // add v to heap
                            RoutePair routePair = new RoutePair(vRoute);
                            heap.add(routePair);
                        }
                    }
                }
            }
            
            //for every edge (u,v) with u in settled
            for (Route u : settled) {
                RouteInfo uParent = u.parent;
                for (int i = 0; i < knotPoints.size(); i++) {
                    RouteInfo v = routeMap1.get(knotPoints.get(i).id);

                    if (uParent.id == v.id) {
                        continue;
                    }
                    if (v.id != end.id && u.matches.size() == knotNumber) {
                        continue;
                    }
                    for (RouteType vRouteType : routes) {
                        Route vRoute = v.getRoute(vRouteType);
                        VirtualPoint neighbor = vRoute.neighbor;

                        // with w(u,v) < 0:
                        Segment acrossSeg = neighbor.getSegment(uParent.node);
                        Segment cutSeg = neighbor.getSegment(v.node);

                        // each edge (u,v)
                        if (!canConnect(u, v, vRouteType, neighbor, acrossSeg, cutSeg, uParent, knot)) {
                            continue;
                        }

                        double edgeDistance = acrossSeg.distance;
                        double cutDistance = cutSeg.distance;
                        double distance = edgeDistance - cutDistance;

                        if (distance >= 0) {
                            continue;
                        }

                        // if(d(v) > d(u) + w(u,v))
                        double newDistancePrevNeighbor = u.delta + edgeDistance - cutDistance;
                        if (newDistancePrevNeighbor < vRoute.delta) {
                            // update d(v) = d(u) + w(u,v)
                            v.updateRoute(newDistancePrevNeighbor, uParent.node, vRouteType, u.routeType, u);
                            // add v to heap
                            RoutePair routePair = new RoutePair(vRoute);
                            heap.add(routePair);
                        }
                    }
                }
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

        if (unionSet.find(cutPoint1.id) != unionSet.find(cutPoint2.id)
                || unionSet.find(cutPoint1.id) != unionSet.find(knotPoint1.id)
                || unionSet.find(cutPoint2.id) != unionSet.find(knotPoint2.id)
                || unionSet.find(knotPoint1.id) != unionSet.find(knotPoint2.id)) {
            throw new MultipleCyclesFoundException(c.shell, cutMatchList, matchSegments, cutSegments, c);
        }
        return new Pair<>(cutMatchList, routeMap);
    }

    private static boolean canConnect(Route u, RouteInfo v, RouteType vRouteType, VirtualPoint neighbor,
            Segment acrossSeg,
            Segment cutSeg, RouteInfo uParent, Knot knot) {

        boolean neighborInGroup = u.ourGroup.contains(neighbor);
        if (neighborInGroup) {
            int nIdx = u.ourGroup.indexOf(neighbor);
            int vIdx = u.ourGroup.indexOf(v.node);
            if (nIdx < vIdx) {
                return false;
            }
        }
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

        boolean vIsConnected = vRouteType.isConnected;
        boolean uIsConnected = u.routeType.isConnected;
        if (neighbor.id == uParent.cutPoint2.id && v.id == uParent.knotPoint2.id) {
            if (!uIsConnected || !vIsConnected) {
                return false;
            }
        } else if (neighborInGroup) {
            if (uIsConnected && !vIsConnected) {
                return false;
            }
            if (!uIsConnected && vIsConnected) {
                return false;
            }
        } else {

            ArrayList<VirtualPoint> grp = u.otherGroup;
            VirtualPoint knotPoint = grp.get(0);
            int knotPointIdx = 0;
            if (!(uParent.knotPoint1.id == knotPoint.id || uParent.knotPoint2.id == knotPoint.id)) {
                knotPoint = grp.get(grp.size() - 1);
                knotPointIdx = grp.size() - 1;
            }
            int neighborIdx = grp.indexOf(neighbor);
            int vIdx = grp.indexOf(v.node);
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

        if (knot.hasSegmentManifold(acrossSeg)) {
            return false;
        }
        if (u.cuts.contains(cutSeg)) {
            return false;
        }
        return true;
    }

    public static ArrayList<VirtualPoint> paintState(Group group, Knot knot, VirtualPoint knotPoint,
            VirtualPoint cutPoint, Segment cutSegment,
            RouteMap routeInfo) {
        ArrayList<VirtualPoint> result = new ArrayList<VirtualPoint>();
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
                result.add(curr);
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