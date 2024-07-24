package shell;

public class MultipleCyclesFoundException extends SegmentBalanceException {
    

    public MultipleCyclesFoundException(SegmentBalanceException sbe) {
        super(sbe);
    }

    public MultipleCyclesFoundException(Shell shell, CutMatchList internalCuts12, CutInfo c) {
        super(shell, internalCuts12, c);
    }

    @Override
    public String toString() {
        return "MultipleCyclesFoundException: "+ "cutID: " + c.cutID + " " + topKnot + " cut1: " + cut1 + " ex1: " + ex1 + " cut2: " + cut2 + " ex2: " + ex2 + " cutName: " + cutName;
    }

}
