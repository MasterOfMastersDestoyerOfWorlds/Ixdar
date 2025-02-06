package shell.cuts.route;

import java.util.HashMap;

import shell.knot.VirtualPoint;

public class RouteMap extends HashMap<Integer, RouteInfo> {
    public RouteMap(RouteMap routeMapToCopy, VirtualPoint upperCutPoint,
            VirtualPoint upperKnotPoint) {
        for(Integer key: routeMapToCopy.keySet()){
            RouteInfo r = routeMapToCopy.get(key);
            this.put(key, new RouteInfo(r, upperCutPoint, upperKnotPoint));
        }
    }

    public RouteMap() {
        super();
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
