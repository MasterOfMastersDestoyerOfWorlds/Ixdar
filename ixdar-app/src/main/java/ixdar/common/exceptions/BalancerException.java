package ixdar.common.exceptions;

import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;

/**
 * Thrown by the cut/match balancer when the requested cut cannot be balanced.
 * Inherits cut-context state from {@link SegmentBalanceException} and adds a
 * descriptive {@code errorMsg} formed from the offending knot points/segments.
 */
public class BalancerException extends SegmentBalanceException {

    String errorMsg;

    /**
     * Wrap an existing {@link SegmentBalanceException}, copying its diagnostic state.
     *
     * @param sbe source exception to inherit context from
     */
    public BalancerException(SegmentBalanceException sbe) {
        super(sbe);
    }

    /**
     * Construct for a "bad cut" between two knot points.
     *
     * @param vp1 first knot point of the failing cut
     * @param vp2 second knot point of the failing cut
     * @param sbe source exception to inherit context from
     */
    public BalancerException(Knot vp1, Knot vp2, SegmentBalanceException sbe) {
        super(sbe);
        errorMsg = "BAD CUT: " + vp1 + " " + vp2;
    }

    /**
     * Construct with a custom message prefix describing why a match was rejected.
     *
     * @param vp knot point at which the failure occurred
     * @param newMatch candidate match segment that was rejected
     * @param sbe source exception to inherit context from
     * @param messageType prefix categorizing the failure (concatenated with vp/newMatch)
     */
    public BalancerException(Knot vp, Segment newMatch, SegmentBalanceException sbe, String messageType) {
        super(sbe);
        errorMsg = messageType + vp + " " + newMatch;
    }

    /**
     * Diagnostic string including the cut ID and computed error message.
     *
     * @return human-readable representation for logs
     */
    @Override
    public String toString() {
        return "BalancerException: cutID: " + super.c.cutID + " ErrorMSG: " + errorMsg;
    }
}
