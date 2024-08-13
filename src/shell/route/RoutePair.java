package shell.route;

public class RoutePair {
    public double delta;
    public Route route;

    public RoutePair(Route vRoute) {
        route = vRoute;
        delta = vRoute.delta;
    }

}
