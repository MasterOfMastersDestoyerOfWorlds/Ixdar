package shell.cuts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

import org.apache.commons.math3.util.Pair;

import shell.BalanceMap;
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
            Knot knot, BalanceMap balanceMap, CutInfo c, boolean knotPointsConnected)
            throws SegmentBalanceException, BalancerException {
        Segment cutSegment1 = knotPoint1.getClosestSegment(cutPoint1, null);
        Segment cutSegment2 = knotPoint2.getClosestSegment(cutPoint2, null);
        SegmentBalanceException sbe = new SegmentBalanceException(c.shell, null,
                new CutInfo(c.shell, knotPoint1, cutPoint1, cutSegment1, external1,
                        knotPoint2, cutPoint2, cutSegment2, external2, knot,
                        balanceMap));
        CutEngine ce = c.shell.cutEngine;
        FlattenEngine fe = ce.flattenEngine;
        Knot smallestKnot1 = fe.flatKnots.get(c.shell.smallestKnotLookup[cutPoint1.id]);
        int smallestKnot1Height = fe.flatKnotsHeight.get(smallestKnot1.id);
        Knot smallestKnot2 = fe.flatKnots.get(c.shell.smallestKnotLookup[cutPoint2.id]);
        int smallestKnot2Height = fe.flatKnotsHeight.get(smallestKnot2.id);
        Knot smallestCommonKnot = fe.flatKnots
                .get(c.shell.smallestCommonKnotLookup[cutPoint2.id][cutPoint1.id]);
        int smallestCommonKnotHeight = fe.flatKnotsHeight.get(smallestCommonKnot.id);
        int knotLayer = Math.max(1, smallestCommonKnotHeight - smallestKnot1Height + smallestCommonKnotHeight
                - smallestKnot2Height)
                + (knotPointsConnected ? 0 : 1);
        if (smallestKnot2.contains(cutPoint1)) {
            knotLayer = Math.max(1, smallestKnot2Height - smallestKnot1Height + 1
                    + (knotPointsConnected ? 0 : 1));
        }
        if (smallestKnot1.contains(cutPoint2)) {
            knotLayer = Math.max(1, smallestKnot1Height - smallestKnot2Height + 1
                    + (knotPointsConnected ? 0 : 1));
        }
        if (smallestKnot1.id == smallestKnot2.id) {
            // if (knotPointsConnected) {
            // CutMatchList cutMatchList = new CutMatchList(shell, sbe, knot);
            // Segment simpleMatch = cutPoint1.getClosestSegment(cutPoint2, null);
            // ArrayList<Segment> segList = new ArrayList<>();
            // segList.add(simpleMatch);
            // cutMatchList.addLists(new ArrayList<Segment>(), segList, knot,
            // "InternalPathEngine");
            // return new Pair<>(cutMatchList, null);
            // }
        }
        long startTimeIxdar = System.currentTimeMillis();
        knotLayer = c.shell.cutEngine.flattenEngine.flatKnots.size();
        RouteMap routeMap = ixdar(knotPoint1, cutPoint1, knotPoint2, cutPoint2,
                knot, knotPointsConnected, cutSegment1, cutSegment2, -1, -1, RouteType.None, knotLayer,
                smallestCommonKnot);
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
            System.out.println(knotLayer);
            throw new MultipleCyclesFoundException(c.shell, cutMatchList, matchSegments, cutSegments, c);
        }
        return new Pair<>(cutMatchList, routeMap);
    }

    public static RouteMap ixdar(VirtualPoint knotPoint1, VirtualPoint cutPoint1,
            VirtualPoint knotPoint2, VirtualPoint cutPoint2,
            Knot knot, boolean knotPointsConnected, Segment cutSegment1, Segment cutSegment2, int steps,
            int sourcePoint, RouteType routeType, int knotNumber, Knot smallestCommonKnot) {

        ArrayList<VirtualPoint> knotPoints = knot.knotPointsFlattened;
        RouteMap routeMap = new RouteMap();
        PriorityQueue<RoutePair> q = new PriorityQueue<RoutePair>(new RouteComparator());
        Set<Integer> settled = new HashSet<Integer>();
        int numPoints = knot.size();
        for (int i = 0; i < numPoints; i++) {
            VirtualPoint k1 = knotPoints.get(i);
            VirtualPoint nextNeighbor = knot.getNext(k1);
            VirtualPoint prevNeighbor = knot.getPrev(k1);
            RouteInfo r = new RouteInfo(k1, Double.MAX_VALUE, prevNeighbor, nextNeighbor, null, null, knotPoint1,
                    knotPoint2, cutPoint1, cutPoint2);
            if (k1.equals(cutPoint1)) {
                boolean knotPointIsPrev = knotPoint1.equals(knot.getPrev(cutPoint1));
                if (knotPointsConnected) {
                    if (knotPointIsPrev) {
                        r.prevC.delta = 0;
                    } else {
                        r.nextC.delta = 0;
                    }

                    q.add(new RoutePair(r.getRoute(knotPointIsPrev ? RouteType.prevC : RouteType.nextC)));
                } else {
                    if (knotPointIsPrev) {
                        r.prevDC.delta = 0;
                    } else {
                        r.nextDC.delta = 0;
                    }

                    q.add(new RoutePair(r.getRoute(knotPointIsPrev ? RouteType.prevDC : RouteType.nextDC)));
                }
            }
            routeMap.put(k1.id, r);
        }
        ArrayList<VirtualPoint> leftGroup = paintState(knotPointsConnected ? Group.Left : Group.Left, knot,
                knotPoint1, cutPoint1, cutSegment2,
                routeMap);
        ArrayList<VirtualPoint> rightGroup = paintState(knotPointsConnected ? Group.Right : Group.Right,
                knot, cutPoint1, knotPoint1,
                cutSegment2, routeMap);

        for (RouteInfo r : routeMap.values()) {
            if (r.group == Group.Left) {
                r.assignGroup(leftGroup, rightGroup);
            } else {
                r.assignGroup(rightGroup, leftGroup);
            }
        }
        RouteInfo curr = routeMap.get(knotPoint2.id);
        RouteType prevCutSide = RouteType.None;
        if (curr.prevC.neighbor.id == cutPoint2.id) {
            prevCutSide = RouteType.prevC;
        } else {
            prevCutSide = RouteType.nextC;
        }

        Route endRoute = curr.getRoute(prevCutSide);
        RouteInfo uParent = null;
        Route u = null;

        while (settled.size() < numPoints * 4 && endRoute.matches.size() < knotNumber) {
            if (q.size() == 0) {
                break;
            }
            u = q.poll().route;
            uParent = u.parent;

            if (settled.contains(u.routeId)) {
                continue;
            }

            settled.add(u.routeId);

            for (int i = 0; i < knotPoints.size(); i++) {
                RouteInfo v = routeMap.get(knotPoints.get(i).id);
                if (uParent.id == v.id) {
                    continue;
                }

                RouteType[] routes = new RouteType[] { RouteType.prevC, RouteType.prevDC, RouteType.nextC,
                        RouteType.nextDC };
                for (RouteType vRouteType : routes) {
                    Route vRoute = v.getRoute(vRouteType);
                    boolean isSettled = settled.contains(vRoute.routeId);
                    VirtualPoint neighbor = vRoute.neighbor;

                    boolean neighborInGroup = u.ourGroup.contains(neighbor);
                    if (neighborInGroup) {
                        int nIdx = u.ourGroup.indexOf(neighbor);
                        int vIdx = u.ourGroup.indexOf(v.node);
                        if (nIdx < vIdx) {
                            continue;
                        }
                    }
                    // if(knotPointsConnected && !(smallestCommonKnot.contains(neighbor) ||
                    // smallestCommonKnot.contains(v.node))){
                    // continue;
                    // }
                    if (u.delta == Double.MAX_VALUE) {
                        continue;
                    }

                    if (uParent.node.id == neighbor.id) {
                        continue;
                    }

                    if (v.id == cutPoint1.id && neighbor.id == knotPoint1.id) {
                        continue;
                    }
                    if (v.id == knotPoint1.id && neighbor.id == cutPoint1.id) {
                        continue;
                    }

                    boolean vIsConnected = vRouteType.isConnected;
                    boolean uIsConnected = u.routeType.isConnected;
                    if (neighbor.id == cutPoint2.id && v.id == knotPoint2.id) {
                        if (!uIsConnected || !vIsConnected) {
                            continue;
                        }
                    } else if (neighborInGroup) {
                        if (uIsConnected && !vIsConnected) {
                            continue;
                        }
                        if (!uIsConnected && vIsConnected) {
                            continue;
                        }
                    } else {

                        ArrayList<VirtualPoint> grp = u.otherGroup;
                        VirtualPoint knotPoint = grp.get(0);
                        int knotPointIdx = 0;
                        if (!(knotPoint1.id == knotPoint.id || knotPoint2.id == knotPoint.id)) {
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
                            continue;
                        }
                        if (!between && !vIsConnected) {
                            continue;
                        }
                        if (between && vIsConnected && uIsConnected) {
                            continue;
                        }
                    }

                    Segment acrossSeg = neighbor.getSegment(uParent.node);
                    Segment cutSeg = neighbor.getSegment(v.node);
                    if (knot.hasSegmentManifold(acrossSeg)) {
                        continue;
                    }
                    if (u.cuts.contains(cutSeg)) {
                        continue;
                    }

                    double edgeDistance = acrossSeg.distance;
                    double cutDistance = cutSeg.distance;

                    double newDistancePrevNeighbor = u.delta + edgeDistance - cutDistance;

                    long startTimeIxdar = System.currentTimeMillis();
                    if (newDistancePrevNeighbor < vRoute.delta) {
                        v.updateRoute(newDistancePrevNeighbor, uParent.node, vRouteType, u.routeType, u, settled.size(),
                                knot.id);
                        if (isSettled && !cutSeg.equals(cutSegment2)) {

                            settled.remove(vRoute.routeId);
                            isSettled = false;
                        }

                    }
                    long endTimeIxdar = System.currentTimeMillis() - startTimeIxdar;
                    profileTimeIxdar += endTimeIxdar;
                    if (!isSettled && !cutSeg.equals(cutSegment2)) {
                        RoutePair routePair = new RoutePair(vRoute);
                        q.add(routePair);
                    }

                }
            }
        }
        return routeMap;

    }

    public static ArrayList<VirtualPoint> paintState(Group group, Knot knot, VirtualPoint knotPoint,
            VirtualPoint cutPoint, Segment cutSegment,
            HashMap<Integer, RouteInfo> routeInfo) {
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