package ixdar.common.exceptions;

import ixdar.geometry.cuts.CutInfo;
import ixdar.geometry.cuts.CutMatchList;
import ixdar.geometry.shell.Shell;

/**
 * Thrown when a proposed cut cannot be applied because it violates cut-validity
 * rules (e.g. would orphan knot points or otherwise produce a disallowed
 * topology). Carries an optional human-readable failure reason.
 */
public class InvalidCutException extends SegmentBalanceException {

    String errMsg = "";

    /**
     * Wrap an existing {@link SegmentBalanceException}, copying its diagnostic state.
     *
     * @param sbe source exception to inherit context from
     */
    public InvalidCutException(SegmentBalanceException sbe) {
        super(sbe);
    }

    /**
     * Construct with the partial cut-match list and surrounding cut context.
     *
     * @param shell shell on which the cut was attempted
     * @param internalCuts12 internal cut-match list built before the rejection
     * @param c cut context describing the invalid cut
     */
    public InvalidCutException(Shell shell, CutMatchList internalCuts12, CutInfo c) {
        super(shell, internalCuts12, c);
    }

    /**
     * Construct with a free-form failure reason.
     *
     * @param string description of why the cut was rejected
     * @param sbe source exception (currently unused for state copy)
     */
    public InvalidCutException(String string, SegmentBalanceException sbe) {
        super(new SegmentBalanceException());
        this.errMsg = string;
    }

    /**
     * Diagnostic string including the failure reason and inherited cut state.
     *
     * @return human-readable representation for logs
     */
    @Override
    public String toString() {
        return "InvalidCutException: FailReason: " + errMsg + " |  topKnot: " + topKnot + " cut1: " + cut1 + " ex1: "
                + ex1 + " cut2: " + cut2 + " ex2: " + ex2 + " cutName: " + cutName + " cut: \n" + cutMatchList;
    }

}
