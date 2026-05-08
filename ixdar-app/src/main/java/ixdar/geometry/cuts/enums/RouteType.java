package ixdar.geometry.cuts.enums;

public enum RouteType {
    prevDC(false, false, true, 0, 0, 0),
    prevC(true, false, true, 1, 0, 0),
    nextDC(false, true, false, 2, 2, 1),
    nextC(true, true, false, 3, 2, 1),
    None(false, false, false, -1, -1, -1);
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;

    static {
        nextC.oppositeRoute = prevC;
        prevC.oppositeRoute = nextC;
        nextDC.oppositeRoute = prevDC;
        prevDC.oppositeRoute = nextDC;
    }

    static {
        nextC.oppositeConnectionRoute = nextDC;
        prevC.oppositeConnectionRoute = prevDC;
        nextDC.oppositeConnectionRoute = nextC;
        prevDC.oppositeConnectionRoute = prevC;
    }

    public boolean isConnected, isNext, isPrev;
    public RouteType oppositeRoute;
    public RouteType oppositeConnectionRoute;
    public int idx;
    public int routeOffset;
    public int matOffset;

    RouteType(boolean isConnected, boolean isNext, boolean isPrev, int idx, int routeOffset, int matOffset) {
        this.isConnected = isConnected;
        this.isNext = isNext;
        this.isPrev = isPrev;
        this.idx = idx;
        this.routeOffset = routeOffset;
        this.matOffset = matOffset;
    }

    /**
     * Pack a knot-point id together with this route type into a unique route id
     * by computing {@code id * 4 + ordinal()}. Returns {@code -1} for {@link #None}.
     *
     * @param id base knot-point id
     * @return packed route id, or {@code -1} for {@link #None}
     */
    public int idTransform(int id) {
        nextDC.oppositeRoute = prevDC;
        if (this.equals(RouteType.None)) {
            return -1;
        }
        return (id * NUM_4) + this.ordinal();
    }

    /**
     * Inverse of {@link #idTransform}: extract the route type from a packed
     * route id by inspecting {@code id % 4}. Negative ids map to {@link #None}.
     *
     * @param id packed route id
     * @return the {@link RouteType} encoded in {@code id}, or {@link #None}
     */
    public static RouteType idTransformToType(int id) {
        if (id < 0) {
            return RouteType.None;
        }
        int base = id % NUM_4;
        switch (base) {
        case 0:
            return RouteType.prevC;
        case 1:
            return RouteType.nextC;
        case 2:
            return RouteType.prevDC;
        case NUM_3:
            return RouteType.nextDC;
        default:
            return RouteType.None;

        }
    }

}