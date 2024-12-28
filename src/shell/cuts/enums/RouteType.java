package shell.cuts.enums;

public enum RouteType {
    prevDC(false, false, true),
    prevC(true, false, true),
    nextDC(false, true, false),
    nextC(true, true, false),
    None(false, false, false);

    public boolean isConnected, isNext, isPrev;
    public RouteType oppositeRoute;
    public RouteType oppositeConnectionRoute;

    RouteType(boolean isConnected, boolean isNext, boolean isPrev) {
        this.isConnected = isConnected;
        this.isNext = isNext;
        this.isPrev = isPrev;

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

    public RouteType idTransformToType(int id) {
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