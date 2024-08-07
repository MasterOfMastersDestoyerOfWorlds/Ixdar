package shell.cuts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

import shell.BalanceMap;
import shell.Shell;
import shell.enums.Group;
import shell.enums.RouteType;
import shell.enums.State;
import shell.exceptions.BalancerException;
import shell.exceptions.MultipleCyclesFoundException;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.route.Route;
import shell.route.RouteInfo;
import shell.route.RoutePair;

public class InternalPathEngine {
    Shell shell;
    CutEngine cutEngine;

    public InternalPathEngine(Shell shell, CutEngine cutEngine) {
        this.shell = shell;
        this.cutEngine = cutEngine;
    }

    public CutMatchList calculateInternalPathLength(
            VirtualPoint knotPoint1, VirtualPoint cutPoint1, VirtualPoint external1,
            VirtualPoint knotPoint2, VirtualPoint cutPoint2, VirtualPoint external2,
            Knot knot, BalanceMap balanceMap, CutInfo c, boolean knotPointsConnected)
            throws SegmentBalanceException, BalancerException {
        Segment cutSegment1 = knotPoint1.getClosestSegment(cutPoint1, null);
        Segment cutSegment2 = knotPoint2.getClosestSegment(cutPoint2, null);
        SegmentBalanceException sbe = new SegmentBalanceException(shell, null,
                new CutInfo(shell, knotPoint1, cutPoint1, cutSegment1, external1,
                        knotPoint2, cutPoint2, cutSegment2, external2, knot,
                        balanceMap));

        shell.buff.add("recutting knot: " + knot);

        shell.buff.add(
                "knotPoint1: " + knotPoint1 + " external1: " + external1);
        shell.buff.add(
                "knotPoint2: " + knotPoint2 + " external2: " + external2);
        shell.buff.add(
                "cutPointA: " + cutPoint1 + " cutPointB: " + cutPoint2);
        shell.buff.add(
                "flatKnots: " + cutEngine.flatKnots);

        HashMap<Integer, RouteInfo> routeMap = ixdar(knotPoint1, cutPoint1, external1, knotPoint2, cutPoint2, external2,
                knot, knotPointsConnected, cutSegment1, cutSegment2, -1, -1, RouteType.None);
        // now we build the route back to the start from knotPoint2

        RouteInfo curr = routeMap.get(knotPoint2.id);
        ArrayList<Segment> cutSegments = new ArrayList<>();
        ArrayList<Segment> matchSegments = new ArrayList<>();
        RouteType prevCutSide = RouteType.None;
        if (curr.prevC.neighbor.id == cutPoint2.id) {
            prevCutSide = RouteType.prevC;
        } else {
            prevCutSide = RouteType.nextC;
        }
        int totalIter = 0;
        while (curr.id != cutPoint1.id && totalIter < knot.size()) {
            Segment matchSegment = null, cutSegment = null;
            Route route = curr.getRoute(prevCutSide);
            if (route.ancestor == null) {
                CutMatchList cutMatchList = new CutMatchList(shell, sbe, knot);
                throw new SegmentBalanceException(shell, cutMatchList, c);
            }
            matchSegment = route.neighbor.getClosestSegment(route.ancestor, null);
            cutSegment = route.neighbor.getClosestSegment(curr.node, null);
            prevCutSide = route.ancestorRouteType;
            curr = routeMap.get(route.ancestor.id);
            matchSegments.add(matchSegment);
            cutSegments.add(cutSegment);
            totalIter++;
        }
        // TODO: need to check if the cut match list produces a cycle and throw a
        // Segment Balance Exception

        ArrayList<VirtualPoint> knotPoints = knot.knotPointsFlattened;
        DisjointUnionSets unionSet = new DisjointUnionSets(knotPoints);
        for (Segment s : matchSegments) {
            unionSet.union(s.first.id, s.last.id);
        }
        for (Segment s : knot.manifoldSegments) {
            if (!cutSegments.contains(s)) {
                unionSet.union(s.first.id, s.last.id);
            }
        }
        cutSegments.remove(cutSegment1);
        cutSegments.remove(cutSegment2);
        CutMatchList cutMatchList = new CutMatchList(shell, sbe, knot);
        try {
            cutMatchList.addLists(cutSegments, matchSegments, knot, "InternalPathEngine");
        } catch (SegmentBalanceException be) {
            throw be;
        }

        if (unionSet.find(cutPoint1.id) != unionSet.find(cutPoint2.id)) {
            // Multiple Cycles found!
            throw new MultipleCyclesFoundException(shell, cutMatchList, matchSegments, cutSegments, c);
        }
        return cutMatchList;
        // if neither orphan is on the top level, find their minimal knot in common and
        // recut it with the external that matched to the knot and its still matched
        // neighbor

    }

