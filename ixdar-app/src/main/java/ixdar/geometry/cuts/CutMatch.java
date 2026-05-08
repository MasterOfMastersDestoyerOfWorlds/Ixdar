package ixdar.geometry.cuts;

import java.util.ArrayList;

import ixdar.common.exceptions.InvalidCutException;
import ixdar.common.exceptions.SegmentBalanceException;
import ixdar.common.utils.Utils;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.shell.Shell;

public class CutMatch {
    public static final String N = " \n";
    public ArrayList<Segment> cutSegments;
    public ArrayList<Segment> matchSegments;
    public Knot knot;
    public double delta;
    public Segment kpSegment;
    public SegmentBalanceException sbe;
    public CutInfo c;
    Knot kp1;
    Knot kp2;
    CutMatch diff;
    double deltaInternal;
    Knot superKnot;
    Shell shell;

    Segment[] originalCutSegments;
    Segment[] originalMatchSegments;
    String cutType;

    /**
     * Build an empty cut/match pair tagged with a category label.
     *
     * @param cutType free-form label identifying which cut routine produced this
     * @param shell host shell used for buffered diagnostics
     * @param sbe balance exception attached to validation failures
     */
    public CutMatch(String cutType, Shell shell, SegmentBalanceException sbe) {
        cutSegments = new ArrayList<>();
        matchSegments = new ArrayList<>();
        this.shell = shell;
        this.sbe = sbe;
        this.cutType = cutType;
    }

    /**
     * Recompute {@code delta} (matches minus cuts by segment distance) and,
     * when a {@code superKnot} is set, the {@code deltaInternal} restricted
     * to segments whose endpoints both lie inside that super-knot.
     */
    public void updateDelta() {
        deltaInternal = 0;
        if (superKnot != null) {
            for (Segment s : cutSegments) {
                if (superKnot.contains(s.first) && superKnot.contains(s.last)) {
                    deltaInternal -= s.distance;
                }
            }
            for (Segment s : matchSegments) {
                if (superKnot.contains(s.first) && superKnot.contains(s.last)) {
                    deltaInternal += s.distance;
                }
            }
        }

        delta = 0;
        for (Segment s : cutSegments) {
            delta -= s.distance;
        }
        for (Segment s : matchSegments) {
            delta += s.distance;
        }

    }

    /**
     * Validate that no segment appears in both lists, and that match segments
     * do not collide with the (super)knot's existing perimeter.
     *
     * @throws SegmentBalanceException propagated from the underlying balance check
     * @throws InvalidCutException if a segment is both cut and matched, or a match reuses an existing knot segment
     */
    public void checkValid() throws SegmentBalanceException {
        for (Segment s : cutSegments) {
            if (matchSegments.contains(s)) {
                throw new InvalidCutException(sbe);
            }
        }
        for (Segment s : matchSegments) {
            if (cutSegments.contains(s)) {
                throw new InvalidCutException(sbe);
            }
        }

        if (superKnot == null && knot != null) {
            ArrayList<Segment> knotSegments = new ArrayList<>();
            for (int a = 0; a < knot.knotPoints.size(); a++) {
                Knot knotPoint11 = knot.knotPoints.get(a);
                Knot knotPoint12 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
                Segment s = knot.getSegment(knotPoint11, knotPoint12);
                knotSegments.add(s);
            }

            for (Segment s : matchSegments) {
                if (knotSegments.contains(s)) {
                    shell.buff.add(this);
                    throw new InvalidCutException("Matching Segment already in Knot", sbe);
                }
            }
        } else if (superKnot != null) {
            ArrayList<Segment> superKnotSegments = new ArrayList<>();
            for (int a = 0; a < superKnot.knotPoints.size(); a++) {
                Knot knotPoint11 = superKnot.knotPoints.get(a);
                Knot knotPoint12 = superKnot.knotPoints.get(a + 1 >= superKnot.knotPoints.size() ? 0 : a + 1);
                Segment s = superKnot.getSegment(knotPoint11, knotPoint12);
                superKnotSegments.add(s);
            }
            for (Segment s : cutSegments) {
                if (!superKnotSegments.contains(s)) {
                    shell.buff.add(this);
                    // throw new InvalidCutException(sbe);
                }
            }

        }
    }

    /**
     * Multi-line debug rendering of this cut/match: cuts, matches, knot,
     * super-knot, kpSegment, delta, original arrays, cut id and cut type.
     *
     * @return human-readable diagnostic string
     */
    @Override
    public String toString() {
        String id = "-1";
        if (c != null) {
            id = c.cutID + "";
        }
        String str = "CM[\n" +
                "cutSegments: " + cutSegments + N +
                "matchSegments: " + matchSegments + N +
                "knot: " + knot + N +
                "super: " + superKnot + N +
                "diff: " + diff + N +
                "kpSegment: " + kpSegment + N +
                "delta: " + delta + N +
                "original cuts: " + Utils.printArray(originalCutSegments) + N +
                "original matches: " + Utils.printArray(originalMatchSegments) + N +
                "Cut ID:" + id + N +
                "Cut Type: " + cutType + " \n]";
        return str;

    }

    /**
     * Deep-ish clone of this cut/match: copies cut/match segment lists, the
     * recursive {@code diff}, knot/super-knot/kp references, and {@code delta}.
     *
     * @return a new instance carrying the same configuration
     */
    public CutMatch copy() {
        CutMatch copy = new CutMatch(cutType, shell, sbe);
        copy.knot = knot;
        copy.delta = delta;
        copy.cutSegments.addAll(cutSegments);
        copy.matchSegments.addAll(matchSegments);
        if (diff != null) {
            copy.diff = diff.copy();
        }
        copy.superKnot = superKnot;
        copy.kp1 = kp1;
        copy.kp2 = kp2;
        return copy;
    }
}