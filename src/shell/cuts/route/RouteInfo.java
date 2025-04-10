package shell.cuts.route;

import java.util.ArrayList;
import java.util.HashMap;

import shell.cuts.CutInfo;
import shell.cuts.engines.InternalPathEngine;
import shell.cuts.enums.Group;
import shell.cuts.enums.RouteType;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Segment;
import shell.knot.VirtualPoint;

public class RouteInfo {

    // need to ad a concept of teh winding number to this model, which would flip
    // the state of the prev/ next depending on weather the winding number is even
    // or odd
    //
    public CutInfo c;
    public RouteMap parent;
    public Group group;
    public Route prevC;
    public Route prevDC;
    public Route nextC;
    public Route nextDC;
    public Route[] routes;
    public int distFromPrevSource;
    public int distFromNextSource;

    public VirtualPoint knotPoint1;
    public VirtualPoint knotPoint2;
    public VirtualPoint cutPoint1;
    public VirtualPoint cutPoint2;

    public VirtualPoint node;
    public int id;
    public int rotDist;

    public static int maxSettledSize;
    public static int maxPathLength;

    public RouteInfo(VirtualPoint node, double delta, VirtualPoint prevNeighbor, VirtualPoint nextNeighbor,
            VirtualPoint ancestor, VirtualPoint matchedNeighbor, VirtualPoint knotPoint1, VirtualPoint knotPoint2,
            VirtualPoint cutPoint1, VirtualPoint cutPoint2, RouteMap routeMap) {
        this.c = routeMap.c;
        this.parent = routeMap;
        this.node = node;
        this.id = node.id;
        this.prevC = new Route(RouteType.prevC, Double.MAX_VALUE, prevNeighbor, node.id, this);
        this.nextC = new Route(RouteType.nextC, Double.MAX_VALUE, nextNeighbor, node.id, this);
        this.prevDC = new Route(RouteType.prevDC, Double.MAX_VALUE, prevNeighbor, node.id, this);
        this.nextDC = new Route(RouteType.nextDC, Double.MAX_VALUE, nextNeighbor, node.id, this);
        this.knotPoint1 = knotPoint1;
        this.knotPoint2 = knotPoint2;
        this.cutPoint1 = cutPoint1;
        this.cutPoint2 = cutPoint2;
        routes = new Route[4];
        routes[RouteType.prevC.idx] = prevC;
        routes[RouteType.prevDC.idx] = prevDC;
        routes[RouteType.nextC.idx] = nextC;
        routes[RouteType.nextDC.idx] = nextDC;
    }

    public RouteInfo(RouteInfo routeInfoToCopy, VirtualPoint upperCutPoint, VirtualPoint upperKnotPoint, CutInfo c,
            RouteMap routeMap, ArrayList<Route> routesToCheck) {
        this.c = c;
        this.parent = routeMap;
        this.node = routeInfoToCopy.node;
        this.id = node.id;
        this.knotPoint1 = routeInfoToCopy.knotPoint1;
        this.knotPoint2 = upperKnotPoint;
        this.cutPoint1 = routeInfoToCopy.cutPoint1;
        this.cutPoint2 = upperCutPoint;
        this.prevC = new Route(routeInfoToCopy.prevC, upperCutPoint, upperKnotPoint, this, c, routesToCheck);
        this.nextC = new Route(routeInfoToCopy.nextC, upperCutPoint, upperKnotPoint, this, c, routesToCheck);
        this.prevDC = new Route(routeInfoToCopy.prevDC, upperCutPoint, upperKnotPoint, this, c, routesToCheck);
        this.nextDC = new Route(routeInfoToCopy.nextDC, upperCutPoint, upperKnotPoint, this, c, routesToCheck);
        routes = new Route[4];
        routes[RouteType.prevC.idx] = prevC;
        routes[RouteType.prevDC.idx] = prevDC;
        routes[RouteType.nextC.idx] = nextC;
        routes[RouteType.nextDC.idx] = nextDC;
    }

    private RouteInfo() {
    }

    public RouteInfo copy(RouteMap parent) {
        RouteInfo ri = new RouteInfo();
        ri.c = parent.c;
        ri.parent = parent;
        ri.group = group;
        ri.prevC = prevC.copy(this);
        ri.prevDC = prevDC.copy(this);
        ri.nextC = nextC.copy(this);
        ri.nextDC = nextDC.copy(this);
        ri.routes = new Route[] { ri.prevC, ri.prevDC, ri.nextC, ri.nextDC };
        ri.distFromPrevSource = distFromPrevSource;
        ri.distFromNextSource = distFromNextSource;
        ri.knotPoint1 = knotPoint1;
        ri.knotPoint2 = knotPoint2;
        ri.cutPoint1 = cutPoint1;
        ri.cutPoint2 = cutPoint2;
        ri.node = node;
        ri.id = id;
        return ri;
    }

