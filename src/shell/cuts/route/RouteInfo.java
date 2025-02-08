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
        routes = new Route[] { prevC, prevDC, nextC, nextDC };
        this.knotPoint1 = knotPoint1;
        this.knotPoint2 = knotPoint2;
        this.cutPoint1 = cutPoint1;
        this.cutPoint2 = cutPoint2;
    }

    public RouteInfo(RouteInfo routeInfoToCopy, VirtualPoint upperCutPoint, VirtualPoint upperKnotPoint, CutInfo c,
            RouteMap routeMap) {
        this.c = c;
        this.parent = routeMap;
        this.node = routeInfoToCopy.node;
        this.id = node.id;
        this.knotPoint1 = routeInfoToCopy.knotPoint1;
        this.knotPoint2 = upperKnotPoint;
        this.cutPoint1 = routeInfoToCopy.cutPoint1;
        this.cutPoint2 = upperCutPoint;
        this.prevC = new Route(routeInfoToCopy.prevC, upperCutPoint, upperKnotPoint, this, c);
        this.nextC = new Route(routeInfoToCopy.nextC, upperCutPoint, upperKnotPoint, this, c);
        this.prevDC = new Route(routeInfoToCopy.prevDC, upperCutPoint, upperKnotPoint, this, c);
        this.nextDC = new Route(routeInfoToCopy.nextDC, upperCutPoint, upperKnotPoint, this, c);
        routes = new Route[] { prevC, prevDC, nextC, nextDC };
    }

    public void assignGroup(ArrayList<VirtualPoint> ourGroup, ArrayList<VirtualPoint> otherGroup)
            throws SegmentBalanceException {

        if (c.cutID == 1881) {
            float z = 0;
        }
        for (int i = 0; i < routes.length; i++) {
            Route route = routes[i];
            if (!route.needToCalculateGroups && route.ancestor == null) {
                route.ourGroup = ourGroup;
                route.otherGroup = otherGroup;
                if (!ourGroup.contains(node)) {
                    throw new SegmentBalanceException(parent.c);
                }
            } else {
                Route r = route;
                ArrayList<Route> routesToCalculateGroups = new ArrayList<>();
                int max = c.knot.size();
                int k = 0;
                ArrayList<Integer> seenIds = new ArrayList<>();
                while (r.ancestor != null && r.needToCalculateGroups) {
                    if(seenIds.contains(r.routeId)){
                        break;
                    }
                    seenIds.add(r.routeId);
                    if (r.needToCalculateGroups) {
                        routesToCalculateGroups.add(0, r);
                    }
                    r = this.parent.get(r.ancestor.id).getRoute(r.ancestorRouteType);
                    if (k > max) {
                        float ix = 0;
                        // throw new SegmentBalanceException(parent.c);
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
            Route ancestorRoute, int settledSize, int knotId) throws SegmentBalanceException {

        Route route = getRoute(routeType);

        if (delta < route.delta) {
            route.delta = delta;
            route.ancestorRouteType = ancestorRouteType;
            route.ancestor = ancestor;
            VirtualPoint neighbor = route.neighbor;
            VirtualPoint node = this.node;
            route.cuts = new ArrayList<>(ancestorRoute.cuts);
            Segment newCut = node.getSegment(neighbor);
            route.cuts.add(0, newCut);
            route.matches = new ArrayList<>(ancestorRoute.matches);
            Segment newMatch = ancestor.getSegment(neighbor);
            route.matches.add(0, newMatch);
            route.ancestorRoutes = new ArrayList<>(ancestorRoute.ancestorRoutes);
            if (route.ancestorRoutes.contains(ancestorRoute.routeId)) {
                throw new SegmentBalanceException(c);
            }
            route.ancestorRoutes.add(ancestorRoute.routeId);
            route.calculateGroups(ancestorRoute);
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
    public String toString() {
        return node.id + "," + prevC.toString() + "," + prevDC.toString() + "," + nextC.toString() + ","
                + nextDC.toString();

    }

    public void getNeighborRoute(VirtualPoint cp) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getNeighborRoute'");
    }

}