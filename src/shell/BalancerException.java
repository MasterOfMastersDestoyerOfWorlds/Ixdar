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

    public BalancerException(VirtualPoint vp, SegmentBalanceException sbe) {
        super(sbe);
        errorMsg = "BAD External Match: " + vp;
    }

    @Override
    public String toString() {
        // TODO Auto-generated method stub
        return "BalancerException: cutID: " + super.c.cutID + " ErrorMSG: " + errorMsg ;
    }
}
