package shell.enums;

public enum RouteType {
    prevDC{
        @Override
        public boolean isConnected() {
            return false;
        }
        @Override
        public boolean isNext() {
            return false;
        }
        @Override
        public boolean isPrev() {
            return true;
        }
        @Override
        public RouteType oppositeRoute() {
            return RouteType.nextDC;
        }
    },
    prevC{
        @Override
        public boolean isConnected() {
            return true;
        }
        @Override
        public boolean isNext() {
            return false;
        }
        @Override
        public boolean isPrev() {
            return true;
        }
        @Override
        public RouteType oppositeRoute() {
            return RouteType.nextC;
        }
    },
    nextDC{
        @Override
        public boolean isConnected() {
            return false;
        }   
        @Override
        public boolean isNext() {
            return true;
        }
        @Override
        public boolean isPrev() {
            return false;
        }
        @Override
        public RouteType oppositeRoute() {
            return RouteType.prevDC;
        }
    },
    nextC{
        @Override
        public boolean isConnected() {
            return true;
        }
        @Override
        public boolean isNext() {
            return true;
        }
        @Override
        public boolean isPrev() {
            return false;
        }
        @Override
        public RouteType oppositeRoute() {
            return RouteType.prevC;
        }
    },
    None{
        @Override
        public boolean isConnected() {
            return false;
        }   
        @Override
        public boolean isNext() {
            return false;
        }
        @Override
        public boolean isPrev() {
            return false;
        }
        @Override
        public RouteType oppositeRoute() {
            return RouteType.None;
        }
    };

    public int idTransform(int id) {
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

    public abstract RouteType oppositeRoute();

    public abstract boolean isConnected();

    public abstract boolean isNext();

    public abstract boolean isPrev();
}