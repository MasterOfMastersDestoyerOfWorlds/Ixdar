package shell;

import shell.InternalPathEngine.Route;

public class RoutePair {
    double delta;
    Route route;

    public RoutePair(Route vRoute) {
        route = vRoute;
        delta = vRoute.delta;
    }

}
