package shell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.commons.math3.util.Pair;

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

        if (cutPoint1.id == 3 && knotPoint1.id == 7 && cutPoint2.id == 5 && knotPoint2.id == 2) {
            float z = 0;
        }
        HashMap<Integer, RouteInfo> routeMap = ixdar(knotPoint1, cutPoint1, external1, knotPoint2, cutPoint2, external2,
                knot, knotPointsConnected, cutSegment1, cutSegment2, -1);
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
            if (curr.matchedNeighbor == null) {
                float z = 0;
            }
            Segment matchSegment = null, cutSegment = null;
            Route route = curr.getRoute(prevCutSide);
            if (route.ancestor == null) {
                float z = 0;

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
            if (s == null) {
                float z = 0;
            }
            unionSet.union(s.first.id, s.last.id);
        }
        for (Segment s : knot.manifoldSegments) {
            if (!cutSegments.contains(s)) {
                unionSet.union(s.first.id, s.last.id);
            }
        }
        float z = 0;
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
            Knot knot, boolean knotPointsConnected, Segment cutSegment1, Segment cutSegment2, int steps) {
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
        PriorityQueue<RouteInfo> q = new PriorityQueue<RouteInfo>();
        Set<Integer> settled = new HashSet<Integer>();
        int numPoints = knot.size();

        for (int i = 0; i < numPoints; i++) {
            VirtualPoint k1 = knotPoints.get(i);
            VirtualPoint nextNeighbor = knot.getNext(k1);
            VirtualPoint prevNeighbor = knot.getPrev(k1);
            RouteInfo r = new RouteInfo(k1, Double.MAX_VALUE, prevNeighbor, nextNeighbor, null, null);
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
                } else {
                    r.update(0, null, null, knotPointIsPrev ? RouteType.prevDC : RouteType.nextDC,
                            RouteType.None);
                    if (knotPointIsPrev) {
                        r.prevDC.delta = 0;
                    } else {
                        r.nextDC.delta = 0;
                    }

                }
            }
            routeMap.put(k1.id, r);
        }

        paintState(State.toKP1, knotPointsConnected ? Group.Left : Group.Left, knot, knotPoint1, cutPoint1, cutSegment2,
                routeMap);
        paintState(State.toCP1, knotPointsConnected ? Group.Right : Group.None, knot, cutPoint1, knotPoint1,
                cutSegment2, routeMap);
        paintState(State.toKP2, knotPointsConnected ? Group.None : Group.Right, knot, knotPoint2, cutPoint2,
                cutSegment1, routeMap);
        paintState(State.toCP2, knotPointsConnected ? Group.None : Group.None, knot, cutPoint2, knotPoint2, cutSegment1,
                routeMap);

        q.add(routeMap.get(cutPoint1.id));

        if (cutPoint1.id == 0 && knotPoint1.id == 20 && cutPoint2.id == 7 && knotPoint2.id == 6) {
            float z = 0;
        }
        VirtualPoint cutPoint2PrevNeighbor = routeMap.get(cutPoint2.id).prevC.neighbor;
        VirtualPoint cutPoint2NextNeighbor = routeMap.get(cutPoint2.id).nextC.neighbor;
        while (settled.size() != numPoints) {
            if(steps != -1 && settled.size() == steps){
                break;
            }
            // Terminating condition check when
            // the priority queue is empty, return
            if (q.isEmpty())
                break;

            // Removing the minimum distance node
            // from the priority queue
            RouteInfo u = q.remove();

            // Adding the node whose distance is
            // finalized
            if (settled.contains(u.id)) {
                continue;
            }

            settled.add(u.id);

            // All the neighbors of v
            for (int i = 0; i < knotPoints.size(); i++) {
                RouteInfo v = routeMap.get(knotPoints.get(i).id);
                int uNode = u.id;
                int vNode = v.id;
                boolean isNotSettled = !settled.contains(v.id);
                boolean canRouteToExit = isNotSettled || v.id == knotPoint2.id;
                if (canRouteToExit) {
                    RouteType[] routes = new RouteType[] { RouteType.prevC, RouteType.prevDC, RouteType.nextC, RouteType.nextDC };
                    for (RouteType vRouteType : routes) {
                        for (RouteType uRouteType : routes) {
                            Route vRoute = v.getRoute(vRouteType);
                            Route uRoute = u.getRoute(uRouteType);

                            VirtualPoint neighbor = vRoute.neighbor;
                            RouteInfo n = routeMap.get(neighbor.id);
                            Route nRoute = n.getRoute(oppositeRoute(vRouteType));
                            if (v.id == cutPoint2.id && neighbor.id == knotPoint2.id) {
                                continue;
                            }
                            if (!isNotSettled && neighbor.id != cutPoint2.id) {
                                continue;
                            }
                            if (n.group == u.group
                                    && n.distFromPrevSource < u.distFromPrevSource
                                    && isNext(vRouteType)) {
                                continue;
                            }
                            if (n.group == u.group
                                    && n.distFromPrevSource > u.distFromPrevSource
                                    && isPrev(vRouteType)) {
                                continue;
                            }
                            // what you want for the next one to be "connected" is for the states to be
                            // opposite of each other
                            // i.e. one of the u or neighbor states is a pointing toward a knot point and
                            // the other toward a cut point
                            boolean skip = false;
                            if (!skip) {
                                if (!(neighbor.id == cutPoint2.id && isConnected(uRouteType))) {
                                    if (n.group != u.group) {
                                        if (!isConnected(uRouteType) && isKnot(n.getOtherState(nRoute.state))
                                                && !isConnected(vRouteType)) {
                                            continue;
                                        }
                                        if (isConnected(uRouteType) && !isKnot(n.getOtherState(nRoute.state))
                                                && isConnected(vRouteType)) {
                                            continue;
                                        }
                                    }
                                    if (n.group == u.group) {

                                        if (!isConnected(uRouteType) && !isKnot(n.getOtherState(nRoute.state))
                                                && isConnected(vRouteType)) {
                                            continue;
                                        }
                                        if (isConnected(uRouteType)
                                                && ((!isKnot(uRoute.state) && !isKnot(n.getOtherState(nRoute.state)))
                                                        || (isKnot(uRoute.state)
                                                                && isKnot(n.getOtherState(nRoute.state))))
                                                && isConnected(vRouteType)) {
                                            continue;
                                        }
                                    }
                                }
                            }

                            if (!(settled.contains(neighbor.id) && neighbor != cutPoint2)
                                    && !u.node.equals(neighbor)
                                    && !(neighbor.id != cutPoint2.id
                                            && u.getKnotState() == opposite(n.getOtherState(nRoute.state)))) {
                                Segment acrossSeg = neighbor.getClosestSegment(u.node, null);
                                Segment cutSeg = neighbor.getClosestSegment(v.node, null);
                                if (!knot.hasSegment(acrossSeg)) {
                                    double edgeDistance = acrossSeg.distance;
                                    double cutDistance = cutSeg.distance;

                                    double newDistancePrevNeighbor = uRoute.delta + edgeDistance - cutDistance;
                                    if (uRoute.delta == Double.MAX_VALUE) {
                                        newDistancePrevNeighbor = Double.MAX_VALUE;
                                    }
                                    if (newDistancePrevNeighbor < v.delta) {
                                        v.update(newDistancePrevNeighbor, u.node, neighbor, vRouteType, uRouteType);
                                    }
                                    if (newDistancePrevNeighbor < vRoute.delta) {

                                        v.updateRoute(newDistancePrevNeighbor, u.node, vRouteType, uRouteType);
                                    }
                                }

                            }
                        }
                    }

                    // Add the current node to the queue
                    if (isNotSettled) {
                        q.add(v);
                    }
                }
            }
        }
        return routeMap;
    }

    public boolean isConnected(RouteType rType) {
        switch (rType) {
            case prevC:
                return true;
            case nextC:
                return true;
            case prevDC:
                return false;
            case nextDC:
                return false;
            case None:
                return false;

        }
        return false;
    }

    public boolean isNext(RouteType rType) {
        switch (rType) {
            case prevC:
                return false;
            case nextC:
                return true;
            case prevDC:
                return false;
            case nextDC:
                return true;
            case None:
                return false;

        }
        return false;
    }

    public boolean isPrev(RouteType rType) {
        switch (rType) {
            case prevC:
                return true;
            case nextC:
                return false;
            case prevDC:
                return true;
            case nextDC:
                return false;
            case None:
                return false;

        }
        return false;
    }

    public State opposite(State state) {
        switch (state) {
            case toKP1:
                return State.toKP2;
            case toKP2:
                return State.toKP1;
            case toCP1:
                return State.toCP2;
            case toCP2:
                return State.toCP1;
            default:
                return State.None;
        }

    }

    public RouteType oppositeRoute(RouteType state) {
        switch (state) {
            case prevC:
                return RouteType.nextC;
            case nextC:
                return RouteType.prevC;
            case prevDC:
                return RouteType.nextDC;
            case nextDC:
                return RouteType.prevDC;
            default:
                return RouteType.None;
        }

    }

    public boolean isKnot(State state) {
        switch (state) {
            case toKP1:
                return true;
            case toKP2:
                return true;
            case toCP1:
                return false;
            case toCP2:
                return false;
            default:
                return false;
        }

    }

    public void paintState(State state, Group group, Knot knot, VirtualPoint knotPoint,
            VirtualPoint cutPoint, Segment cutSegment,
            HashMap<Integer, RouteInfo> routeInfo) {

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
    }

    public enum State {
        toKP1,
        toCP1,
        toKP2,
        toCP2,
        None,

    }

    public enum Group {
        Left,
        Right,
        None,

    }

    public enum RouteType {
        prevDC,
        prevC,
        nextDC,
        nextC,
        None;
    }

    public class Route {
        public RouteType routeType;
        public RouteType ancestorRouteType = RouteType.None;
        public State state = State.None;
        public VirtualPoint neighbor;
        public double delta;
        public VirtualPoint ancestor;

        public Route(RouteType routeType, double delta, VirtualPoint neighbor) {
            this.routeType = routeType;
            this.delta = delta;
            this.neighbor = neighbor;
        }

    }

    public class RouteInfo implements Comparable<RouteInfo> {

        // need to ad a concept of teh winding number to this model, which would flip
        // the state of the prev/ next depending on weather the winding number is even
        // or odd
        //

        public Group group;
        public Route prevC;
        public Route prevDC;
        public Route nextC;
        public Route nextDC;
        int distFromPrevSource;
        int distFromNextSource;

        public double delta;
        public RouteType minRoute = RouteType.None;
        public RouteType ancestorRouteType;
        public VirtualPoint ancestor;
        public VirtualPoint matchedNeighbor;

        public VirtualPoint node;
        public int id;

        public RouteInfo(VirtualPoint node, double delta, VirtualPoint prevNeighbor, VirtualPoint nextNeighbor,
                VirtualPoint ancestor, VirtualPoint matchedNeighbor) {
            this.node = node;
            this.id = node.id;
            this.delta = delta;
            this.prevC = new Route(RouteType.prevC, Double.MAX_VALUE, prevNeighbor);
            this.nextC = new Route(RouteType.nextC, Double.MAX_VALUE, nextNeighbor);
            this.prevDC = new Route(RouteType.prevDC, Double.MAX_VALUE, prevNeighbor);
            this.nextDC = new Route(RouteType.prevDC, Double.MAX_VALUE, nextNeighbor);
            this.ancestor = ancestor;
            this.matchedNeighbor = matchedNeighbor;
        }

        public State getOtherState(State state) {
            if (prevC.state == state) {
                return nextC.state;
            } else {
                return prevC.state;
            }
        }

        public void update(double delta, VirtualPoint ancestor, VirtualPoint matchedNeighbor, RouteType routeType,
                RouteType ancestorRouteType) {
            if (delta < this.delta) {
                this.delta = delta;
                this.minRoute = routeType;
                this.ancestor = ancestor;
                this.matchedNeighbor = matchedNeighbor;
                this.ancestorRouteType = ancestorRouteType;
            }
        }

        public void updateRoute(double delta, VirtualPoint ancestor, RouteType routeType, RouteType ancestorRouteType) {
            Route route = getRoute(routeType);
            if (delta < route.delta) {
                route.delta = delta;
                route.ancestorRouteType = ancestorRouteType;
                route.ancestor = ancestor;
            }
        }

        public Route getRoute(RouteType routeType) {
            switch (routeType) {
                case prevC:
                    return prevC;
                case nextC:
                    return nextC;
                case prevDC:
                    return prevDC;
                case nextDC:
                    return nextDC;
                default:
                    return null;
            }
        }

        @Override
        public int compareTo(RouteInfo other) {
            if (this.delta < other.delta) {
                return -1;
            }

            if (this.delta > other.delta) {
                return 1;
            }

            return 0;
        }

        public State getKnotState() {
            if (prevC.state == State.toKP1 || prevC.state == State.toKP2) {
                return prevC.state;
            } else if (nextC.state == State.toKP1 || nextC.state == State.toKP2) {
                return nextC.state;
            }
            return State.None;
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