    public HashMap<Integer, RouteInfo> ixdar(VirtualPoint knotPoint1, VirtualPoint cutPoint1, VirtualPoint external1,
            VirtualPoint knotPoint2, VirtualPoint cutPoint2, VirtualPoint external2,
            Knot knot, boolean knotPointsConnected, Segment cutSegment1, Segment cutSegment2, int steps,
            int sourcePoint, RouteType routeType) {
        /*
         * THE SWORD OF ISKANDAR
         * function Dijkstra(Graph, source):
         * 
         * for each vertex v in Graph.Vertices:
         * dist[v] ← INFINITY
         * prev[v] ← UNDEFINED
         * add v to Q
         * dist[source] ← 0
         * 
         * while Q is not empty:
         * u ← vertex in Q with minimum dist[u]
         * remove u from Q
         * 
         * for each neighbor v of u still in Q:
         * alt ← dist[u] + Graph.Edges(u, v)
         * if alt < dist[v]:
         * dist[v] ← alt
         * prev[v] ← u
         * 
         * return dist[], prev[]
         * 
         * We also want to mark each node as either left side (on the same path in the
         * knot as cutpoint1 and knotpoint2) or right side (on the same path in the knot
         * as cutpoint2 and knotpoint1)
         * 
         * so
         * every node should have two routes into the node, signifiged by matching the
         * previous cutpoint to one of its two neighbors and subtract that distance
         * (node to neighbor) from its min dist to arrive at the node
         * 
         * the end cutpoint,(by convention lets just say it's cutpoint2) must be arrived
         * at from the left side (one of the nodes in the route from cutpoint1 to
         * knotpoint2) and by cutting its neighbor that is knotpoint2, i.e. cutting its
         * other neighbor could only lead to the neighbors other neighbor
         * 
         * in order to find the optimal cut match we perform this modified djikstras and
         * then back track from cutpoint2 to cutpoint 1, so we need to store along with
         * the previous point and distance to arrive, the neighbor that was matched to,
         * and wether it is in the right or left set (should store this as two lists of
         * left/right points as well as on a per node basis)
         * 
         * I actually dont think that left/ right matters because whenever you make a
         * hole and match across to the other of the sides,
         * all of the points from the new cutpoint to the closest knot point switch
         * sides effectively. So we should really jsut focus on plugging the hole formed
         * by cutpoint2 and knotpoint2
         */
        ArrayList<VirtualPoint> knotPoints = knot.knotPointsFlattened;
        HashMap<Integer, RouteInfo> routeMap = new HashMap<>();
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
                    r.update(0, null, null, knotPointIsPrev ? RouteType.prevC : RouteType.nextC,
                            RouteType.None);
                    if (knotPointIsPrev) {
                        r.prevC.delta = 0;
                    } else {
                        r.nextC.delta = 0;
                    }

                    q.add(new RoutePair(r.getRoute(knotPointIsPrev ? RouteType.prevC : RouteType.nextC)));
                } else {
                    r.update(0, null, null, knotPointIsPrev ? RouteType.prevDC : RouteType.nextDC,
                            RouteType.None);
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
        ArrayList<VirtualPoint> leftGroup = paintState(State.toKP1, knotPointsConnected ? Group.Left : Group.Left, knot,
                knotPoint1, cutPoint1, cutSegment2,
                routeMap);
        ArrayList<VirtualPoint> rightGroup = paintState(State.toCP1, knotPointsConnected ? Group.Right : Group.None,
                knot, cutPoint1, knotPoint1,
                cutSegment2, routeMap);
        ArrayList<VirtualPoint> tmp = paintState(State.toKP2, knotPointsConnected ? Group.None : Group.Right, knot,
                knotPoint2, cutPoint2,
                cutSegment1, routeMap);
        if (rightGroup.size() == 0) {
            rightGroup = tmp;
        }
        paintState(State.toCP2, knotPointsConnected ? Group.None : Group.None, knot, cutPoint2, knotPoint2, cutSegment1,
                routeMap);

        for (RouteInfo r : routeMap.values()) {
            if (r.group == Group.Left) {
                r.assignGroup(leftGroup, rightGroup);
            } else {
                r.assignGroup(rightGroup, leftGroup);
            }
        }

        RouteInfo uParent = null;
        Route u = null;
        boolean foundSourcePoint = false;
        while (settled.size() != numPoints * 4) {
            if (steps != -1 && settled.size() == steps) {
                assert sourcePoint == uParent.id : "last layer node was: " + uParent.id + " expected: " + sourcePoint;
                assert u.routeType == routeType
                        : "last layer node route type was: " + u.routeType + " expected: " + routeType;
                foundSourcePoint = true;
                break;
            }
            if (steps == -1 && sourcePoint != -1 && uParent != null && uParent.id == sourcePoint
                    && u.routeType == routeType) {
                foundSourcePoint = true;
                break;
            }
            // Terminating condition check when
            // the priority queue is empty, return
            if (q.isEmpty())
                break;

            // Removing the minimum distance node
            // from the priority queue
            u = q.remove().route;
            uParent = u.parent;

            // Adding the node whose distance is
            // finalized
            if (settled.contains(u.routeId)) {
                continue;
            }

            settled.add(u.routeId);
            // All the neighbors of v
            for (int i = 0; i < knotPoints.size(); i++) {
                RouteInfo v = routeMap.get(knotPoints.get(i).id);
                RouteType[] routes = new RouteType[] { RouteType.prevC, RouteType.prevDC, RouteType.nextC,
                        RouteType.nextDC };
                if(uParent.id == 16 && u.routeType == RouteType.prevDC && v.id == 14){
                    float z =0;
                }
                for (RouteType vRouteType : routes) {
                    Route vRoute = v.getRoute(vRouteType);
                    boolean isNotSettled = !settled.contains(vRoute.routeId);
                    boolean canRouteToExit = isNotSettled || v.id == knotPoint2.id;

                    // TODO: Generate an exception if we update prevC and prevDC or nextC and nextDC
                    // from the same uRoute
                    if (canRouteToExit) {

                        VirtualPoint neighbor = vRoute.neighbor;
                        RouteInfo n = routeMap.get(neighbor.id);
                        Segment acrossSeg = neighbor.getClosestSegment(uParent.node, null);
                        Segment cutSeg = neighbor.getClosestSegment(v.node, null);

                        boolean vIsConnected = vRouteType.isConnected();

                        if (u.delta == Double.MAX_VALUE) {
                            continue;
                        }
                        boolean uIsConnected = u.routeType.isConnected();
                        boolean neighborInGroup = u.ourGroup.contains(neighbor);
                        Route nRoute = n.getRoute(vRouteType.oppositeRoute());
                        if (v.id == cutPoint1.id && neighbor.id == knotPoint1.id) {
                            continue;
                        }
                        if (v.id == knotPoint1.id && neighbor.id == cutPoint1.id) {
                            continue;
                        }
                        if (v.id == cutPoint2.id && neighbor.id == knotPoint2.id) {
                            continue;
                        }
                        if (uParent.id == knotPoint2.id && u.neighbor.id == cutPoint2.id) {
                            continue;
                        }
                        if (u.cuts.contains(cutSeg)) {
                            continue;
                        }
                        if (!isNotSettled && neighbor.id != cutPoint2.id) {
                            continue;
                        }
                        if (neighborInGroup) {
                            int nIdx = u.ourGroup.indexOf(neighbor);
                            int vIdx = u.ourGroup.indexOf(v.node);
                            if (nIdx < vIdx) {
                                continue;
                            }
                        }
                        // what you want for the next one to be "connected" is for the states to be
                        // opposite of each other
                        // i.e. one of the u or neighbor states is a pointing toward a knot point and
                        // the other toward a cut point
                        boolean skip = false;
                        if (!skip) {
                            if (neighbor.id == cutPoint2.id && v.id == knotPoint2.id) {
                                if (!uIsConnected || !vIsConnected) {
                                    continue;
                                }
                            } else if (!u.ourGroup.contains(neighbor)) {
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
                            } else {
                                if (uIsConnected && !vIsConnected) {
                                    continue;
                                }
                                if (!uIsConnected && vIsConnected) {
                                    continue;
                                }

                                if (!uIsConnected && !n.getOtherState(nRoute.state).isKnot()
                                        && vIsConnected) {
                                    continue;
                                }
                                if (uIsConnected
                                        && ((!u.state.isKnot() && !n.getOtherState(nRoute.state).isKnot())
                                                || (u.state.isKnot()
                                                        && n.getOtherState(nRoute.state).isKnot()))
                                        && vIsConnected) {
                                    continue;
                                }
                            }

                        }

                        if (!uParent.node.equals(neighbor)) {
                            // && !(neighbor.id != cutPoint2.id
                            // && u.getKnotState() == opposite(n.getOtherState(nRoute.state)))) {
                            if (!knot.hasSegment(acrossSeg)) {
                                double edgeDistance = acrossSeg.distance;
                                double cutDistance = cutSeg.distance;

                                double newDistancePrevNeighbor = u.delta + edgeDistance - cutDistance;
                                if (u.delta == Double.MAX_VALUE) {
                                    newDistancePrevNeighbor = Double.MAX_VALUE;
                                }
                                if (newDistancePrevNeighbor < v.delta) {
                                    v.update(newDistancePrevNeighbor, uParent.node, neighbor, vRouteType, u.routeType);
                                }
                                if (newDistancePrevNeighbor < vRoute.delta) {

                                    v.updateRoute(newDistancePrevNeighbor, uParent.node, vRouteType, u.routeType, u);
                                }
                            }

                        }
                        if (isNotSettled) {
                            RoutePair routePair = new RoutePair(vRoute);
                            q.add(routePair);
                        }
                    }
                }
                // Add the current node to the queue
            }
        }
        if(steps != -1 || sourcePoint != -1){
            assert foundSourcePoint : "SourcePoint: " + sourcePoint + " not Found";
        }
        return routeMap;

    }

