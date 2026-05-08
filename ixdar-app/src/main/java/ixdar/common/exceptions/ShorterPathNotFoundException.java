package ixdar.common.exceptions;

import ixdar.geometry.cuts.CutInfo;
import ixdar.geometry.cuts.CutMatchList;
import ixdar.geometry.shell.Shell;

/**
 * Thrown by the cut balancer when it expects to discover a path strictly
 * shorter than the current candidate but none exists. Inherits cut-context
 * state from {@link SegmentBalanceException}.
 */
public class ShorterPathNotFoundException extends SegmentBalanceException {

    /**
     * Wrap an existing {@link SegmentBalanceException}, copying its diagnostic state.
     *
     * @param sbe source exception to inherit context from
     */
    public ShorterPathNotFoundException(SegmentBalanceException sbe) {
        super(sbe);
    }

    /**
     * Construct with the partial cut-match list and surrounding cut context.
     *
     * @param shell shell on which the search was attempted
     * @param internalCuts12 internal cut-match list built before the failure
     * @param c cut context describing the search
     */
    public ShorterPathNotFoundException(Shell shell, CutMatchList internalCuts12, CutInfo c) {
        super(shell, internalCuts12, c);
    }

    /**
     * Diagnostic string including cut ID, knots, segments, and cut-match list.
     *
     * @return human-readable representation for logs
     */
    @Override
    public String toString() {
        return "ShorterPathNotFoundException: " + "cutID: " + c.cutID + " " + topKnot + " cut1: " + cut1 + " ex1: "
                + ex1 + " cut2: " + cut2 + " ex2: " + ex2 + " cutName: " + cutName + " cut: \n" + cutMatchList;
    }

}
