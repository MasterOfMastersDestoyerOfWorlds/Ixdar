package shell.cuts.route;

import java.util.ArrayList;

import shell.cuts.enums.RouteType;
import shell.cuts.enums.State;
import shell.knot.Segment;
import shell.knot.VirtualPoint;

public class Route implements Comparable<Route> {
    public RouteType routeType;
    public RouteType ancestorRouteType = RouteType.None;
    public State state = State.None;
    public VirtualPoint neighbor;
    public double delta;
    public VirtualPoint ancestor;
    public ArrayList<Route> ancestors;
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
        ancestors = new ArrayList<>();
        cuts = new ArrayList<>();
        matches = new ArrayList<>();
        routeId = routeType.idTransform(pointId);

    }

    public void reset() {
        delta = Double.MAX_VALUE;
        ancestors = new ArrayList<>();
        cuts = new ArrayList<>();
        matches = new ArrayList<>();
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
        return (this.routeId == rt.routeId);
    }

    @Override
    public int compareTo(Route o) {
        return Double.compare(delta, o.delta);
    }

    @Override
    public String toString() {
        return routeType.name() + "," + (ancestor == null ? "NULL"
                : ancestor.id) + "," + (delta == Double.MAX_VALUE ? "INF" : delta);
    }

}