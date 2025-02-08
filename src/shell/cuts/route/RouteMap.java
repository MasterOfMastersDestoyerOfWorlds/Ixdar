package shell.cuts.route;

import java.util.HashMap;

import shell.cuts.CutInfo;
import shell.knot.VirtualPoint;

public class RouteMap extends HashMap<Integer, RouteInfo> {
    public CutInfo c;

    public RouteMap(RouteMap routeMapToCopy, VirtualPoint upperCutPoint,
            VirtualPoint upperKnotPoint, CutInfo c) {
        this.c = c;
        if (c.lowerKnotPoint.id != routeMapToCopy.c.lowerKnotPoint.id) {
            float z = 1/0;
        }
        if (c.lowerCutPoint.id != routeMapToCopy.c.lowerCutPoint.id) {
            float z = 1/0;
        }
        for (Integer key : routeMapToCopy.keySet()) {
            RouteInfo r = routeMapToCopy.get(key);
            this.put(key, new RouteInfo(r, upperCutPoint, upperKnotPoint, c, this));
        }
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
