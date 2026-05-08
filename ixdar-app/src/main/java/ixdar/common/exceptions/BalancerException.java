package ixdar.common.exceptions;

import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;

public class BalancerException extends SegmentBalanceException {

    String errorMsg;

    /**
     * TODO: document {@code BalancerException}.
     *
     * @param sbe TODO: describe
     */
    public BalancerException(SegmentBalanceException sbe) {
        super(sbe);
    }

    /**
     * TODO: document {@code BalancerException}.
     *
     * @param vp1 TODO: describe
     * @param vp2 TODO: describe
     * @param sbe TODO: describe
     */
    public BalancerException(Knot vp1, Knot vp2, SegmentBalanceException sbe) {
        super(sbe);
        errorMsg = "BAD CUT: " + vp1 + " " + vp2;
    }

    /**
     * TODO: document {@code BalancerException}.
     *
     * @param vp TODO: describe
     * @param newMatch TODO: describe
     * @param sbe TODO: describe
     * @param messageType TODO: describe
     */
    public BalancerException(Knot vp, Segment newMatch, SegmentBalanceException sbe, String messageType) {
        super(sbe);
        errorMsg = messageType + vp + " " + newMatch;
    }

    /**
     * TODO: document {@code toString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toString() {
        return "BalancerException: cutID: " + super.c.cutID + " ErrorMSG: " + errorMsg;
    }
}
