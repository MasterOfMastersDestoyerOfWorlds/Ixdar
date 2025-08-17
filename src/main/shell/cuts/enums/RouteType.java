package shell.cuts.enums;

public enum RouteType {
    prevDC(false, false, true, 0, 0, 0),
    prevC(true, false, true, 1, 0, 0),
    nextDC(false, true, false, 2, 2, 1),
    nextC(true, true, false, 3, 2, 1),
    None(false, false, false, -1, -1, -1);

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

    public int idTransform(int id) {
        nextDC.oppositeRoute = prevDC;
        if (this.equals(RouteType.None)) {
            return -1;
        }
        return (id * 4) + this.ordinal();
    }

    public static RouteType idTransformToType(int id) {
        if (id < 0) {
            return RouteType.None;
        }
        int base = id % 4;
        switch (base) {
        case 0:
            return RouteType.prevC;
        case 1:
            return RouteType.nextC;
        case 2:
            return RouteType.prevDC;
        case 3:
            return RouteType.nextDC;
        default:
            return RouteType.None;

        }
    }

}