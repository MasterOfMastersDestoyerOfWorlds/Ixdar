package ixdar.common.exceptions;

import java.util.ArrayList;

import ixdar.geometry.cuts.CutInfo;
import ixdar.geometry.cuts.CutMatchList;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.shell.Shell;

public class MultipleCyclesFoundException extends SegmentBalanceException {
    public static final String N = "\n";
    ArrayList<Segment> matchSegments;
    ArrayList<Segment> cutSegments;

    /**
     * TODO: document {@code MultipleCyclesFoundException}.
     *
     * @param sbe TODO: describe
     */
    public MultipleCyclesFoundException(SegmentBalanceException sbe) {
        super(sbe);
    }

    /**
     * TODO: document {@code MultipleCyclesFoundException}.
     *
     * @param shell TODO: describe
     * @param internalCuts12 TODO: describe
     * @param matchSegments TODO: describe
     * @param cutSegments TODO: describe
     * @param c TODO: describe
     */
    public MultipleCyclesFoundException(Shell shell, CutMatchList internalCuts12, ArrayList<Segment> matchSegments,
            ArrayList<Segment> cutSegments, CutInfo c) {
        super(shell, internalCuts12, c);
        this.matchSegments = matchSegments;
        this.cutSegments = cutSegments;
    }

    /**
     * TODO: document {@code toString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toString() {
        return "MultipleCyclesFoundException: \n matchSegments: " + matchSegments + "\n cutSegments: " + cutSegments
                + N + "cutID: " + c.cutID + " " + topKnot + " cut1: " + cut1 + " ex1: " + ex1 + " cut2: " + cut2
                + " ex2: " + ex2 + " cutName: " + cutName + "\n\n" + this.getStackTrace()[0] + N;
    }

}
