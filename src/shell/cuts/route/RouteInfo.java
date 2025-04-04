package shell.cuts.route;

import java.util.ArrayList;

import shell.cuts.CutInfo;
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

    public void assignGroup(ArrayList<Integer> ourGroup, ArrayList<Integer> otherGroup)
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
                for (int j = 0; j < routesToCalculateGroups.size(); j++) {
                    r = routesToCalculateGroups.get(j);
                    Route ancestorRoute = this.parent.get(r.ancestor.id).getRoute(r.ancestorRouteType);
                    if (ancestorRoute.ourGroup == null) {
                        ancestorRoute.ourGroup = ourGroup;
                        ancestorRoute.otherGroup = otherGroup;
                    }
                    r.calculateGroups(ancestorRoute);
                }
            }
        }
    }

    public void updateRoute(double delta, VirtualPoint ancestor, RouteType routeType, RouteType ancestorRouteType,
            Route ancestorRoute) throws SegmentBalanceException {

        Route route = getRoute(routeType);

        // 20%
        if (delta < route.delta) {
            // 3%
            route.delta = delta;
            route.ancestorRouteType = ancestorRouteType;
            route.ancestor = ancestor;
            route.ancestorRoute = ancestorRoute;
            VirtualPoint neighbor = route.neighbor;
            VirtualPoint node = this.node;
            route.cuts = new ArrayList<>(ancestorRoute.cuts);
            Segment newCut = node.getSegment(neighbor);
            route.cuts.add(0, newCut);
            route.matches = new ArrayList<>(ancestorRoute.matches);
            // 3%
            Segment newMatch = ancestor.getSegment(neighbor);
            route.matches.add(0, newMatch);
            route.ancestorRoutes = new ArrayList<>(ancestorRoute.ancestorRoutes);
            if (route.ancestorRoutes.contains(ancestorRoute.routeId)) {
                throw new SegmentBalanceException(c);
            }
            // 2%
            route.ancestorRoutes.add(ancestorRoute.routeId);
            // 9%
            route.calculateGroups(ancestorRoute);
        }

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