package shell.cuts.route;

import java.util.ArrayList;
import java.util.HashMap;

import shell.cuts.CutInfo;
import shell.knot.VirtualPoint;

public class RouteMap extends HashMap<Integer, RouteInfo> {
    public CutInfo c;
    public ArrayList<Route> routesToCheck;

    public RouteMap(RouteMap routeMapToCopy, VirtualPoint upperCutPoint,
            VirtualPoint upperKnotPoint, CutInfo c) {
        this.c = c;
        if (c.lowerKnotPoint.id != routeMapToCopy.c.lowerKnotPoint.id) {
            throw new AssertionError();
        }
        if (c.lowerCutPoint.id != routeMapToCopy.c.lowerCutPoint.id) {
            throw new AssertionError();
        }
        this.routesToCheck = new ArrayList<>();
        for (Integer key : routeMapToCopy.keySet()) {
            RouteInfo r = routeMapToCopy.get(key);
            this.put(key, new RouteInfo(r, upperCutPoint, upperKnotPoint, c, this, routesToCheck));
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
    }

    @Override
    public String toString() {
        String str = "";
        for (RouteInfo r : this.values()) {
            str += r.toString() + "\n";
        }
        return str;
    }
}
