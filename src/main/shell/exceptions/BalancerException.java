package shell.exceptions;

import shell.knot.Knot;
import shell.knot.Segment;

public class BalancerException extends SegmentBalanceException {

    String errorMsg;

    public BalancerException(SegmentBalanceException sbe) {
        super(sbe);
        // TODO Auto-generated constructor stub
    }

    public BalancerException(Knot vp1, Knot vp2, SegmentBalanceException sbe) {
        super(sbe);
        errorMsg = "BAD CUT: " + vp1 + " " + vp2;
        // TODO Auto-generated constructor stub
    }

    public BalancerException(Knot vp, Segment newMatch, SegmentBalanceException sbe, String messageType) {
        super(sbe);
        errorMsg = messageType + vp + " " + newMatch;
        // TODO Auto-generated constructor stub
    }

    @Override
    public String toString() {
        // TODO Auto-generated method stub
        return "BalancerException: cutID: " + super.c.cutID + " ErrorMSG: " + errorMsg;
    }
}
