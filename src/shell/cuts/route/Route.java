package shell.cuts.route;

import java.util.ArrayList;

import shell.cuts.enums.RouteType;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.render.color.Color;
import shell.render.text.HyperString;

public class Route implements Comparable<Route> {
    public RouteType routeType;
    public RouteType ancestorRouteType = RouteType.None;
    public VirtualPoint neighbor;
    public double delta;
    public VirtualPoint ancestor;
    public ArrayList<VirtualPoint> ourGroup;
    public ArrayList<VirtualPoint> otherGroup;
    public ArrayList<Segment> cuts;
    public ArrayList<Segment> matches;
    public int routeId;
    public RouteInfo parent;

    public Route(RouteType routeType, double delta, VirtualPoint neighbor, int pointId, RouteInfo parent) {
        this.routeType = routeType;
        this.delta = delta;
        this.neighbor = neighbor;
        this.parent = parent;
        cuts = new ArrayList<>();
        matches = new ArrayList<>();
        routeId = routeType.idTransform(pointId);

    }

    /**
     * Copy constructor
     * 
     * @param routeToCopy
     * @param upperCutPoint
     * @param upperKnotPoint
     * @param parent
     * 
     */
    public Route(Route routeToCopy, VirtualPoint upperCutPoint, VirtualPoint upperKnotPoint, RouteInfo parent) {
        this.routeType = routeToCopy.routeType;
        this.ancestorRouteType = routeToCopy.ancestorRouteType;
        this.neighbor = routeToCopy.neighbor;
        this.delta = routeToCopy.delta;
        this.ancestor = routeToCopy.ancestor;
        this.ourGroup = new ArrayList<>(routeToCopy.ourGroup);
        this.otherGroup = new ArrayList<>(routeToCopy.otherGroup);
        this.cuts = new ArrayList<>(routeToCopy.cuts);
        this.matches = new ArrayList<>(routeToCopy.matches);
        this.routeId = routeToCopy.routeId;
        this.parent = parent;

        RouteInfo otherParent = routeToCopy.parent;
        if (parent.id == upperCutPoint.id || neighbor.id == upperCutPoint.id ||
                parent.id == upperKnotPoint.id || neighbor.id == upperKnotPoint.id) {
            this.reset();
        } else if (parent.id == otherParent.knotPoint2.id
                || parent.id == otherParent.cutPoint2.id ||
                neighbor.id == otherParent.knotPoint2.id || neighbor.id == otherParent.cutPoint2.id) {
            this.reset();
        } else {
            float z = 0;
        }
    }

    public void reset() {
        delta = Double.MAX_VALUE;
        cuts = new ArrayList<>();
        matches = new ArrayList<>();
        ancestor = null;
        ancestorRouteType = RouteType.None;
    }

    @Override
    public boolean equals(Object obj) {

        if (obj == null) {
            return false;
        }
        if (obj.getClass() != Route.class) {
            return false;
        } else {
            Route r2 = (Route) obj;

            return (this.routeId == r2.routeId);
        }
    }

    public boolean sameRoute(Route rt) {

        if (rt == null) {
            return false;
        }
        if (this.delta != rt.delta) {
            return false;
        }
        if (this.cuts.size() != rt.cuts.size()) {
            return false;
        }
        if (this.matches.size() != rt.matches.size()) {
            return false;
        }
        for (int i = 0; i < this.cuts.size(); i++) {
            if (!this.cuts.get(i).equals(rt.cuts.get(i))) {
                return false;
            }
        }
        for (int i = 0; i < this.matches.size(); i++) {
            if (!this.matches.get(i).equals(rt.matches.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int compareTo(Route o) {
        return Double.compare(delta, o.delta);
    }

    @Override
    public String toString() {
        return routeType.name() + ", " + (ancestor == null ? "NULL"
                : ancestor.id) + ", " + (delta == Double.MAX_VALUE ? "INF" : delta);
    }

    public HyperString compareHyperString(Route otherRoute, Color matchColor, Color cutColor) {
        HyperString h = new HyperString();
        int maxSize = Math.max(this.matches.size(), this.cuts.size());
        for (int i = 0; i < maxSize; i++) {
            Segment match = i < this.matches.size() ? this.matches.get(i) : null;
            Segment cut = i < this.cuts.size() ? this.cuts.get(i) : null;
            if (match != null) {
                if (otherRoute.matches.contains(match)) {
                    h.addHyperString(match.toHyperString(Color.CYAN, false));
                } else {
                    h.addHyperString(match.toHyperString(matchColor, false));
                }
            }
            if (cut != null) {
                if (otherRoute.cuts.contains(cut)) {
                    h.addHyperString(cut.toHyperString(Color.ORANGE, false));
                } else {
                    h.addHyperString(cut.toHyperString(cutColor, false));
                }
                h.newLine();
            }
        }
        return h;
    }

}