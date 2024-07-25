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
            Knot knot, BalanceMap balanceMap, CutInfo c) throws SegmentBalanceException, BalancerException {
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

        int smallestKnotIdA = shell.smallestKnotLookup[cutPoint1.id];
        int smallestKnotIdB = shell.smallestKnotLookup[cutPoint2.id];
        int smallestKnotIdKp1 = shell.smallestKnotLookup[knotPoint1.id];
        int smallestKnotIdKp2 = shell.smallestKnotLookup[knotPoint2.id];

        Knot topKnot = cutEngine.flatKnots.get(smallestKnotIdA);
        VirtualPoint topPoint = cutPoint1;
        VirtualPoint topKnotPoint = knotPoint1;

        Knot botKnot = cutEngine.flatKnots.get(smallestKnotIdB);
        VirtualPoint botPoint = cutPoint2;
        VirtualPoint botKnotPoint = knotPoint2;

        if (topKnot.size() < botKnot.size()) {
            topPoint = cutPoint2;
            topKnotPoint = knotPoint2;
            botPoint = cutPoint1;
            botKnotPoint = knotPoint1;
        }
        shell.buff.add("topPoint: " + topPoint);
        shell.buff.add("botPoint: " + botPoint);
        shell.buff.add("topKnotPoint: " + topKnotPoint);
        shell.buff.add("botKNotPoint: " + botKnotPoint);
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
                r.update(0, null, null, knotPointIsPrev ? RouteType.prevEven : RouteType.nextEven, RouteType.None);
                if (knotPointIsPrev) {
                    r.prevEven.delta = 0;
                } else {
                    r.nextEven.delta = 0;
                }
            }
            routeMap.put(k1.id, r);
        }

        paintState(State.toKP1, true, knot, knotPoint1, cutPoint1, cutSegment2, routeMap);
        paintState(State.toCP1, false, knot, cutPoint1, knotPoint1, cutSegment2, routeMap);
        paintState(State.toKP2, true, knot, knotPoint2, cutPoint2, cutSegment1, routeMap);
        paintState(State.toCP2, false, knot, cutPoint2, knotPoint2, cutSegment1, routeMap);

        q.add(routeMap.get(cutPoint1.id));

        if (cutPoint1.id == 15 && knotPoint1.id == 14 && cutPoint2.id == 5 && knotPoint2.id == 6) {
            float z = 0;
        }

        while (settled.size() != numPoints) {

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
                // If current node hasn't already been processed
                if (!settled.contains(v.id)) {
                    // we need to also keep track of the orientation of the cut on u's side,
                    // the rule is if the cut is on u's prev the nthe coloring from u.prevstate is
                    // compared to the matching neighbor's opposite direction from
                    // its cut.
                    // so if the neighbor is next compared to v, we look at the neighbors prevState
                    // and compare it to u's state
                    // state, it the neighbor is prev compared to v then we look at the neighbor's
                    // nextState and compare it to u's state,
                    // if the two states are equal then we know that we would form multiple cycles
                    // instead of just one.

                    // the first set is we attempt to compare against is u if it was cut and
                    // separated from its previous
                    RouteType[] routes = new RouteType[] { RouteType.prevEven, RouteType.nextEven };
                    for (RouteType vRouteType : routes) {
                        for (RouteType uRouteType : routes) {
                            Route vRoute = v.getRoute(vRouteType);
                            Route uRoute = u.getRoute(uRouteType);
                            VirtualPoint prevNeighbor = vRoute.neighbor;
                            Route prevRoute = routeMap.get(prevNeighbor.id).getRoute(vRouteType);
                            if (!settled.contains(prevNeighbor.id)
                                    && !u.node.equals(prevNeighbor)
                                    && !(uRoute.state == prevRoute.state)
                                    && !(prevNeighbor.id != cutPoint2.id
                                            && u.getKnotState() == opposite(prevRoute.state))) {
                                Segment acrossSeg = prevNeighbor.getClosestSegment(u.node, null);
                                Segment cutSeg = prevNeighbor.getClosestSegment(v.node, null);
                                if (!knot.hasSegment(acrossSeg)) {
                                    double edgeDistance = acrossSeg.distance;
                                    double cutDistance = cutSeg.distance;

                                    double newDistancePrevNeighbor = uRoute.delta + edgeDistance - cutDistance;
                                    if (uRoute.delta == Double.MAX_VALUE) {
                                        newDistancePrevNeighbor = Double.MAX_VALUE;
                                    }
                                    if (newDistancePrevNeighbor < v.delta) {
                                        v.update(newDistancePrevNeighbor, u.node, prevNeighbor, vRouteType, uRouteType);
                                    }
                                    if (newDistancePrevNeighbor < vRoute.delta) {

                                        v.updateRoute(newDistancePrevNeighbor, u.node, vRouteType, uRouteType);
                                    }
                                }

                            }
                        }
                    }

                    // Add the current node to the queue
                    q.add(v);
                }
            }
        }

        if (cutPoint1.id == 0 && knotPoint1.id == 2 && cutPoint2.id == 5 && knotPoint2.id == 6) {
            float z = 0;
        }
        // now we build the route back to the start from knotPoint2

        RouteInfo curr = routeMap.get(knotPoint2.id);
        ArrayList<Segment> cutSegments = new ArrayList<>();
        ArrayList<Segment> matchSegments = new ArrayList<>();

        RouteType prevCutSide = curr.prevEven.neighbor.id == cutPoint2.id ? RouteType.prevEven : RouteType.nextEven;
        while (curr.id != cutPoint1.id) {
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
        }
        // TODO: need to check if the cut match list produces a cycle and throw a
        // Segment Balance Exception
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
            throw new MultipleCyclesFoundException(shell, cutMatchList, c);
        }
        return cutMatchList;
        // if neither orphan is on the top level, find their minimal knot in common and
        // recut it with the external that matched to the knot and its still matched
        // neighbor

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

    public void paintState(State state, boolean isTowardKnotPoint, Knot knot, VirtualPoint knotPoint,
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
            if (marchDirection == 1) {
                r.prevEven.state = state;
                r.nextOdd.state = state;

            } else {
                r.nextEven.state = state;
                r.prevOdd.state = state;
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

    public enum RouteType {
        prevOdd,
        prevEven,
        nextOdd,
        nextEven,
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

        public Route prevOdd;
        public Route prevEven;
        public Route nextOdd;
        public Route nextEven;

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
            this.prevOdd = new Route(RouteType.prevOdd, Double.MAX_VALUE, prevNeighbor);
            this.prevEven = new Route(RouteType.prevEven, Double.MAX_VALUE, prevNeighbor);
            this.nextOdd = new Route(RouteType.nextOdd, Double.MAX_VALUE, nextNeighbor);
            this.nextEven = new Route(RouteType.nextEven, Double.MAX_VALUE, nextNeighbor);
            this.ancestor = ancestor;
            this.matchedNeighbor = matchedNeighbor;
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
                case prevEven:
                    return prevEven;
                case prevOdd:
                    return prevOdd;
                case nextEven:
                    return nextEven;
                case nextOdd:
                    return nextOdd;
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
            if (prevEven.state == State.toKP1 || prevEven.state == State.toKP2) {
                return prevEven.state;
            } else if (nextEven.state == State.toKP1 || nextEven.state == State.toKP2) {
                return nextEven.state;
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