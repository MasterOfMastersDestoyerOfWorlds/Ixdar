package ixdar.geometry.cuts;

import java.util.ArrayList;
import java.util.Comparator;

import ixdar.common.exceptions.SegmentBalanceException;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.shell.Shell;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;
import ixdar.platform.file.FileStringable;

public class CutMatchList implements FileStringable {
    public static final String STR_2F = "%.2f";
    public static int cutMatchListComparisons = 0;

    public ArrayList<CutMatch> cutMatches;
    public double delta;
    public double internalDelta;
    public Shell shell;
    public CutInfo c;
    public SegmentBalanceException sbe;
    public Knot superKnot;

    /**
     * Construct an empty list bound to the given shell, cut context and parent knot.
     *
     * @param shell containing shell, used for logging and balance exceptions
     * @param c cut info describing the source of these cuts/matches
     * @param superKnot enclosing knot whose membership decides internal vs external segments
     */
    public CutMatchList(Shell shell, CutInfo c, Knot superKnot) {
        cutMatches = new ArrayList<>();
        this.shell = shell;
        this.superKnot = superKnot;
        this.c = c;
    }

    /**
     * Construct an empty list with no associated {@link CutInfo}.
     *
     * @param shell containing shell, used for logging and balance exceptions
     * @param superKnot enclosing knot whose membership decides internal vs external segments
     */
    public CutMatchList(Shell shell, Knot superKnot) {
        cutMatches = new ArrayList<>();
        this.shell = shell;
        this.superKnot = superKnot;
    }

    /**
     * Compact debug rendering of this list including the parent knot, every
     * contained {@link CutMatch} and the running {@link #delta}.
     *
     * @return debug string
     */
    @Override
    public String toString() {
        String str = "CML[ topKnot:" + superKnot + "\n" + cutMatches + " \n]\n totalDelta: " + delta;
        return str;

    }

    /**
     * Append a new {@link CutMatch} populated from the given segment arrays
     * and merge in any internal cut/match results that target the same
     * underlying knot (others are appended verbatim). The running deltas are
     * refreshed before the call returns.
     *
     * @param cutSegments segments to record as cuts on the new entry
     * @param matchSegments segments to record as matches on the new entry
     * @param internalCuts results from a recursive cut/match pass to merge in
     * @param c cut context for the new entry
     * @param cutType label identifying the operation source
     * @throws SegmentBalanceException propagated from {@link CutMatch#checkValid()}
     */
    public void addCutMatch(Segment[] cutSegments, Segment[] matchSegments, CutMatchList internalCuts, CutInfo c,
            String cutType)
            throws SegmentBalanceException {
        CutMatch cm = new CutMatch(cutType, shell, sbe);
        cm.c = c;
        for (Segment s : cutSegments) {
            cm.cutSegments.add(s);
        }
        for (Segment s : matchSegments) {
            cm.matchSegments.add(s);
        }
        cm.knot = c.knot;
        cm.kp1 = c.lowerKnotPoint;
        cm.kp2 = c.upperKnotPoint;
        cutMatches.add(cm);
        for (CutMatch m : internalCuts.cutMatches) {
            if (m.knot == c.knot) {
                cm.matchSegments.addAll(m.matchSegments);
                cm.cutSegments.addAll(m.cutSegments);
            } else {
                cutMatches.add(m);
            }
        }
        cm.updateDelta();

        cm.checkValid();
        this.updateDelta();

    }