    public void assignGroup(ArrayList<Integer> ourGroup, ArrayList<Integer> otherGroup, CutInfo c, RouteMap routeMap)
            throws SegmentBalanceException {
        for (int i = 0; i < routes.length; i++) {
            Route route = routes[i];
            if (!route.needToCalculateGroups && route.ancestor == null) {
                route.ourGroup = ourGroup;
                route.otherGroup = otherGroup;
                if (!ourGroup.contains(node.id)) {
                    throw new SegmentBalanceException(parent.c);
                }
            } else {
                Route r = route;
                ArrayList<Route> routesToCalculateGroups = new ArrayList<>();
                int max = c.knot.size();
                int k = 0;
                ArrayList<Integer> seenIds = new ArrayList<>();
                while (r.ancestor != null && r.needToCalculateGroups) {
                    if (seenIds.contains(r.routeId)) {
                        break;
                    }
                    seenIds.add(r.routeId);
                    if (r.needToCalculateGroups) {
                        routesToCalculateGroups.add(0, r);
                    }
                    r = this.parent.get(r.ancestor.id).getRoute(r.ancestorRouteType);
                    if (k > max) {
                        throw new AssertionError();
                    }
                    k++;
                }

                int kp2 = c.upperKnotPoint.id;
                int cp2 = c.upperCutPoint.id;
                int kp2Neighbor;

                for (int j = 0; j < routesToCalculateGroups.size(); j++) {
                    r = routesToCalculateGroups.get(j);
                    r.needToCalculateGroups = false;
                    if (r.ourGroup != null && r.delta != 0.0) {
                        float z = 0;
                    }
                    if (r.ourGroup.size() == 0) {
                        float z = 0;
                    }
                    if (c.cutID == 1111 && j == 2) {
                        float z = 0;
                    }
                    int ourLast = r.ourGroup.get(r.ourGroup.size() - 1);
                    int ourFirst = r.ourGroup.get(0);
                    int otherLast = r.otherGroup.get(r.otherGroup.size() - 1);
                    int otherFirst = r.otherGroup.get(0);

                    ArrayList<Integer> mainGroup = null;
                    ArrayList<Integer> moveGroup = null;
                    int kpIdx = -1;
                    boolean first = false;
                    int moveVp = -1;
                    int dontMoveVp = -1;
                    boolean set = true;
                    if (ourLast == kp2) {
                        mainGroup = r.ourGroup;
                        kpIdx = r.ourGroup.size() - 1;
                    } else if (ourFirst == kp2) {
                        mainGroup = r.ourGroup;
                        kpIdx = 0;
                    } else if (otherLast == kp2) {
                        mainGroup = r.otherGroup;
                        kpIdx = r.otherGroup.size() - 1;
                    } else if (otherFirst == kp2) {
                        mainGroup = r.otherGroup;
                        kpIdx = 0;
                    } else {
                        set = false;
                        if (ourLast == cp2) {
                            mainGroup = r.ourGroup;
                            kpIdx = r.ourGroup.size() - 1;
                        } else if (ourFirst == cp2) {
                            mainGroup = r.ourGroup;
                            kpIdx = 0;
                        } else if (otherLast == cp2) {
                            mainGroup = r.otherGroup;
                            kpIdx = r.otherGroup.size() - 1;
                        } else if (otherFirst == cp2) {
                            mainGroup = r.otherGroup;
                            kpIdx = 0;
                        }
                    }

                    if (set) {
                        moveVp = kp2;
                        dontMoveVp = cp2;
                    } else {
                        moveVp = cp2;
                        dontMoveVp = kp2;
                    }

                    Route prev = routeMap.get(moveVp).getRoute(RouteType.prevC);
                    Route next = routeMap.get(moveVp).getRoute(RouteType.nextC);
                    if (prev.neighbor.id == dontMoveVp) {
                        kp2Neighbor = next.neighbor.id;
                    } else {
                        kp2Neighbor = prev.neighbor.id;
                    }

                    if (ourLast == kp2Neighbor) {
                        moveGroup = r.ourGroup;
                        first = false;
                    } else if (ourFirst == kp2Neighbor) {
                        moveGroup = r.ourGroup;
                        first = true;

                    } else if (otherLast == kp2Neighbor) {
                        moveGroup = r.otherGroup;
                        first = false;
                    } else if (otherFirst == kp2Neighbor) {
                        moveGroup = r.otherGroup;
                        first = true;
                    }
                    if (moveGroup == null) {
                        continue;
                    }
                    mainGroup.remove((int) kpIdx);
                    if (first) {
                        moveGroup.add(0, moveVp);
                    } else {
                        moveGroup.add(moveVp);
                    }
                }
            }
        }
    }

    public void updateRoute(double delta, VirtualPoint ancestor, RouteType routeType, RouteType ancestorRouteType,
            Route ancestorRoute, Segment acrossSeg) throws SegmentBalanceException {

        Route route = routes[routeType.idx];
        if (ancestorRoute.parent.group != group) {
            route.greatestRotDistAncestorOtherGroup = ancestorRoute.parent.rotDist;
        } else {
            route.greatestRotDistAncestorOtherGroup = ancestorRoute.greatestRotDistAncestorOtherGroup;
        }
        // 0.15%
        route.delta = delta;
        route.ancestorRouteType = ancestorRouteType;
        route.ancestor = ancestor;
        route.ancestorRoute = ancestorRoute;
        // 2.18-2.8%
        route.cuts = new ArrayList<>(ancestorRoute.cuts);
        route.cuts.add(route.neighborSegment);
        route.matches = new ArrayList<>(ancestorRoute.matches);
        route.matches.add(acrossSeg);
        // 12%
        route.calculateGroupsFromAncestor(ancestorRoute);
    }

    public Route getRoute(RouteType routeType) {
        return routes[routeType.idx];
    }

    @Override
    public String toString() {
        return node.id + "," + prevC.toString() + "," + prevDC.toString() + "," + nextC.toString() + ","
                + nextDC.toString();

    }

    public void getNeighborRoute(VirtualPoint cp) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getNeighborRoute'");
    }

}