package shell;

public class ShorterPathNotFoundException extends SegmentBalanceException {
    

    public ShorterPathNotFoundException(SegmentBalanceException sbe) {
        super(sbe);
    }

    public ShorterPathNotFoundException(Shell shell, CutMatchList internalCuts12, CutInfo c) {
        super(shell, internalCuts12, c);
    }

    @Override
    public String toString() {
        return "ShorterPathNotFoundException: " + topKnot + " cut1: " + cut1 + " ex1: " + ex1 + " cut2: " + cut2 + " ex2: " + ex2 + " cutName: " + cutName + " cut: \n" +cutMatchList;
    }

}
