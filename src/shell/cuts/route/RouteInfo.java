package shell.cuts.route;

import java.util.ArrayList;

import shell.cuts.CutInfo;
import shell.cuts.enums.Group;
import shell.cuts.enums.RouteType;
import shell.knot.Segment;
import shell.knot.VirtualPoint;

public class RouteInfo {

    // need to ad a concept of teh winding number to this model, which would flip
    // the state of the prev/ next depending on weather the winding number is even
    // or odd
    //

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
            VirtualPoint cutPoint1, VirtualPoint cutPoint2) {
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

    public RouteInfo(RouteInfo other, VirtualPoint upperCutPoint, VirtualPoint upperKnotPoint, CutInfo c) {
        this.node = other.node;
        this.id = node.id;
        this.knotPoint1 = other.knotPoint1;
        this.knotPoint2 = upperKnotPoint;
        this.cutPoint1 = other.cutPoint1;
        this.cutPoint2 = upperCutPoint;
        this.prevC = new Route(other.prevC, upperCutPoint, upperKnotPoint, this, c);
        this.nextC = new Route(other.nextC, upperCutPoint, upperKnotPoint, this, c);
        this.prevDC = new Route(other.prevDC, upperCutPoint, upperKnotPoint, this, c);
        this.nextDC = new Route(other.nextDC, upperCutPoint, upperKnotPoint, this, c);
        routes = new Route[] { prevC, prevDC, nextC, nextDC };
    }

    public void assignGroup(ArrayList<VirtualPoint> ourGroup, ArrayList<VirtualPoint> otherGroup) {
        if (prevC.ourGroup == null) {
            prevC.ourGroup = ourGroup;
            prevC.otherGroup = otherGroup;
        }
        if (prevDC.ourGroup == null) {
            prevDC.ourGroup = ourGroup;
            prevDC.otherGroup = otherGroup;
        }
        if (nextC.ourGroup == null) {
            nextC.ourGroup = ourGroup;
            nextC.otherGroup = otherGroup;
        }
        if (nextDC.ourGroup == null) {
            nextDC.ourGroup = ourGroup;
            nextDC.otherGroup = otherGroup;
        }
    }

    public void updateRoute(double delta, VirtualPoint ancestor, RouteType routeType, RouteType ancestorRouteType,
            Route ancestorRoute, int settledSize, int knotId) {

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

            if (ancestorRoute.ourGroup.contains(node)) {
                ArrayList<VirtualPoint> grp = ancestorRoute.ourGroup;
                int idxNeighbor = grp.indexOf(neighbor);
                int rotateIdx = grp.indexOf(node);

                route.otherGroup = ancestorRoute.otherGroup;

                ArrayList<VirtualPoint> reverseList = new ArrayList<VirtualPoint>();
                if (idxNeighbor > rotateIdx || idxNeighbor == -1) {
                    for (int i = rotateIdx + 1; i < grp.size(); i++) {
                        reverseList.add(grp.get(i));
                    }
                    for (int i = 0; i < rotateIdx + 1; i++) {
                        reverseList.add(0, grp.get(i));
                    }
                    route.ourGroup = reverseList;
                } else {
                    for (int i = 0; i < rotateIdx; i++) {
                        reverseList.add(grp.get(i));
                    }
                    for (int i = rotateIdx; i < grp.size(); i++) {
                        reverseList.add(0, grp.get(i));
                    }
                    route.ourGroup = reverseList;
                }
            } else {

                ArrayList<VirtualPoint> grp = ancestorRoute.otherGroup;
                ArrayList<VirtualPoint> otherGrp = ancestorRoute.ourGroup;
                int idxNeighbor = grp.indexOf(neighbor);
                int rotateIdx = grp.indexOf(node);
                route.otherGroup = ancestorRoute.otherGroup;
                ArrayList<VirtualPoint> remainList = new ArrayList<VirtualPoint>();
                ArrayList<VirtualPoint> reverseList = new ArrayList<VirtualPoint>(otherGrp);
                if (idxNeighbor > rotateIdx || idxNeighbor == -1) {
                    for (int i = 0; i < rotateIdx + 1; i++) {
                        remainList.add(0, grp.get(i));
                    }
                    for (int i = rotateIdx + 1; i < grp.size(); i++) {
                        reverseList.add(0, grp.get(i));
                    }
                } else {
                    for (int i = rotateIdx; i < grp.size(); i++) {
                        remainList.add(grp.get(i));
                    }
                    for (int i = rotateIdx - 1; i >= 0; i--) {
                        reverseList.add(0, grp.get(i));
                    }
                }
                route.ourGroup = remainList;
                route.otherGroup = reverseList;
            }
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