    /**
     * Add a two-cut/two-match entry: cuts are taken from {@code segments}
     * (filtered against {@code c.balanceMap.cuts}), {@code matchSegment2} is
     * always added and {@code matchSegment1} is added when {@code match1} is
     * {@code true}. Internal results from {@code cml} are merged with the
     * same knot-aware rules as {@link #addCutMatch}, then segments not on the
     * super-knot are pruned and the deltas refreshed.
     *
     * @param cutSegment unused legacy parameter retained for signature stability
     * @param segments candidate cut segments to add (deduplicated against the entry and balance map)
     * @param matchSegment1 first match segment, added only when {@code match1} is {@code true}
     * @param matchSegment2 second match segment, always added
     * @param kp1 lower knot point on the parent knot
     * @param kp2 upper knot point on the parent knot
     * @param cml recursive results to merge in
     * @param c cut context for the new entry
     * @param match1 whether to add {@code matchSegment1}
     * @param cutType label identifying the operation source
     * @throws SegmentBalanceException propagated from {@link CutMatch#checkValid()}
     */
    public void addTwoCut(Segment cutSegment, Segment[] segments, Segment matchSegment1, Segment matchSegment2,
            Knot kp1, Knot kp2, CutMatchList cml, CutInfo c, boolean match1, String cutType)
            throws SegmentBalanceException {
        CutMatch cm = new CutMatch(cutType, shell, sbe);
        for (Segment s : segments) {
            if (!c.balanceMap.cuts.contains(s) && !cm.cutSegments.contains(s)) {
                cm.cutSegments.add(s);
            }
        }
        if (match1) {
            cm.matchSegments.add(matchSegment1);
        }
        cm.c = c;
        cm.originalCutSegments = segments;
        cm.originalMatchSegments = new Segment[] { matchSegment1, matchSegment2 };
        cm.matchSegments.add(matchSegment2);
        cm.knot = c.knot;
        cm.kp1 = kp1;
        cm.kp2 = kp2;
        cm.superKnot = c.superKnot;
        cutMatches.add(cm);
        for (CutMatch m : cml.cutMatches) {
            if (m.knot == c.knot) {
                cm.matchSegments.addAll(m.matchSegments);
                cm.cutSegments.addAll(m.cutSegments);
            } else {
                cutMatches.add(m);
            }
        }

        ArrayList<Segment> toRemove = new ArrayList<>();
        for (Segment s : cm.cutSegments) {
            if (!c.superKnot.hasSegment(s)) {
                toRemove.add(s);
            }
        }
        cm.cutSegments.removeAll(toRemove);

        ArrayList<Segment> toRemove2 = new ArrayList<>();
        for (Segment s : cm.matchSegments) {
            if (c.superKnot.hasSegment(s)) {
                toRemove2.add(s);
            }
        }
        cm.matchSegments.removeAll(toRemove2);
        this.updateDelta();
        cm.checkValid();
    }

    /**
     * Append a new {@link CutMatch} for a one-step move: every entry of
     * {@code cutSegments} is added as a cut and every non-degenerate entry of
     * {@code matchSegments} is added as a match. The entry's {@code kp1}/{@code kp2}
     * come from {@code c}; a {@code null} {@code kp2} signals an unbalanced
     * configuration and triggers a {@link SegmentBalanceException}. Cuts not
     * on the super-knot and matches that already are on it are pruned, then
     * the deltas refresh.
     *
     * @param cutSegments segments to record as cuts on the new entry
     * @param matchSegments segments to record as matches on the new entry (degenerates skipped)
     * @param c cut context whose lower/upper knot points anchor the new entry
     * @param cutType label identifying the operation source
     * @throws SegmentBalanceException when {@code c} lacks an upper knot point or the entry fails validation
     * @return the freshly created {@link CutMatch} entry
     */
    public CutMatch addCutMatch(Segment[] cutSegments,
            Segment[] matchSegments, CutInfo c, String cutType)
            throws SegmentBalanceException {
        // shell.buff.add("MAKING TWO CUT TWO MATCH
        // ---------------------=================");
        // shell.buff.add("cutSegment1 : " + c.cutSegment1 + " cutSegment2: " +
        // cutSegment2 + " "
        // + Utils.printArray(matchSegments));

        CutMatch cm = new CutMatch(cutType, shell, sbe);

        cm.originalCutSegments = cutSegments;
        cm.originalMatchSegments = matchSegments;
        cm.c = c;
        for (Segment s : cutSegments) {
            cm.cutSegments.add(s);
        }
        for (Segment s : matchSegments) {
            if (s != null && !s.isDegenerate()) {
                cm.matchSegments.add(s);
            }
        }
        cm.knot = c.knot;
        cm.kp1 = c.lowerKnotPoint;
        cm.kp2 = c.upperKnotPoint;
        if (cm.kp2 == null) {

            throw new SegmentBalanceException(shell, this, c);
        }
        cm.superKnot = c.superKnot;
        cutMatches.add(cm);

        ArrayList<Segment> toRemove = new ArrayList<>();
        for (Segment s : cm.cutSegments) {
            if (!c.superKnot.hasSegment(s)) {
                toRemove.add(s);
            }
        }
        cm.cutSegments.removeAll(toRemove);

        ArrayList<Segment> toRemove2 = new ArrayList<>();
        for (Segment s : cm.matchSegments) {
            if (c.superKnot.hasSegment(s)) {
                toRemove2.add(s);
            }
        }
        cm.matchSegments.removeAll(toRemove2);
        cm.updateDelta();

        cm.checkValid();

        this.updateDelta();
        return cm;
    }

