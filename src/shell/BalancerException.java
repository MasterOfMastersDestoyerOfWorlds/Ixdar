package shell;

public class BalancerException extends SegmentBalanceException{

    String errorMsg;

    public BalancerException(SegmentBalanceException sbe) {
        super(sbe);
        //TODO Auto-generated constructor stub
    }

    public BalancerException(VirtualPoint vp1, VirtualPoint vp2, SegmentBalanceException sbe) {
        super(sbe);
        errorMsg = "BAD CUT: " + vp1 + " " + vp2;
        //TODO Auto-generated constructor stub
    }

    public BalancerException(VirtualPoint vp, Segment newMatch, SegmentBalanceException sbe, String messageType) {
        super(sbe);
        errorMsg = messageType + vp + " " + newMatch;
        //TODO Auto-generated constructor stub
    }

    @Override
    public String toString() {
        // TODO Auto-generated method stub
        return "BalancerException: cutID: " + super.c.cutID + " ErrorMSG: " + errorMsg ;
    }
}
