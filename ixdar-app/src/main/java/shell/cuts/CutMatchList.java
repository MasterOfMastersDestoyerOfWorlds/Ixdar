package shell.cuts;

import java.util.ArrayList;
import java.util.Comparator;

import shell.exceptions.SegmentBalanceException;
import shell.file.FileStringable;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.shell.Shell;

public class CutMatchList implements FileStringable {

    public ArrayList<CutMatch> cutMatches;
    public double delta;
    public double internalDelta;
    public Shell shell;
    public CutInfo c;
    public SegmentBalanceException sbe;
    public Knot superKnot;
    public static int cutMatchListComparisons = 0;

    public CutMatchList(Shell shell, CutInfo c, Knot superKnot) {
        cutMatches = new ArrayList<>();
        this.shell = shell;
        this.superKnot = superKnot;
        this.c = c;
    }

    public CutMatchList(Shell shell, Knot superKnot) {
        cutMatches = new ArrayList<>();
        this.shell = shell;
        this.superKnot = superKnot;
    }

    @Override
    public String toString() {
        String str = "CML[ topKnot:" + superKnot + "\n" + cutMatches + " \n]\n totalDelta: " + delta;
        return str;

    }

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

    public void addSimpleMatch(Segment matchSegment, Knot knot, String cutType) throws SegmentBalanceException {
        CutMatch cm = new CutMatch(cutType, shell, sbe);
        cm.matchSegments.add(matchSegment);
        cm.knot = knot;
        cm.updateDelta();
        cm.checkValid();
        cutMatches.add(cm);
        this.updateDelta();
    }

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

    public boolean hasMatch(Segment s) {
        for (CutMatch cm : cutMatches) {
            if (cm.matchSegments.contains(s)) {
                return true;
            }
        }
        return false;
    }

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

    public void removeMatch(Segment match) {
        for (CutMatch cm : cutMatches) {
            if (cm.matchSegments.contains(match)) {
                cm.matchSegments.remove(match);
            }
        }
        this.updateDelta();
    }

    public void removeCut(Segment cut) {
        for (CutMatch cm : cutMatches) {
            if (cm.cutSegments.contains(cut)) {
                cm.cutSegments.remove(cut);
            }
        }
        this.updateDelta();
    }

    public CutMatchList copy() {
        CutMatchList copy = new CutMatchList(shell, c, superKnot);
        copy.delta = delta;
        for (CutMatch cm : cutMatches) {
            CutMatch copyCM = cm.copy();
            copy.cutMatches.add(copyCM);

        }
        return copy;
    }

    public void addDumbCutMatch(Knot knot, Knot superKnot, String cutType) {
        CutMatch cm = new CutMatch(cutType, shell, sbe);
        cm.knot = knot;
        cutMatches.add(cm);
    }

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

    public static class CutMatchListComparator implements Comparator<CutMatchList> {

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

    public Knot getOtherKp(Knot knotPoint) {
        CutMatch cm = cutMatches.get(0);
        if (cm.kp1.id == knotPoint.id) {
            return cm.kp2;
        } else if (cm.kp2.id == knotPoint.id) {
            return cm.kp1;
        }
        return null;
    }

    public CutMatch getCutMatch() {
        return this.cutMatches.get(0);
    }

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
        h.addLine(String.format("%.2f", delta), Color.COMMAND);
        h.addWord("IntDelta: ", matchColor);
        h.addLine(String.format("%.2f", internalDelta) + "", Color.COMMAND);
        return h;
    }

}