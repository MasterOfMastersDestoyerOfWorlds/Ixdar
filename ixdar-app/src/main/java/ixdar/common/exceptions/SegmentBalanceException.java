package ixdar.common.exceptions;

import ixdar.geometry.cuts.CutInfo;
import ixdar.geometry.cuts.CutMatchList;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.shell.Shell;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;

/**
 * Base exception thrown when balancing a cut's match/cut segments against a
 * shell fails. Carries the surrounding {@link CutInfo}, super-knot, the two cut
 * and exemplar segments, and (optionally) the partial {@link CutMatchList} so
 * downstream rendering and logging can describe the failure precisely. Several
 * more specific failure modes subclass this type.
 */
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
     * Construct from a {@link CutInfo}, copying the super-knot, cut/exemplar
     * segments, and shell from it.
     *
     * @param c cut context being balanced when the failure occurred
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
     * Construct with a partial cut-match list in addition to the cut context.
     *
     * @param shell shell on which balancing was attempted
     * @param internalCut partial cut-match list produced before the failure
     * @param c cut context being balanced
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
     * Copy-construct, inheriting all diagnostic state from another instance.
     *
     * @param sbe source exception to copy from
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
     * No-arg constructor for callers that wrap the exception without context.
     */
    public SegmentBalanceException() {
    }

    /**
     * Lazily build the red/orange "X" hyperstrings used to render the failure
     * markers. No-op if already initialized.
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
     * Diagnostic string including cut ID, top knot, the two cut/exemplar
     * segments, and the constructed cut name. Falls back to a minimal form if
     * no {@link CutInfo} is available.
     *
     * @return human-readable representation for logs
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
     * Hook for emitting a regression test stub from the failing cut's state.
     * Empty by default; subclasses or tooling may override.
     */
    public void generateUnitTestFromCut() {
    }

}
