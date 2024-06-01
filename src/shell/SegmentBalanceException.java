package shell;

import shell.CutMatchList;
import shell.Knot;
import shell.Segment;;

public class SegmentBalanceException extends Exception {
    public CutMatchList cutMatchList;
    public Knot topKnot;
    public Segment cut1;
    public Segment ex1;
    public Segment cut2;
    public Segment ex2;
    public SegmentBalanceException(CutMatchList internalCut, Knot knot, Segment cut1, Segment ex1, Segment cut2, Segment ex2) {
        cutMatchList = internalCut;
        topKnot = knot;
        this.cut1 = cut1;
        this.ex1 = ex1;
        this.cut2 = cut2;
        this.ex2 = ex2;
    }

    public SegmentBalanceException(SegmentBalanceException sbe){
        cutMatchList = sbe.cutMatchList;
        topKnot = sbe.topKnot;
        cut1 = sbe.cut1;
        ex1 = sbe.ex1;
        cut2 = sbe.cut2;
        ex2 = sbe.ex2;

    }

    @Override
    public String toString() {
        return "SegmentBalanceException: " + topKnot + " cut1: " + cut1 + " ex1: " + ex1 + " cut2: " + cut2 + " ex2: " + ex2;
    }

}
