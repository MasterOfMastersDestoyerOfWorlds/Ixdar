package ixdar.geometry.cuts;

import java.util.ArrayList;

import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.commons.math3.util.Pair;

import ixdar.common.exceptions.BalancerException;
import ixdar.common.exceptions.SegmentBalanceException;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.shell.Shell;

public class CutInfo {
    public static final String N = "\n";
    static int numCuts = 0;
    public Knot knot;
    public Segment cutSegment1;
    public Knot kp1;
    public Knot cp1;
    public Knot superKnot;

    public Segment kpSegment;

    public Knot upperCutPoint;

    public boolean needTwoNeighborMatches;
    public boolean bothKnotPointsInside;
    public boolean bothCutPointsOutside;

    public Knot upperKnotPoint;
    public Knot upperExternal;
    public Segment upperCutSegment;
    public Segment upperMatchSegment;

    public Knot lowerKnotPoint;
    public Knot lowerExternal;
    public Segment lowerCutSegment;
    public Segment lowerMatchSegment;
    public Shell shell;
    public Knot lowerCutPoint;
    public int cutID;
    public boolean bothKnotPointsOutside;
    public BalanceMap balanceMap;

    public boolean partialOverlaps;
    public boolean overlapOrientationCorrect;
    public boolean knotPointsConnected;
    private SegmentBalanceException sbe;

    /**
     * TODO: document {@code CutInfo}.
     *
     * @param shell TODO: describe
     * @param knot TODO: describe
     * @param external1 TODO: describe
     * @param external2 TODO: describe
     * @param cutSegment1 TODO: describe
     * @param kp1 TODO: describe
     * @param cp1 TODO: describe
     * @param superKnot TODO: describe
     * @param kpSegment TODO: describe
     * @param innerNeighborSegments TODO: describe
     * @param innerNeighborSegmentLookup TODO: describe
     * @param neighborSegments TODO: describe
     * @param neighborCutSegments TODO: describe
     * @param topCutPoint TODO: describe
     * @param needTwoNeighborMatches TODO: describe
     * @param bothKnotPointsInside TODO: describe
     * @param bothKnotPointsOutside TODO: describe
     * @param bothCutPointsOutside TODO: describe
     * @param upperKnotPoint TODO: describe
     * @param upperMatchSegment TODO: describe
     * @param upperCutSegment TODO: describe
     * @param lowerKnotPoint TODO: describe
     * @param lowerMatchSegment TODO: describe
     * @param lowerCutSegment TODO: describe
     * @param balanceMap TODO: describe
     */
    public CutInfo(Shell shell, Knot knot, Knot external1, Knot external2, Segment cutSegment1,
            Knot kp1, Knot cp1, Knot superKnot, Segment kpSegment,
            ArrayList<Segment> innerNeighborSegments, MultiKeyMap<Integer, Segment> innerNeighborSegmentLookup,
            ArrayList<Segment> neighborSegments, ArrayList<Pair<Segment, Knot>> neighborCutSegments,
            Knot topCutPoint, boolean needTwoNeighborMatches,
            boolean bothKnotPointsInside, boolean bothKnotPointsOutside, boolean bothCutPointsOutside,
            Knot upperKnotPoint, Segment upperMatchSegment, Segment upperCutSegment,
            Knot lowerKnotPoint, Segment lowerMatchSegment, Segment lowerCutSegment, BalanceMap balanceMap) {
        this.shell = shell;
        this.knot = knot;
        this.superKnot = superKnot;
        this.cutSegment1 = cutSegment1;
        this.kp1 = kp1;
        this.cp1 = cp1;
        this.kpSegment = kpSegment;

        numCuts++;
        cutID = numCuts;

        this.needTwoNeighborMatches = needTwoNeighborMatches;
        this.bothKnotPointsInside = bothKnotPointsInside;
        this.bothCutPointsOutside = bothCutPointsOutside;
        this.bothKnotPointsOutside = bothKnotPointsOutside;

        this.upperKnotPoint = upperKnotPoint;
        this.upperCutPoint = topCutPoint;
        this.upperCutSegment = upperCutSegment;
        this.upperMatchSegment = upperMatchSegment;
        this.upperExternal = upperMatchSegment.getOther(upperKnotPoint);

        this.lowerKnotPoint = lowerKnotPoint;
        this.lowerCutSegment = lowerCutSegment;
        this.lowerCutPoint = lowerCutSegment.getOther(lowerKnotPoint);
        this.lowerMatchSegment = lowerMatchSegment;
        this.lowerExternal = lowerMatchSegment.getOther(lowerKnotPoint);
        this.balanceMap = balanceMap;

        this.sbe = new SegmentBalanceException(shell, null, this);
    }

