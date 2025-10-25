package ixdar.common.exceptions;

import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;

public class BalancerException extends SegmentBalanceException {

    String errorMsg;

    public BalancerException(SegmentBalanceException sbe) {
        super(sbe);
    }

    public BalancerException(Knot vp1, Knot vp2, SegmentBalanceException sbe) {
        super(sbe);
        errorMsg = "BAD CUT: " + vp1 + " " + vp2;
    }

    public BalancerException(Knot vp, Segment newMatch, SegmentBalanceException sbe, String messageType) {
        super(sbe);
        errorMsg = messageType + vp + " " + newMatch;
    }

    @Override
    public String toString() {
        return "BalancerException: cutID: " + super.c.cutID + " ErrorMSG: " + errorMsg;
    }
}
