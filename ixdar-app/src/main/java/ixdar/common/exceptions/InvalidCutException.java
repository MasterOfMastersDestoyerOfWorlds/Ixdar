package ixdar.common.exceptions;

import ixdar.geometry.cuts.CutInfo;
import ixdar.geometry.cuts.CutMatchList;
import ixdar.geometry.shell.Shell;

public class InvalidCutException extends SegmentBalanceException {

    String errMsg = "";

    public InvalidCutException(SegmentBalanceException sbe) {
        super(sbe);
    }

    public InvalidCutException(Shell shell, CutMatchList internalCuts12, CutInfo c) {
        super(shell, internalCuts12, c);
    }

    public InvalidCutException(String string, SegmentBalanceException sbe) {
        super(new SegmentBalanceException());
        this.errMsg = string;
    }

    @Override
    public String toString() {
        return "InvalidCutException: FailReason: " + errMsg + " |  topKnot: " + topKnot + " cut1: " + cut1 + " ex1: "
                + ex1 + " cut2: " + cut2 + " ex2: " + ex2 + " cutName: " + cutName + " cut: \n" + cutMatchList;
    }

}
