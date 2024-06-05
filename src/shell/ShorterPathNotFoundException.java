package shell;

import shell.CutMatchList;
import shell.Knot;
import shell.Segment;;

public class ShorterPathNotFoundException extends SegmentBalanceException {
    

    public ShorterPathNotFoundException(SegmentBalanceException sbe) {
        super(sbe);
    }

    public ShorterPathNotFoundException(Shell shell, CutMatchList internalCuts12, Knot knot, Segment cut1,
            Segment ex1, Segment cut2, Segment ex2) {
        super(shell, internalCuts12, knot, cut1, ex1, cut2, ex2);
    }

    @Override
    public String toString() {
        return "ShorterPathNotFoundException: " + topKnot + " cut1: " + cut1 + " ex1: " + ex1 + " cut2: " + cut2 + " ex2: " + ex2 + " cutName: " + cutName;
    }

}
