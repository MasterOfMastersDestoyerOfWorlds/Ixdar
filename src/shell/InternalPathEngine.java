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
            Knot knot, BalanceMap balanceMap) throws SegmentBalanceException, BalancerException {

        SegmentBalanceException sbe = new SegmentBalanceException(shell, null,
                new CutInfo(shell, knotPoint1, cutPoint1, knotPoint1.getClosestSegment(cutPoint1, null), external1,
                        knotPoint2, cutPoint2, knotPoint2.getClosestSegment(cutPoint2, null), external2, knot,
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
        HashMap<Integer, RouteInfo> routeInfo = new HashMap<>();
        PriorityQueue<RouteInfo> q = new PriorityQueue<RouteInfo>();
        Set<Integer> settled = new HashSet<Integer>();
        int numPoints = knot.size();

        for (int i = 0; i < numPoints; i++) {
            VirtualPoint k1 = knotPoints.get(i);
            RouteInfo r = new RouteInfo(k1, Double.MAX_VALUE, true, null, null);
            if (k1.equals(cutPoint1)) {
                r.update(0, null, null);
            }
            routeInfo.put(k1.id, r);
        }

        int idx = knot.knotPoints.indexOf(cutPoint2);
        int idx2 = knot.knotPoints.indexOf(knotPoint2);
        int marchDirection = idx2 - idx < 0 ? -1 : 1;
        if (idx == 0 && idx2 == knot.knotPoints.size() - 1) {
            marchDirection = -1;
        }
        if (idx2 == 0 && idx == knot.knotPoints.size() - 1) {
            marchDirection = 1;
        }
        marchDirection = -marchDirection;
        int totalIter = 0;
        ArrayList<VirtualPoint> pointsTowardCut = new ArrayList<>();
        while (true) {
            VirtualPoint k1 = knot.knotPoints.get(idx);
            if (k1.equals(cutPoint1)) {
                break;
            }
            routeInfo.get(k1.id).isLeft = false;
            routeInfo.get(k1.id).pointsTowardCut = new ArrayList<>(pointsTowardCut);
            pointsTowardCut.add(k1);

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

        idx = knot.knotPoints.indexOf(cutPoint1);
        idx2 = knot.knotPoints.indexOf(knotPoint1);
        marchDirection = idx2 - idx < 0 ? -1 : 1;
        if (idx == 0 && idx2 == knot.knotPoints.size() - 1) {
            marchDirection = -1;
        }
        if (idx2 == 0 && idx == knot.knotPoints.size() - 1) {
            marchDirection = 1;
        }
        marchDirection = -marchDirection;
        totalIter = 0;
        pointsTowardCut = new ArrayList<>();
        while (true) {
            VirtualPoint k1 = knot.knotPoints.get(idx);
            if (k1.equals(cutPoint2)) {
                break;
            }
            routeInfo.get(k1.id).isLeft = true;
            routeInfo.get(k1.id).pointsTowardCut = new ArrayList<>(pointsTowardCut);
            pointsTowardCut.add(k1);

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

        q.add(routeInfo.get(cutPoint1.id));

        if (cutPoint1.id == 0 && cutPoint2.id == 4 && knotPoint1.id == 1 && knotPoint2.id == 9) {
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

            double edgeDistance = -1;
            double newDistancePrevNeighbor = -1;
            double newDistanceNextNeighbor = -1;
            // All the neighbors of v
            for (int i = 0; i < knotPoints.size(); i++) {
                RouteInfo v = routeInfo.get(knotPoints.get(i).id);
                int nodeId2 = u.id;
                int nodeId = v.id;
                // If current node hasn't already been processed
                if (!settled.contains(v.id)) {
                    if (v.node.equals(knotPoint2)) {
                        if (!u.node.equals(cutPoint2)) {
                            Segment acrossSeg = cutPoint2.getClosestSegment(u.node, null);

                            if (!knot.hasSegment(acrossSeg)) {
                                edgeDistance = acrossSeg.distance;
                                Segment cutSeg = cutPoint2.getClosestSegment(v.node, null);
                                double cutDistance = cutSeg.distance;
                                newDistancePrevNeighbor = u.delta + edgeDistance - cutDistance;
                                if (newDistancePrevNeighbor < v.delta) {
                                    v.update(newDistancePrevNeighbor, u.node, cutPoint2);
                                }
                            }
                        }
                    } else {
                        VirtualPoint prevNeighbor = knot.getPrev(v.node);
                        if (!settled.contains(prevNeighbor.id) && !u.node.equals(prevNeighbor)
                                && !prevNeighbor.equals(cutPoint2)) {
                            Segment acrossSeg = prevNeighbor.getClosestSegment(u.node, null);
                            Segment cutSeg = prevNeighbor.getClosestSegment(v.node, null);
                            if (!knot.hasSegment(acrossSeg)
                                    && !(cutSeg.contains(knotPoint2) && cutSeg.contains(cutPoint2))) {
                                edgeDistance = acrossSeg.distance;
                                double cutDistance = cutSeg.distance;
                                newDistancePrevNeighbor = u.delta + edgeDistance - cutDistance;
                                if (newDistancePrevNeighbor < v.delta) {
                                    v.update(newDistancePrevNeighbor, u.node, prevNeighbor);
                                }
                            }

                        }

                        VirtualPoint nextNeighbor = knot.getNext(v.node);

                        if (!settled.contains(nextNeighbor.id)
                                && !u.node.equals(nextNeighbor)
                                && !nextNeighbor.equals(cutPoint2)) {
                            Segment acrossSeg = nextNeighbor.getClosestSegment(u.node, null);

                            Segment cutSeg = nextNeighbor.getClosestSegment(v.node, null);
                            if (!knot.hasSegment(acrossSeg)
                                    && !(cutSeg.contains(knotPoint2) && cutSeg.contains(cutPoint2))) {
                                edgeDistance = acrossSeg.distance;

                                double cutDistance = cutSeg.distance;
                                newDistanceNextNeighbor = u.delta + edgeDistance - cutDistance;
                                if (newDistanceNextNeighbor < v.delta) {
                                    v.update(newDistanceNextNeighbor, u.node, nextNeighbor);
                                }
                            }

                        }
                    }

                    // Add the current node to the queue
                    q.add(v);
                }
            }
        }

        // now we build the route back to the start from knotPoint2

        RouteInfo curr = routeInfo.get(knotPoint2.id);
        ArrayList<Segment> cutSegments = new ArrayList<>();
        ArrayList<Segment> matchSegments = new ArrayList<>();
        while (curr.id != cutPoint1.id) {
            Segment matchSegment = curr.matchedNeighbor.getClosestSegment(curr.previous, null);
            matchSegments.add(matchSegment);
            Segment cutSegment = curr.matchedNeighbor.getClosestSegment(curr.node, null);
            cutSegments.add(cutSegment);
            curr = routeInfo.get(curr.previous.id);
        }

        float z = 0;

        CutMatchList cutMatchList = new CutMatchList(shell, sbe, knot);
        try {
            cutMatchList.addLists(cutSegments, matchSegments, knot, "InternalPathEngine");
        } catch (SegmentBalanceException be) {
            throw be;
        }
        return cutMatchList;
        // if neither orphan is on the top level, find their minimal knot in common and
        // recut it with the external that matched to the knot and its still matched
        // neighbor

    }

    public class RouteInfo implements Comparable<RouteInfo> {
        public ArrayList pointsTowardCut;
        public double delta;
        public boolean isLeft;
        public VirtualPoint previous;
        public VirtualPoint matchedNeighbor;
        public VirtualPoint node;
        public int id;

        public RouteInfo(VirtualPoint node, double delta, boolean isLeft, VirtualPoint previous,
                VirtualPoint matchedNeighbor) {
            this.node = node;
            this.id = node.id;
            this.delta = delta;
            this.isLeft = isLeft;
            this.previous = previous;
            this.matchedNeighbor = matchedNeighbor;
        }

        public void update(double delta, VirtualPoint previous, VirtualPoint matchedNeighbor) {
            if (delta < this.delta) {
                this.delta = delta;
                this.previous = previous;
                this.matchedNeighbor = matchedNeighbor;
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

    }
}