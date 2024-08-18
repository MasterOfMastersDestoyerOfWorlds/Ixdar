package shell.cuts;

import java.util.ArrayList;

import shell.exceptions.InvalidCutException;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.shell.Shell;
import shell.utils.Utils;

public class CutMatch {
    public ArrayList<Segment> cutSegments;
    public ArrayList<Segment> matchSegments;
    public Knot knot;
    VirtualPoint kp1;
    VirtualPoint kp2;
    CutMatch diff;
    double delta;
    Knot superKnot;
    public Segment kpSegment;
    Shell shell;
    SegmentBalanceException sbe;

    Segment[] originalCutSegments;
    Segment[] originalMatchSegments;
    CutInfo c;
    String cutType;

    public CutMatch(String cutType, Shell shell, SegmentBalanceException sbe) {
        cutSegments = new ArrayList<>();
        matchSegments = new ArrayList<>();
        this.shell = shell;
        this.sbe = sbe;
        this.cutType = cutType;
    }

    public void updateDelta() {
        delta = 0;
        for (Segment s : cutSegments) {
            delta -= s.distance;
        }
        for (Segment s : matchSegments) {
            delta += s.distance;
        }

    }

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

        if (superKnot == null) {
            ArrayList<Segment> knotSegments = new ArrayList<>();
            for (int a = 0; a < knot.knotPoints.size(); a++) {
                VirtualPoint knotPoint11 = knot.knotPoints.get(a);
                VirtualPoint knotPoint12 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
                Segment s = knot.getSegment(knotPoint11, knotPoint12);
                knotSegments.add(s);
            }

            for (Segment s : matchSegments) {
                if (knotSegments.contains(s)) {
                    shell.buff.add(this);
                    throw new InvalidCutException("Matching Segment already in Knot", sbe);
                }
            }
        } else {
            ArrayList<Segment> superKnotSegments = new ArrayList<>();
            for (int a = 0; a < superKnot.knotPoints.size(); a++) {
                VirtualPoint knotPoint11 = superKnot.knotPoints.get(a);
                VirtualPoint knotPoint12 = superKnot.knotPoints.get(a + 1 >= superKnot.knotPoints.size() ? 0 : a + 1);
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

    public String toString() {
        String id = "-1";
        if (c != null) {
            id = c.cutID + "";
        }
        String str = "CM[\n" +
                "cutSegments: " + cutSegments + " \n" +
                "matchSegments: " + matchSegments + " \n" +
                "knot: " + knot + " \n" +
                "super: " + superKnot + " \n" +
                "diff: " + diff + " \n" +
                "kpSegment: " + kpSegment + " \n" +
                "delta: " + delta + " \n" +
                "original cuts: " + Utils.printArray(originalCutSegments) + " \n" +
                "original matches: " + Utils.printArray(originalMatchSegments) + " \n" +
                "Cut ID:" + id + " \n" +
                "Cut Type: " + cutType + " \n]";
        return str;

    }

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