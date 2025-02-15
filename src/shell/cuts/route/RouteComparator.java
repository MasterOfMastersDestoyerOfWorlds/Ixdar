package shell.cuts.route;

import java.util.Comparator;

import shell.cuts.engines.InternalPathEngine;

public class RouteComparator implements Comparator<RoutePair> {

    @Override
    public int compare(RoutePair o1, RoutePair o2) {
        double d1 = o1.delta;
        double d2 = o2.delta;
        InternalPathEngine.comparisons++;
        if (d1 < d2)
            return -1; // Neither val is NaN, thisVal is smaller
        if (d1 > d2)
            return 1; // Neither val is NaN, thisVal is larger

        // Cannot use doubleToRawLongBits because of possibility of NaNs.
        long thisBits = (long) d1;
        long anotherBits = (long) d2;

        return (thisBits == anotherBits ? 0 : // Values are equal
                (thisBits < anotherBits ? -1 : // (-0.0, 0.0) or (!NaN, NaN)
                        1)); // (0.0, -0.0) or (NaN, !NaN)
    }
}