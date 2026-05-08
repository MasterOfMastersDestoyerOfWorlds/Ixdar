package ixdar.common.exceptions;

import ixdar.geometry.cuts.CutInfo;
import ixdar.geometry.cuts.CutMatchList;
import ixdar.geometry.shell.Shell;

public class InvalidCutException extends SegmentBalanceException {

    String errMsg = "";

    /**
     * TODO: document {@code InvalidCutException}.
     *
     * @param sbe TODO: describe
     */
    public InvalidCutException(SegmentBalanceException sbe) {
        super(sbe);
    }

    /**
     * TODO: document {@code InvalidCutException}.
     *
     * @param shell TODO: describe
     * @param internalCuts12 TODO: describe
     * @param c TODO: describe
     */
    public InvalidCutException(Shell shell, CutMatchList internalCuts12, CutInfo c) {
        super(shell, internalCuts12, c);
    }

    /**
     * TODO: document {@code InvalidCutException}.
     *
     * @param string TODO: describe
     * @param sbe TODO: describe
     */
    public InvalidCutException(String string, SegmentBalanceException sbe) {
        super(new SegmentBalanceException());
        this.errMsg = string;
    }

    /**
     * TODO: document {@code toString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toString() {
        return "InvalidCutException: FailReason: " + errMsg + " |  topKnot: " + topKnot + " cut1: " + cut1 + " ex1: "
                + ex1 + " cut2: " + cut2 + " ex2: " + ex2 + " cutName: " + cutName + " cut: \n" + cutMatchList;
    }

}
