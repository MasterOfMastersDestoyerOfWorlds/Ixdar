package ixdar.common.exceptions;

import ixdar.geometry.cuts.CutInfo;
import ixdar.geometry.cuts.CutMatchList;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.shell.Shell;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;

public class SegmentBalanceException extends Exception {
    public static final String X = "X";
    public static final String STR = "-";
    public static final String SEGMENTBALANCEEXCEPTION = "SegmentBalanceException: ";
    public CutMatchList cutMatchList;
    public Knot topKnot;
    public Segment cut1;
    public Segment ex1;
    public Segment cut2;
    public Segment ex2;
    public String cutName;
    public Shell shell;
    public CutInfo c;
    public HyperString x1;
    public HyperString x2;

    /**
     * TODO: document {@code SegmentBalanceException}.
     *
     * @param c TODO: describe
     */
    public SegmentBalanceException(CutInfo c) {
        topKnot = c.superKnot;
        this.cut1 = c.lowerCutSegment;
        this.ex1 = c.lowerMatchSegment;
        this.cut2 = c.upperCutSegment;
        this.ex2 = c.upperMatchSegment;
        this.c = c;
        this.shell = c.shell;
    }

    /**
     * TODO: document {@code SegmentBalanceException}.
     *
     * @param shell TODO: describe
     * @param internalCut TODO: describe
     * @param c TODO: describe
     */
    public SegmentBalanceException(Shell shell, CutMatchList internalCut, CutInfo c) {
        cutMatchList = internalCut;
        topKnot = c.superKnot;
        this.cut1 = c.lowerCutSegment;
        this.ex1 = c.lowerMatchSegment;
        this.cut2 = c.upperCutSegment;
        this.ex2 = c.upperMatchSegment;
        this.shell = shell;
        this.c = c;
    }

    /**
     * TODO: document {@code SegmentBalanceException}.
     *
     * @param sbe TODO: describe
     */
    public SegmentBalanceException(SegmentBalanceException sbe) {
        cutMatchList = sbe.cutMatchList;
        topKnot = sbe.topKnot;
        cut1 = sbe.cut1;
        ex1 = sbe.ex1;
        cut2 = sbe.cut2;
        ex2 = sbe.ex2;
        this.c = sbe.c;
        this.shell = sbe.shell;
        cutName = sbe.cutName;
    }

    /**
     * TODO: document {@code SegmentBalanceException}.
     */
    public SegmentBalanceException() {
    }

    /**
     * TODO: document {@code initDraw}.
     */
    public void initDraw() {
        if(x1 != null){
            return;
        }
        x1 = new HyperString();
        x1.addWord(X, Color.RED);
        x2 = new HyperString();
        x2.addWord(X, Color.ORANGE);
    }

    /**
     * TODO: document {@code toString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toString() {
        Knot kp1 = c.lowerKnotPoint;
        Knot kp2 = c.upperKnotPoint;
        cutName = shell.knotName + "_cut" + kp1 + STR + cut1.getOther(kp1) + "and" + kp2
                + STR + cut2.getOther(kp2) + "\n" + cutMatchList;
        if (c != null) {
            return SEGMENTBALANCEEXCEPTION + "cutID: " + c.cutID + " " + topKnot + " cut1: " + cut1 + " ex1: " + ex1
                    + " cut2: " + cut2 + " ex2: " + ex2 + " cutName: " + cutName + "\n\n" + this.getStackTrace()[0];
        } else {
            return SEGMENTBALANCEEXCEPTION + this.getStackTrace()[0];
        }
    }

    /**
     * TODO: document {@code generateUnitTestFromCut}.
     */
    public void generateUnitTestFromCut() {
    }

}