    // 12%
    /**
     * TODO: document {@code CutInfo}.
     *
     * @param shell TODO: describe
     * @param lowerKnotPoint TODO: describe
     * @param lowerCutPoint TODO: describe
     * @param lowerCutSegment TODO: describe
     * @param lowerExternal TODO: describe
     * @param upperKnotPoint TODO: describe
     * @param upperCutPoint TODO: describe
     * @param upperCutSegment TODO: describe
     * @param upperExternal TODO: describe
     * @param superKnot TODO: describe
     * @param balanceMap TODO: describe
     * @param knotPointsConnected TODO: describe
     * @throws BalancerException TODO: describe
     */
    public CutInfo(Shell shell, Knot lowerKnotPoint, Knot lowerCutPoint, Segment lowerCutSegment,
            Knot lowerExternal,
            Knot upperKnotPoint, Knot upperCutPoint, Segment upperCutSegment,
            Knot upperExternal,
            Knot superKnot, BalanceMap balanceMap, boolean knotPointsConnected) throws BalancerException {
        // 2.38%
        Segment s51 = lowerKnotPoint.getClosestSegment(lowerExternal, null);
        Segment s52 = upperKnotPoint.getClosestSegment(upperExternal, s51);
        Knot externalPoint51 = s51.getOther(lowerKnotPoint);
        Knot externalPoint52 = s52.getOther(upperKnotPoint);
        // 0%
        cutID = ++numCuts;
        this.shell = shell;
        this.knot = superKnot;
        this.superKnot = superKnot;

        this.cutSegment1 = lowerCutSegment;

        this.lowerExternal = externalPoint51;
        this.lowerKnotPoint = lowerKnotPoint;
        this.lowerCutSegment = lowerCutSegment;
        this.lowerCutPoint = lowerCutSegment.getOther(lowerKnotPoint);
        this.lowerMatchSegment = s51;

        this.upperKnotPoint = upperKnotPoint;
        this.upperMatchSegment = s52;
        this.upperCutPoint = upperCutPoint;
        this.upperExternal = externalPoint52;
        this.upperCutSegment = upperCutSegment;

        if (this.upperCutSegment.partialOverlaps(this.lowerCutSegment)) {
            this.partialOverlaps = true;
            if (lowerKnotPoint.equals(upperCutPoint) || lowerCutPoint.equals(upperKnotPoint)
                    || lowerKnotPoint.equals(upperKnotPoint)) {
                this.overlapOrientationCorrect = false;
            } else if (lowerCutPoint.equals(upperCutPoint)) {
                this.overlapOrientationCorrect = true;
            }
        } else {
            this.partialOverlaps = false;
            this.overlapOrientationCorrect = true;
        }

        this.knotPointsConnected = knotPointsConnected;

    }