    /**
     * Append a {@link CutMatch} consisting of a single match segment on the
     * given knot, with no associated cuts.
     *
     * @param matchSegment the lone match segment for the new entry
     * @param knot knot the entry belongs to
     * @param cutType label identifying the operation source
     * @throws SegmentBalanceException propagated from {@link CutMatch#checkValid()}
     */
    public void addSimpleMatch(Segment matchSegment, Knot knot, String cutType) throws SegmentBalanceException {
        CutMatch cm = new CutMatch(cutType, shell, sbe);
        cm.matchSegments.add(matchSegment);
        cm.knot = knot;
        cm.updateDelta();
        cm.checkValid();
        cutMatches.add(cm);
        this.updateDelta();
    }

    /**
     * Recompute {@link #delta} and {@link #internalDelta} from scratch.
     * Each contained {@link CutMatch} is refreshed, then unique cuts on the
     * super-knot subtract their distance from {@code delta} and unique matches
     * not yet on the super-knot add theirs; matches whose endpoints both lie
     * inside the super-knot also contribute to {@code internalDelta}.
     */
    public void updateDelta() {
        delta = 0.0;
        internalDelta = 0.0;
        ArrayList<Segment> seenCuts = new ArrayList<>();
        ArrayList<Segment> seenMatches = new ArrayList<>();
        for (CutMatch cm : cutMatches) {
            cm.updateDelta();
            if (superKnot != null) {
                for (Segment s : cm.cutSegments) {

                    if (!seenCuts.contains(s) && this.superKnot.hasSegment(s)) {
                        delta -= s.distance;
                        internalDelta -= s.distance;
                        seenCuts.add(s);
                    }
                }
                for (Segment s : cm.matchSegments) {
                    if (!seenMatches.contains(s) && !this.superKnot.hasSegment(s)) {
                        delta += s.distance;
                        if (this.superKnot.contains(s.first) && this.superKnot.contains(s.last)) {
                            internalDelta += s.distance;
                        }
                        seenMatches.add(s);
                    }
                }
            }
        }
    }

    /**
     * Append a {@link CutMatch} that records a cut on a neighbor knot and
     * absorbs the matches and cuts from any {@code cml} entries belonging to
     * the same knot (others are appended verbatim).
     *
     * @param neighborCut the cut segment on the neighbor knot
     * @param knot knot the new entry belongs to
     * @param cml recursive results to merge in
     * @param cutType label identifying the operation source
     * @throws SegmentBalanceException propagated from {@link CutMatch#checkValid()}
     */
    public void addNeighborCut(Segment neighborCut, Knot knot, CutMatchList cml, String cutType)
            throws SegmentBalanceException {
        CutMatch cm = new CutMatch(cutType, shell, sbe);
        cm.cutSegments.add(neighborCut);
        cm.knot = knot;

        for (CutMatch m : cml.cutMatches) {
            if (m.knot == knot) {
                cm.matchSegments.addAll(m.matchSegments);
                cm.matchSegments.addAll(m.cutSegments);
            } else {
                cutMatches.add(m);
            }
        }
        cutMatches.add(cm);
        this.updateDelta();
        cm.checkValid();
    }

