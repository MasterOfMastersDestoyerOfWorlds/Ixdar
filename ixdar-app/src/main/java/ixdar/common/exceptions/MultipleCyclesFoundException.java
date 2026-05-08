package ixdar.common.exceptions;

import java.util.ArrayList;

import ixdar.geometry.cuts.CutInfo;
import ixdar.geometry.cuts.CutMatchList;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.shell.Shell;

/**
 * Thrown by the segment balancer when the resulting match/cut graph yields more
 * than one cycle, which is not a legal cut-match topology. Carries the offending
 * match and cut segment lists for diagnostic rendering.
 */
public class MultipleCyclesFoundException extends SegmentBalanceException {
    public static final String N = "\n";
    ArrayList<Segment> matchSegments;
    ArrayList<Segment> cutSegments;

    /**
     * Wrap an existing {@link SegmentBalanceException}, copying its diagnostic state.
     *
     * @param sbe source exception to inherit context from
     */
    public MultipleCyclesFoundException(SegmentBalanceException sbe) {
        super(sbe);
    }

    /**
     * Construct with the full set of segments involved in the multi-cycle failure.
     *
     * @param shell shell on which the balance was attempted
     * @param internalCuts12 internal cut-match list produced before the failure
     * @param matchSegments match segments contributing to the discovered cycles
     * @param cutSegments cut segments contributing to the discovered cycles
     * @param c cut info describing the cut being balanced
     */
    public MultipleCyclesFoundException(Shell shell, CutMatchList internalCuts12, ArrayList<Segment> matchSegments,
            ArrayList<Segment> cutSegments, CutInfo c) {
        super(shell, internalCuts12, c);
        this.matchSegments = matchSegments;
        this.cutSegments = cutSegments;
    }

    /**
     * Diagnostic string including match/cut segment lists and inherited cut state.
     *
     * @return human-readable representation for logs
     */
    @Override
    public String toString() {
        return "MultipleCyclesFoundException: \n matchSegments: " + matchSegments + "\n cutSegments: " + cutSegments
                + N + "cutID: " + c.cutID + " " + topKnot + " cut1: " + cut1 + " ex1: " + ex1 + " cut2: " + cut2
                + " ex2: " + ex2 + " cutName: " + cutName + "\n\n" + this.getStackTrace()[0] + N;
    }

}