    /**
     * TODO: document {@code CutInfo}.
     *
     * @param shell TODO: describe
     * @param cutSegmentFinal TODO: describe
     * @param matchSegment1Final TODO: describe
     * @param cutSegment2Final TODO: describe
     * @param matchSegment2Final TODO: describe
     * @param knot TODO: describe
     */
    public CutInfo(Shell shell, Segment cutSegmentFinal, Segment matchSegment1Final, Segment cutSegment2Final,
            Segment matchSegment2Final, Knot knot) {
        numCuts++;
        cutID = numCuts;
        this.shell = shell;
        this.knot = knot;
        this.superKnot = knot;
        this.cutSegment1 = cutSegmentFinal;
        this.lowerCutSegment = cutSegmentFinal;
        this.lowerMatchSegment = matchSegment1Final;
        this.upperCutSegment = cutSegment2Final;
        this.upperMatchSegment = matchSegment2Final;
        this.superKnot = knot;
    }

    /**
     * TODO: document {@code CutInfo}.
     *
     * @param c TODO: describe
     */
    public CutInfo(CutInfo c) {
        this.shell = c.shell;
        this.knot = c.knot;
        this.superKnot = c.superKnot;
        this.cutSegment1 = c.cutSegment1;
        this.kp1 = c.kp1;
        this.cp1 = c.cp1;
        this.kpSegment = c.kpSegment;

        this.cutID = c.cutID;

        this.needTwoNeighborMatches = c.needTwoNeighborMatches;
        this.bothKnotPointsInside = c.bothKnotPointsInside;
        this.bothCutPointsOutside = c.bothCutPointsOutside;
        this.bothKnotPointsOutside = c.bothKnotPointsOutside;

        this.upperKnotPoint = c.upperKnotPoint;
        this.upperCutPoint = c.upperCutPoint;
        this.upperCutSegment = c.upperCutSegment;
        this.upperMatchSegment = c.upperMatchSegment;
        this.upperExternal = c.upperExternal;

        this.lowerKnotPoint = c.lowerKnotPoint;
        this.lowerCutSegment = c.lowerCutSegment;
        this.lowerCutPoint = c.lowerCutPoint;
        this.lowerMatchSegment = c.lowerMatchSegment;
        this.lowerExternal = c.lowerExternal;
        this.balanceMap = c.balanceMap;

        this.partialOverlaps = c.partialOverlaps;
        this.overlapOrientationCorrect = c.overlapOrientationCorrect;

        this.sbe = c.sbe;

        this.knotPointsConnected = c.knotPointsConnected;

    }

    /**
     * TODO: document {@code toString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toString() {
        return "ID: " + cutID + " minKnot: " + knot
                + " | cutSegment1: "
                + cutSegment1 + " | kp1: " + kp1 + " | cp1: " + cp1 + " | superKnot: " + superKnot + " | kpSegment: "
                + kpSegment +

                " upperCutPointIsOutside: " + needTwoNeighborMatches + " bothKnotPOintsInside: "
                + bothKnotPointsInside + N +

                " lowerCutSegment: " + lowerCutSegment + " lowerKnotPoint: " + lowerKnotPoint + " lowerCutPoint"
                + lowerCutPoint + " lowerMatchSegment: "
                + lowerMatchSegment + " lowerExternal: " + lowerExternal + N +

                " upperCutSegment: " + upperCutSegment + " upperKnotPoint: " + upperKnotPoint + " upperCutPoint"
                + upperCutPoint + " upperMatchSegment: "
                + upperMatchSegment + " upperExternal: " + upperExternal;
    }

    /**
     * TODO: document {@code genNewSegmentBalanceException}.
     *
     * @return TODO: describe
     */
    public SegmentBalanceException genNewSegmentBalanceException() {

        return new SegmentBalanceException(shell, null, this);
    }

