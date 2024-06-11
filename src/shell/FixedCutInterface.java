package shell;

public interface FixedCutInterface {
    
    public CutMatchList findCutMatchListFixedCut()
            throws SegmentBalanceException, BalancerException;
}