    /**
     * Whether {@code s} appears as a match segment on any contained
     * {@link CutMatch}.
     *
     * @param s candidate segment
     * @return {@code true} if at least one entry holds {@code s} as a match
     */
    public boolean hasMatch(Segment s) {
        for (CutMatch cm : cutMatches) {
            if (cm.matchSegments.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any match segment in this list is incident to {@code vp}.
     *
     * @param vp candidate endpoint
     * @return {@code true} if at least one match contains {@code vp}
     */
    public boolean hasMatchWith(Knot vp) {
        for (CutMatch cm : cutMatches) {
            for (Segment s : cm.matchSegments) {
                if (s.contains(vp)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Return the first match segment in this list that is incident to
     * {@code vp}, or {@code null} when none exists.
     *
     * @param vp candidate endpoint
     * @return the matching segment, or {@code null}
     */
    public Segment getMatchWith(Knot vp) {
        for (CutMatch cm : cutMatches) {
            for (Segment s : cm.matchSegments) {
                if (s.contains(vp)) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * Drop {@code match} from every {@link CutMatch} in this list and refresh
     * the deltas.
     *
     * @param match match segment to remove
     */
    public void removeMatch(Segment match) {
        for (CutMatch cm : cutMatches) {
            if (cm.matchSegments.contains(match)) {
                cm.matchSegments.remove(match);
            }
        }
        this.updateDelta();
    }

    /**
     * Drop {@code cut} from every {@link CutMatch} in this list and refresh
     * the deltas.
     *
     * @param cut cut segment to remove
     */
    public void removeCut(Segment cut) {
        for (CutMatch cm : cutMatches) {
            if (cm.cutSegments.contains(cut)) {
                cm.cutSegments.remove(cut);
            }
        }
        this.updateDelta();
    }

    /**
     * Deep-copy this list and its {@link CutMatch} entries (each via
     * {@link CutMatch#copy()}), preserving {@link #delta}.
     *
     * @return an independent copy of this list
     */
    public CutMatchList copy() {
        CutMatchList copy = new CutMatchList(shell, c, superKnot);
        copy.delta = delta;
        for (CutMatch cm : cutMatches) {
            CutMatch copyCM = cm.copy();
            copy.cutMatches.add(copyCM);

        }
        return copy;
    }

    /**
     * Append a placeholder {@link CutMatch} that has no segments; used when
     * the algorithm needs to record a knot's presence without committing to
     * any cuts or matches yet.
     *
     * @param knot knot the placeholder is attached to
     * @param superKnot enclosing knot (kept for signature symmetry; not stored)
     * @param cutType label identifying the operation source
     */
    public void addDumbCutMatch(Knot knot, Knot superKnot, String cutType) {
        CutMatch cm = new CutMatch(cutType, shell, sbe);
        cm.knot = knot;
        cutMatches.add(cm);
    }

    /**
     * Append a {@link CutMatch} that records a single cut on {@code knot},
     * but only if the segment is genuinely part of that knot's manifold.
     * Refreshes the deltas on a successful add.
     *
     * @param leftCut candidate cut segment
     * @param knot knot to validate against and attach to
     * @param cutType label identifying the operation source
     */
    public void addCutDiff(Segment leftCut, Knot knot, String cutType) {
        shell.buff.add("making left/right cut: " + leftCut);

        if (knot.hasSegment(leftCut)) {

            CutMatch cm = new CutMatch(cutType, shell, sbe);
            cm.cutSegments.add(leftCut);
            cm.knot = knot;
            cutMatches.add(cm);
            this.updateDelta();
        }
    }

    /**
     * Append a {@link CutMatch} populated from two lists, copying them into
     * both the live segment collections and the original-segment snapshots
     * for replay/undo support.
     *
     * @param cutSegments cut segments to record on the new entry
     * @param matchSegments match segments to record on the new entry
     * @param knot knot the entry belongs to
     * @param cutType label identifying the operation source
     * @throws SegmentBalanceException propagated from {@link CutMatch#checkValid()}
     */
    public void addLists(ArrayList<Segment> cutSegments, ArrayList<Segment> matchSegments, Knot knot, String cutType)
            throws SegmentBalanceException {

        CutMatch cm = new CutMatch(cutType, shell, sbe);
        cm.cutSegments.addAll(cutSegments);
        cm.matchSegments.addAll(matchSegments);
        cm.originalCutSegments = cutSegments.toArray(new Segment[cutSegments.size()]);
        cm.originalMatchSegments = matchSegments.toArray(new Segment[matchSegments.size()]);
        cm.knot = knot;
        cm.updateDelta();
        cm.checkValid();
        cutMatches.add(cm);
        this.updateDelta();
        Segment[] cutSegmentsFinal = new Segment[cutSegments.size()];
        for (int i = 0; i < cutSegments.size(); i++) {
            cutSegmentsFinal[i] = cutSegments.get(i);
        }
    }

    /**
     * Serialize the first {@link CutMatch} as
     * {@code "CUTMATCH CUTS <a b ...> MATCHES <a b ...>"}, where each
     * {@code a b} pair is the endpoint ids of one segment.
     *
     * @return file-friendly representation of {@link #getCutMatch()}
     */
    @Override
    public String toFileString() {
        String fileString = "CUTMATCH CUTS ";
        for (Segment s : this.getCutMatch().cutSegments) {
            fileString += s.first + " " + s.last + " ";
        }
        fileString += "MATCHES ";
        for (Segment s : this.getCutMatch().matchSegments) {
            fileString += s.first + " " + s.last + " ";
        }
        return fileString;
    }

    /**
     * Of the two anchor points {@code kp1}/{@code kp2} on the first
     * {@link CutMatch}, return whichever yields the smaller summed distance
     * when paired against {@code neighbor} and {@code other}.
     *
     * @param neighbor first source knot
     * @param other second source knot
     * @return the closer anchor knot ({@code kp1} or {@code kp2})
     */
    public Knot getClosestKnotPoint(Knot neighbor, Knot other) {
        CutMatch cm = cutMatches.get(0);
        Segment kp1n1 = neighbor.getSegment(cm.kp1);
        Segment kp2n2 = other.getSegment(cm.kp2);
        Segment kp2n1 = neighbor.getSegment(cm.kp2);
        Segment kp1n2 = other.getSegment(cm.kp1);
        if (kp1n1.distance + kp2n2.distance < kp2n1.distance + kp1n2.distance) {
            return cm.kp1;
        } else {
            return cm.kp2;
        }
    }

    /**
     * Of the two anchor points {@code kp1}/{@code kp2} on the first
     * {@link CutMatch}, return the one that is not {@code knotPoint}.
     *
     * @param knotPoint anchor whose counterpart is requested
     * @return the other anchor knot, or {@code null} if {@code knotPoint} is neither
     */
    public Knot getOtherKp(Knot knotPoint) {
        CutMatch cm = cutMatches.get(0);
        if (cm.kp1.id == knotPoint.id) {
            return cm.kp2;
        } else if (cm.kp2.id == knotPoint.id) {
            return cm.kp1;
        }
        return null;
    }

    /**
     * The first (primary) {@link CutMatch} in this list.
     *
     * @return {@code cutMatches.get(0)}
     */
    public CutMatch getCutMatch() {
        return this.cutMatches.get(0);
    }

    /**
     * Render the primary {@link CutMatch} as an interactive table: matches
     * and cuts are paired row-by-row, the row's color depending on whether
     * the segment is one of the externally supplied lower/upper match/cut
     * segments. A trailing summary block reports match count and deltas.
     *
     * @param matchColor color for internal match segments
     * @param cutColor color for internal cut segments
     * @param externalColor color for the externally supplied match segments
     * @param externalCutColor color for the externally supplied cut segments
     * @return a clickable hyperstring representation
     */
    public HyperString toHyperString(Color matchColor, Color cutColor, Color externalColor, Color externalCutColor) {
        HyperString h = new HyperString();
        CutMatch cm = this.cutMatches.get(0);
        int maxSize = Math.max(cm.matchSegments.size(), cm.cutSegments.size());
        int internalCutCount = 0;
        int internalMatchCount = 0;
        for (int i = 0; i < maxSize; i++) {
            Segment match = i < cm.matchSegments.size() ? cm.matchSegments.get(i) : null;
            Segment cut = i < cm.cutSegments.size() ? cm.cutSegments.get(i) : null;
            if (match != null) {
                if (match.id == cm.c.lowerMatchSegment.id || match.id == cm.c.upperMatchSegment.id) {
                    h.addHyperString(match.toHyperString(externalColor, false));
                } else {
                    h.addHyperString(match.toHyperString(matchColor, false));
                    internalMatchCount++;
                }
            }
            if (cut != null) {
                if (cut.id == cm.c.lowerCutSegment.id || cut.id == cm.c.upperCutSegment.id) {
                    h.addHyperString(match.toHyperString(externalCutColor, false));
                } else {
                    h.addHyperString(cut.toHyperString(cutColor, false));
                    internalCutCount++;
                }
                h.newLine();
            }
        }

        int internalCount = Math.max(internalCutCount, internalMatchCount);
        h.newLine();
        h.addWord("MatchCount: ", matchColor);
        h.addLine(internalCount + "", Color.COMMAND);
        h.addWord("Delta: ", matchColor);
        h.addLine(String.format(STR_2F, delta), Color.COMMAND);
        h.addWord("IntDelta: ", matchColor);
        h.addLine(String.format(STR_2F, internalDelta) + "", Color.COMMAND);
        return h;
    }

    public static class CutMatchListComparator implements Comparator<CutMatchList> {

        /**
         * Order two lists by ascending {@link CutMatchList#delta}, falling
         * back to a raw-bits comparison when the deltas are equal so the
         * sort is total even for tied values. Increments
         * {@link CutMatchList#cutMatchListComparisons} for instrumentation.
         *
         * @param o1 first list
         * @param o2 second list
         * @return -1, 0 or 1 per {@link Comparator#compare}
         */
        @Override
        public int compare(CutMatchList o1, CutMatchList o2) {
            double d1 = o1.delta;
            double d2 = o2.delta;
            cutMatchListComparisons++;
            if (d1 < d2)
                return -1; // Neither val is NaN, thisVal is smaller
            if (d1 > d2)
                return 1; // Neither val is NaN, thisVal is larger

            // Cannot use doubleToRawLongBits because of possibility of NaNs.
            long thisBits = (long) d1;
            long anotherBits = (long) d2;

            return (thisBits == anotherBits ? 0 : // Values are equal
                    (thisBits < anotherBits ? -1 : // (-0.0, 0.0) or (!NaN, NaN)
                            1)); // (0.0, -0.0) or (NaN, !NaN)
        }
    }

}