    /**
     * TODO: document {@code copyAndSwapExternals}.
     *
     * @throws SegmentBalanceException TODO: describe
     * @return TODO: describe
     */
    public CutInfo copyAndSwapExternals() throws SegmentBalanceException {
        CutInfo c = new CutInfo(this);
        Segment s41 = this.upperKnotPoint.getClosestSegment(this.lowerExternal, null);
        Segment s42 = this.lowerKnotPoint.getClosestSegment(this.upperExternal, s41);
        Knot externalPoint41 = s41.getOther(this.upperKnotPoint);
        Knot externalPoint42 = s42.getOther(this.lowerKnotPoint);
        c.lowerMatchSegment = s41;
        c.upperMatchSegment = s42;
        c.lowerExternal = externalPoint41;
        c.upperExternal = externalPoint42;
        c.knotPointsConnected = knotPointsConnected;

        c.sbe = new SegmentBalanceException(shell, null, c);
        if (this.overlapOrientationCorrect) {
            c.balanceMap = new BalanceMap(knot, c.sbe);
            c.balanceMap.addCut(lowerKnotPoint, lowerCutPoint);
            c.balanceMap.addCut(upperKnotPoint, upperCutPoint);
            c.balanceMap.addExternalMatch(lowerKnotPoint, externalPoint42, null);
            c.balanceMap.addExternalMatch(upperKnotPoint, externalPoint41, null);
        }
        if (!c.lowerMatchSegment.contains(c.lowerExternal)) {
            throw new SegmentBalanceException(shell, null, this);
        }
        return c;
    }

    /**
     * TODO: document {@code getExternalMatchPointFromCutSegment}.
     *
     * @param externalKnot TODO: describe
     * @param cutSegment TODO: describe
     * @param exclude TODO: describe
     * @return TODO: describe
     */
    public Knot getExternalMatchPointFromCutSegment(Knot externalKnot, Segment cutSegment,
            Knot exclude) {
        if (cutSegment.id == lowerCutSegment.id && externalKnot.contains(lowerExternal)
                && (exclude == null || exclude.id != lowerExternal.id)) {
            return lowerExternal;
        } else if (cutSegment.id == upperCutSegment.id && externalKnot.contains(upperExternal)
                && (exclude == null || exclude.id != upperExternal.id)) {
            return upperExternal;
        }
        return null;
    }

    /**
     * TODO: document {@code getKnotPointFromCutSegment}.
     *
     * @param externalKnot TODO: describe
     * @param cutSegment TODO: describe
     * @param exclude TODO: describe
     * @return TODO: describe
     */
    public Knot getKnotPointFromCutSegment(Knot externalKnot, Segment cutSegment,
            Knot exclude) {
        if (cutSegment.id == lowerCutSegment.id && externalKnot.contains(lowerExternal)
                && (exclude == null || exclude.id != lowerKnotPoint.id)) {
            return lowerKnotPoint;
        } else if (cutSegment.id == upperCutSegment.id && externalKnot.contains(upperExternal)
                && (exclude == null || exclude.id != upperKnotPoint.id)) {
            return upperKnotPoint;
        }
        return null;
    }

    /**
     * TODO: document {@code getCutSegmentFromKnotPoint}.
     *
     * @param prevMatchPoint TODO: describe
     * @return TODO: describe
     */
    public Segment getCutSegmentFromKnotPoint(Knot prevMatchPoint) {
        if (prevMatchPoint.id == lowerKnotPoint.id) {
            return lowerCutSegment;
        } else if (prevMatchPoint.id == upperKnotPoint.id) {
            return upperCutSegment;
        }
        return null;
    }

    /**
     * TODO: document {@code hasKnotPoint}.
     *
     * @param nextMatchPoint TODO: describe
     * @return TODO: describe
     */
    public boolean hasKnotPoint(Knot nextMatchPoint) {
        if (lowerKnotPoint.id == nextMatchPoint.id || upperKnotPoint.id == nextMatchPoint.id) {
            return true;
        }
        return false;
    }

    /**
     * TODO: document {@code getSbe}.
     *
     * @return TODO: describe
     */
    public SegmentBalanceException getSbe() {
        this.sbe = new SegmentBalanceException(shell, null, this);
        return sbe;
    }
}
