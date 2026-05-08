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
     * Build a fully-specified cut descriptor with both upper and lower
     * knot-point/cut-point/match-segment triples already resolved by the caller.
     * Assigns a unique {@link #cutID} and seeds a fresh {@link SegmentBalanceException}.
     *
     * @param shell host shell
     * @param knot inner knot the cut applies to
     * @param external1 first external endpoint (carried for caller use)
     * @param external2 second external endpoint (carried for caller use)
     * @param cutSegment1 primary cut segment
     * @param kp1 primary knot point
     * @param cp1 primary cut point
     * @param superKnot enclosing knot for the cut
     * @param kpSegment segment between the two knot points
     * @param innerNeighborSegments inner neighbor segments (carried for caller use)
     * @param innerNeighborSegmentLookup multi-key lookup of inner neighbor segments
     * @param neighborSegments neighbor segments
     * @param neighborCutSegments neighbor segments paired with the knot they cut to
     * @param topCutPoint cut point on the upper side of the cut
     * @param needTwoNeighborMatches whether two neighbor matches are required
     * @param bothKnotPointsInside both knot points lie inside the inner knot
     * @param bothKnotPointsOutside both knot points lie outside the inner knot
     * @param bothCutPointsOutside both cut points lie outside the inner knot
     * @param upperKnotPoint upper knot point
     * @param upperMatchSegment upper match segment (its other endpoint becomes {@code upperExternal})
     * @param upperCutSegment upper cut segment
     * @param lowerKnotPoint lower knot point
     * @param lowerMatchSegment lower match segment (its other endpoint becomes {@code lowerExternal})
     * @param lowerCutSegment lower cut segment (its other endpoint becomes {@code lowerCutPoint})
     * @param balanceMap balance map tracking external matches and cuts for {@code knot}
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
     * Build a cut descriptor by deriving each external match segment as the
     * closest segment from its knot point to the supplied external hint, and
     * by classifying overlap of the two cut segments (partial overlap and
     * orientation correctness) from their shared endpoints.
     *
     * @param shell host shell
     * @param lowerKnotPoint lower knot point
     * @param lowerCutPoint lower cut point hint (used only for orientation check)
     * @param lowerCutSegment lower cut segment
     * @param lowerExternal hint for the lower external endpoint
     * @param upperKnotPoint upper knot point
     * @param upperCutPoint upper cut point hint (used only for orientation check)
     * @param upperCutSegment upper cut segment
     * @param upperExternal hint for the upper external endpoint
     * @param superKnot enclosing knot (also used as {@code knot})
     * @param balanceMap balance map for the cut
     * @param knotPointsConnected whether the two knot points are joined by a knot edge
     * @throws BalancerException if a closest-segment lookup violates balance constraints
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
     * Minimal constructor that records just the four resolved cut/match
     * segments against a knot (also reused as the super-knot).
     *
     * @param shell host shell
     * @param cutSegmentFinal final lower cut segment (also stored as {@code cutSegment1})
     * @param matchSegment1Final final lower match segment
     * @param cutSegment2Final final upper cut segment
     * @param matchSegment2Final final upper match segment
     * @param knot the knot the cut applies to
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
     * Copy constructor that aliases all reference fields and the cut id from {@code c}.
     *
     * @param c source descriptor to copy
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
     * Multi-line debug rendering listing the cut id, both knot/cut/match
     * triples, and the configuration flags.
     *
     * @return human-readable diagnostic string
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
     * Build a fresh {@link SegmentBalanceException} bound to this cut.
     *
     * @return newly constructed exception (also returned to callers that throw it)
     */
    public SegmentBalanceException genNewSegmentBalanceException() {

        return new SegmentBalanceException(shell, null, this);
    }

    /**
     * Copy this cut and swap which knot point matches each external endpoint,
     * recomputing match segments via closest-segment lookups. When the two
     * cut segments overlap with correct orientation, also rebuild a fresh
     * {@link BalanceMap} populated with the swapped cuts and external matches.
     *
     * @throws SegmentBalanceException if the recomputed lower match segment no longer contains its external
     * @return the swapped copy
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
     * Look up the external match point that pairs with {@code cutSegment}
     * (lower or upper), provided {@code externalKnot} contains it and it is
     * not the {@code exclude} point.
     *
     * @param externalKnot knot the external must lie within
     * @param cutSegment cut segment to identify which side of the cut to query
     * @param exclude an external to skip (or {@code null} for none)
     * @return the matching external, or {@code null} if no side qualifies
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
     * Look up the knot point that pairs with {@code cutSegment} (lower or
     * upper), provided {@code externalKnot} contains the matching external
     * and the knot point is not {@code exclude}.
     *
     * @param externalKnot knot the external must lie within
     * @param cutSegment cut segment identifying which side to query
     * @param exclude a knot point to skip (or {@code null} for none)
     * @return the matching knot point, or {@code null} if no side qualifies
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
     * Inverse of {@link #getKnotPointFromCutSegment}: returns the cut segment
     * (lower or upper) whose knot point matches the given point.
     *
     * @param prevMatchPoint the knot point to look up
     * @return the corresponding cut segment, or {@code null} if neither side matches
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
     * Whether the given knot is one of this cut's two knot points.
     *
     * @param nextMatchPoint candidate knot
     * @return {@code true} iff it equals the lower or upper knot point
     */
    public boolean hasKnotPoint(Knot nextMatchPoint) {
        if (lowerKnotPoint.id == nextMatchPoint.id || upperKnotPoint.id == nextMatchPoint.id) {
            return true;
        }
        return false;
    }

    /**
     * Replace and return the cached {@link SegmentBalanceException} bound to
     * this cut, refreshed from the current shell and cut state.
     *
     * @return the freshly created exception now stored on this descriptor
     */
    public SegmentBalanceException getSbe() {
        this.sbe = new SegmentBalanceException(shell, null, this);
        return sbe;
    }
}
