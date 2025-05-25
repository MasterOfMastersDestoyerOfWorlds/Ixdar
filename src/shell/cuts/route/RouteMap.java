package shell.cuts.route;

import java.util.ArrayList;
import java.util.HashMap;

import shell.cuts.CutInfo;
import shell.knot.Knot;

public class RouteMap extends HashMap<Integer, RouteInfo> {
    public CutInfo c;
    public ArrayList<Route> routesToCheck;
    public RouteInfo[] routeInfos;

    public RouteMap(RouteMap routeMapToCopy, Knot upperCutPoint,
            Knot upperKnotPoint, CutInfo c) {
        this.c = c;
        if (c.lowerKnotPoint.id != routeMapToCopy.c.lowerKnotPoint.id) {
            throw new AssertionError();
        }
        if (c.lowerCutPoint.id != routeMapToCopy.c.lowerCutPoint.id) {
            throw new AssertionError();
        }
        this.routesToCheck = new ArrayList<>();
        this.routeInfos = new RouteInfo[c.knot.knotPointsFlattened.size()];
        for (int i = 0; i < routeMapToCopy.routeInfos.length; i++) {
            RouteInfo r = routeMapToCopy.getIndex(i);
            this.put(r.id, i, new RouteInfo(r, upperCutPoint, upperKnotPoint, c, this, routesToCheck));
        }
        ArrayList<Route> toRemove = new ArrayList<>();
        for (Route r : routesToCheck) {
            if (r.delta == Double.MAX_VALUE) {
                toRemove.add(r);
            }
        }
        routesToCheck.removeAll(toRemove);
    }

    public RouteMap(CutInfo c) {
        super();
        this.c = c;
        this.routeInfos = new RouteInfo[c.knot.knotPointsFlattened.size()];
    }

    @Override
    public String toString() {
        String str = "";
        for (RouteInfo r : this.values()) {
            str += r.toString() + "\n";
        }
        return str;
    }

    public RouteMap copy() {
        RouteMap copy = new RouteMap(c);
        for (Entry<Integer, RouteInfo> entry : this.entrySet()) {
            RouteInfo copyRouteInfo = entry.getValue().copy(this);
            copy.put(entry.getKey(), copyRouteInfo);
        }
        return copy;
    }

    public RouteInfo put(Integer key, int idx, RouteInfo r) {
        RouteInfo prev = super.put(key, r);
        routeInfos[idx] = r;
        return prev;
    }

    public RouteInfo getIndex(int i) {
        return this.routeInfos[i];
    }
}
