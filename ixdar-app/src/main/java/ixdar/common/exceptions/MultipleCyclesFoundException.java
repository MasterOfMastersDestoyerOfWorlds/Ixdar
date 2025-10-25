package ixdar.common.exceptions;

import java.util.ArrayList;

import ixdar.geometry.cuts.CutInfo;
import ixdar.geometry.cuts.CutMatchList;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.shell.Shell;

public class MultipleCyclesFoundException extends SegmentBalanceException {
    ArrayList<Segment> matchSegments;
    ArrayList<Segment> cutSegments;

    public MultipleCyclesFoundException(SegmentBalanceException sbe) {
        super(sbe);
    }

    public MultipleCyclesFoundException(Shell shell, CutMatchList internalCuts12, ArrayList<Segment> matchSegments,
            ArrayList<Segment> cutSegments, CutInfo c) {
        super(shell, internalCuts12, c);
        this.matchSegments = matchSegments;
        this.cutSegments = cutSegments;
    }

    @Override
    public String toString() {
        return "MultipleCyclesFoundException: \n matchSegments: " + matchSegments + "\n cutSegments: " + cutSegments
                + "\n" + "cutID: " + c.cutID + " " + topKnot + " cut1: " + cut1 + " ex1: " + ex1 + " cut2: " + cut2
                + " ex2: " + ex2 + " cutName: " + cutName + "\n\n" + this.getStackTrace()[0] + "\n";
    }

}