    public ArrayList<VirtualPoint> paintState(State state, Group group, Knot knot, VirtualPoint knotPoint,
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
            if (marchDirection == 1) {
                r.prevC.state = state;
                r.prevDC.state = state;
                r.distFromPrevSource = totalIter;

            } else {
                r.nextC.state = state;
                r.nextDC.state = state;
                r.distFromNextSource = totalIter;
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

    class RouteComparator implements Comparator<RoutePair> {

        // Overriding compare()method of Comparator
        // for descending order of cgpa
        @Override
        public int compare(RoutePair o1, RoutePair o2) {
            return Double.compare(o1.delta, o2.delta);
        }
    }

    class DisjointUnionSets {
        HashMap<Integer, Integer> rank, parent;

        // Constructor
        public DisjointUnionSets(ArrayList<VirtualPoint> knotPoints) {
            rank = new HashMap<Integer, Integer>();
            parent = new HashMap<Integer, Integer>();
            for (int i = 0; i < knotPoints.size(); i++) {
                // Initially, all elements are in
                // their own set.
                int id = knotPoints.get(i).id;
                parent.put(id, id);
            }
        }

        // Returns representative of x's set
        int find(int x) {
            // Finds the representative of the set
            // that x is an element of
            if (parent.get(x) != x) {
                // if x is not the parent of itself
                // Then x is not the representative of
                // his set,
                parent.put(x, find(parent.get(x)));

                // so we recursively call Find on its parent
                // and move i's node directly under the
                // representative of this set
            }

            return parent.get(x);
        }

        // Unites the set that includes x and the set
        // that includes x
        void union(int x, int y) {
            // Find representatives of two sets
            int xRoot = find(x), yRoot = find(y);

            // Elements are in the same set, no need
            // to unite anything.
            if (xRoot == yRoot)
                return;

            // If x's rank is less than y's rank
            if (rank.getOrDefault(xRoot, 0) < rank.getOrDefault(yRoot, 0))

                // Then move x under y so that depth
                // of tree remains less
                parent.put(xRoot, yRoot);

            // Else if y's rank is less than x's rank
            else if (rank.getOrDefault(yRoot, 0) < rank.getOrDefault(xRoot, 0))

                // Then move y under x so that depth of
                // tree remains less
                parent.put(yRoot, xRoot);

            else // if ranks are the same
            {
                // Then move y under x (doesn't matter
                // which one goes where)
                parent.put(yRoot, xRoot);

                // And increment the result tree's
                // rank by 1
                rank.put(xRoot, rank.getOrDefault(xRoot, 0) + 1);
            }
        }
    }
}