package shell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.commons.math3.util.Pair;

class CutMatch {
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

class CutMatchList {

    ArrayList<CutMatch> cutMatches;
    double delta;
    Shell shell;
    SegmentBalanceException sbe;
    Knot topKnot;

    public CutMatchList(Shell shell, SegmentBalanceException sbe, Knot superKnot) {
        cutMatches = new ArrayList<>();
        sbe.cutMatchList = this;
        this.shell = shell;
        this.sbe = sbe;
        this.topKnot = superKnot;
    }

    public String toString() {
        String str = "CML[ topKnot:" + topKnot + "\n" + cutMatches + " \n]\n totalDelta: " + delta;
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
        if (!this.checkCutMatchBalance(c.lowerMatchSegment, c.upperMatchSegment, c.cutSegment1,
                cutSegments, c,
                false, true)) {
            throw new SegmentBalanceException(shell, this, c);
        }

    }

    public void addTwoCut(Segment cutSegment, Segment[] segments, Segment matchSegment1, Segment matchSegment2,
            VirtualPoint kp1, VirtualPoint kp2, CutMatchList cml, CutInfo c, boolean match1, String cutType)
            throws SegmentBalanceException {
        CutMatch cm = new CutMatch(cutType, shell, sbe);
        for (Segment s : segments) {
            if (!cutSegment.equals(s) && !cm.cutSegments.contains(s)) {
                cm.cutSegments.add(s);
            }
        }
        if (match1) {
            cm.matchSegments.add(matchSegment1);
        }
        cm.c = c;
        cm.originalCutSegments = segments;
        cm.originalMatchSegments = new Segment[]{matchSegment1, matchSegment2};
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
        boolean balanced = this.checkCutMatchBalance(matchSegment1, matchSegment2, cutSegment, segments, c, true,
                false);
        shell.buff.add("BALANCE :" + balanced);
        if (!balanced) {
            CutMatch diff = diffKnots(cm, c, false, cutType);
            cm.cutSegments.addAll(diff.cutSegments);
            cm.matchSegments.addAll(diff.matchSegments);
            cm.diff = diff;
            cm.diff.kpSegment = c.kpSegment;
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

        if (!this.checkCutMatchBalance(c.lowerMatchSegment, c.upperMatchSegment, c.cutSegment1,
                segments, c,
                false, false)) {
            this.checkCutMatchBalance(c.lowerMatchSegment, c.upperMatchSegment, c.cutSegment1,
                    segments, c,
                    false, true);
            throw new SegmentBalanceException(shell, this, c);
        }
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

        boolean balanced = this.checkCutMatchBalance(c.lowerMatchSegment, c.upperMatchSegment, c.cutSegment1,
                cutSegments, c,
                false, true);
        if (!balanced) {
            CutMatch diff = diffKnots(cm, c, true, cutType);
            cm.diff = diff;
            cm.diff.kpSegment = c.kpSegment;
            cm.cutSegments.addAll(diff.cutSegments);
            cm.matchSegments.addAll(diff.matchSegments);
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

    public CutMatch diffKnots(CutMatch cm, CutInfo c,
            boolean needTwoNeighborMatches, String cutType)
            throws SegmentBalanceException {
        Knot subKnot = c.knot;
        Knot superKnot = c.superKnot;
        Segment kpSegment = c.kpSegment;
        ArrayList<Segment> innerNeighborSegments = c.innerNeighborSegments;
        MultiKeyMap<Integer, Segment> innerNeighborSegmentLookup = c.innerNeighborSegmentLookup;
        ArrayList<Segment> neighborSegments = c.neighborSegments;
        ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments = c.neighborCutSegments;
        Segment upperCutSegment = c.upperCutSegment;
        VirtualPoint topCutPoint = c.upperCutPoint;
        innerNeighborSegments = new ArrayList<Segment>(innerNeighborSegments);
        boolean hasCutSegment = false;
        if (neighborCutSegments.size() > 0) {
            for (int i = 0; i < neighborCutSegments.size(); i++) {
                Segment neighborCutSegment = neighborCutSegments.get(i).getFirst();

                VirtualPoint neighbor = neighborCutSegments.get(i).getSecond();
                ArrayList<Segment> totalNeighborSegments = new ArrayList<Segment>(neighborSegments);
                VirtualPoint topKnotPoint = upperCutSegment.getOther(topCutPoint);
                if (neighbor.equals(topKnotPoint)) {
                    totalNeighborSegments.add(upperCutSegment);
                }
                if (!topCutPoint.equals(neighbor) || (topCutPoint.equals(neighbor) && needTwoNeighborMatches)) {
                    VirtualPoint innerNeighbor = neighborCutSegment.getOther(neighbor);
                    int neighborSegmentsTarget = 1;
                    if (topCutPoint.equals(neighbor) && needTwoNeighborMatches) {
                        neighborSegmentsTarget = 2;
                    } else if (needTwoNeighborMatches) {
                        int count = 0;
                        for (Pair<Segment, VirtualPoint> ncs : neighborCutSegments) {
                            if (ncs.getFirst().contains(neighbor)) {
                                count++;
                            }
                        }
                        if (count >= 2) {
                            neighborSegmentsTarget = 2;
                        }
                    }
                    for (Segment match : cm.matchSegments) {
                        if (match.contains(neighbor) && !match.contains(innerNeighbor)) {
                            neighborSegmentsTarget--;
                        }
                    }
                    if (neighborSegmentsTarget <= 0) {
                        VirtualPoint vp1 = null;
                        VirtualPoint vp2 = null;
                        for (Segment s : totalNeighborSegments) {
                            if (s.contains(neighbor)) {
                                if (vp1 == null) {
                                    vp1 = s.getOther(neighbor);
                                } else {
                                    vp2 = s.getOther(neighbor);
                                    break;
                                }
                            }
                        }
                        Segment innerNeighborSegment = null;
                        for (Segment s : innerNeighborSegments) {
                            Segment lookup = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(neighborCutSegment),
                                    Segment.getLastOrderId(neighborCutSegment));
                            if ((s.contains(vp2) && s.contains(vp1)) || (lookup != null && lookup.equals(s))) {
                                innerNeighborSegment = s;
                                break;
                            }
                        }
                        innerNeighborSegments.remove(innerNeighborSegment);

                        for (Segment cut : cm.cutSegments) {
                            if (cut.contains(neighbor) && cut.contains(innerNeighbor)) {
                                hasCutSegment = true;
                            }
                        }
                        if (!hasCutSegment) {
                            cm.cutSegments.add(neighborCutSegment);
                        }
                    }

                }
            }
        }
        ArrayList<VirtualPoint> innerNeighborSegmentsFlattened = new ArrayList<>();
        for (Segment s : innerNeighborSegments) {
            innerNeighborSegmentsFlattened.add(s.first);
            innerNeighborSegmentsFlattened.add(s.last);
        }

        if (subKnot.equals(superKnot)) {
            return new CutMatch(cutType, shell, sbe);
        }
        ArrayList<Segment> subKnotSegments = new ArrayList<>();
        ArrayList<Segment> diffList = new ArrayList<>();
        for (int a = 0; a < subKnot.knotPoints.size(); a++) {
            VirtualPoint knotPoint11 = subKnot.knotPoints.get(a);
            VirtualPoint knotPoint12 = subKnot.knotPoints.get(a + 1 >= subKnot.knotPoints.size() ? 0 : a + 1);
            Segment s = subKnot.getSegment(knotPoint11, knotPoint12);
            subKnotSegments.add(s);

        }
        ArrayList<Segment> superKnotSegments = new ArrayList<>();
        ArrayList<Segment> diffList2 = new ArrayList<>();
        for (int a = 0; a < superKnot.knotPoints.size(); a++) {
            VirtualPoint knotPoint11 = superKnot.knotPoints.get(a);
            VirtualPoint knotPoint12 = superKnot.knotPoints.get(a + 1 >= superKnot.knotPoints.size() ? 0 : a + 1);
            Segment s = superKnot.getSegment(knotPoint11, knotPoint12);
            superKnotSegments.add(s);
            if (!subKnotSegments.contains(s) && !cm.matchSegments.contains(s) && subKnot.contains(knotPoint11)
                    && subKnot.contains(knotPoint12) && !s.equals(kpSegment)
                    && !innerNeighborSegments.contains(s)
                    && !(innerNeighborSegmentsFlattened.contains(knotPoint12)
                            && innerNeighborSegmentsFlattened.contains(knotPoint11))) {

                diffList2.add(s);
            }
        }

        for (Segment s : subKnotSegments) {
            if (!superKnotSegments.contains(s) && !cm.cutSegments.contains(s) && !s.equals(c.cutSegment1)
                    && !s.equals(kpSegment) && !innerNeighborSegments.contains(s)) {
                diffList.add(s);
            }
        }

        ArrayList<Segment> toRemoveCuts = new ArrayList<>();
        for (Segment s : cm.cutSegments) {
            if (!superKnotSegments.contains(s)) {
                toRemoveCuts.add(s);
            }
        }
        cm.cutSegments.removeAll(toRemoveCuts);
        ArrayList<Segment> toRemoveMatches = new ArrayList<>();
        for (Segment s : cm.matchSegments) {
            if (superKnotSegments.contains(s)) {
                toRemoveMatches.add(s);
            }
        }

        cm.matchSegments.removeAll(toRemoveMatches);

        CutMatch cmNew = new CutMatch(cutType, shell, sbe);
        // if(superKnot.hasSegment(kpSegment) && subKnot.hasSegment(kpSegment) &&
        // !diffList2.contains(kpSegment)){
        // diffList2.add(kpSegment);
        // }
        cmNew.cutSegments.addAll(diffList2);
        cmNew.matchSegments.addAll(diffList);
        cmNew.knot = superKnot;
        cmNew.updateDelta();
        cmNew.checkValid();
        return cmNew;
    }

    public void updateDelta() {
        delta = 0.0;
        ArrayList<Segment> seenCuts = new ArrayList<>();
        ArrayList<Segment> seenMatches = new ArrayList<>();
        for (CutMatch cm : cutMatches) {
            cm.updateDelta();
            for (Segment s : cm.cutSegments) {
                if (!seenCuts.contains(s) && this.topKnot.hasSegment(s)) {
                    delta -= s.distance;
                    seenCuts.add(s);
                }
            }
            for (Segment s : cm.matchSegments) {
                if (!seenMatches.contains(s) &&  !this.topKnot.hasSegment(s)) {
                    delta += s.distance;
                    seenMatches.add(s);
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

    public boolean hasMatchWith(VirtualPoint vp) {
        for (CutMatch cm : cutMatches) {
            for (Segment s : cm.matchSegments) {
                if (s.contains(vp)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Segment getMatchWith(VirtualPoint vp) {
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
        CutMatchList copy = new CutMatchList(shell, sbe, topKnot);
        copy.delta = delta;
        for (CutMatch cm : cutMatches) {
            CutMatch copyCM = cm.copy();
            copy.cutMatches.add(copyCM);

        }
        return copy;
    }

    public boolean checkCutMatchBalance(Segment s1, Segment s2, Segment cutSegment1, Segment[] cutSegments,
            CutInfo c, boolean doubleCount, boolean printBalance) {
        HashMap<Integer, Integer> balance = new HashMap<>();
        HashMap<Integer, Integer> balance2 = new HashMap<>();
        Set<Segment> allSegments = new HashSet<>();
        Knot superKnot = c.superKnot;
        Knot knot = c.knot;

        for (int j = 0; j < superKnot.size(); j++) {
            VirtualPoint k1 = superKnot.knotPoints.get(j);
            VirtualPoint k2 = superKnot.knotPoints.get(j + 1 >= superKnot.knotPoints.size() ? 0 : j + 1);
            if (knot.contains(k1) && knot.contains(k2)) {
                balance.put(k1.id, balance.getOrDefault(k1.id, 0) + 1);
                balance.put(k2.id, balance.getOrDefault(k2.id, 0) + 1);
                allSegments.add(knot.getSegment(k1, k2));
            }
            if (!knot.contains(k1) && !knot.contains(k2)) {
                allSegments.add(knot.getSegment(k1, k2));
            }
        }
        balance.put(cutSegment1.first.id, balance.getOrDefault(cutSegment1.first.id, 0) - 1);
        balance.put(cutSegment1.last.id, balance.getOrDefault(cutSegment1.last.id, 0) - 1);
        allSegments.remove(cutSegment1);
        if (!doubleCount) {
            for (Segment s : cutSegments) {
                balance.put(s.first.id, balance.getOrDefault(s.first.id, 0) - 1);
                balance.put(s.last.id, balance.getOrDefault(s.last.id, 0) - 1);
                allSegments.remove(s);
            }
        }
        balance.put(s1.first.id, balance.getOrDefault(s1.first.id, 0) + 1);
        balance.put(s1.last.id, balance.getOrDefault(s1.last.id, 0) + 1);
        allSegments.add(s1);
        if (!doubleCount) {
            balance.put(s2.first.id, balance.getOrDefault(s2.first.id, 0) + 1);
            balance.put(s2.last.id, balance.getOrDefault(s2.last.id, 0) + 1);
            allSegments.add(s2);
        }
        VirtualPoint externalPoint1 = c.lowerMatchSegment.getKnotPoint(c.lowerExternal.knotPointsFlattened);
        VirtualPoint externalPoint2 = c.upperMatchSegment.getKnotPoint(c.upperExternal.knotPointsFlattened);

        for (Segment s : c.neighborSegments) {
            if (knot.contains(s.first)) {
                balance.put(s.first.id, balance.getOrDefault(s.first.id, 0) + 1);
            } else if (knot.contains(s.last)) {
                balance.put(s.last.id, balance.getOrDefault(s.last.id, 0) + 1);
            }
            allSegments.add(s);
        }

        allSegments.remove(c.upperCutSegment);
        if (c.upperMatchSegment != null && !allSegments.contains(c.upperMatchSegment)) {
            allSegments.add(c.upperMatchSegment);
        }
        for (CutMatch cm : cutMatches) {
            for (Segment s : cm.cutSegments) {
                balance.put(s.first.id, balance.getOrDefault(s.first.id, 0) - 1);
                balance.put(s.last.id, balance.getOrDefault(s.last.id, 0) - 1);
                allSegments.remove(s);
            }
            for (Segment s : cm.matchSegments) {
                balance.put(s.first.id, balance.getOrDefault(s.first.id, 0) + 1);
                balance.put(s.last.id, balance.getOrDefault(s.last.id, 0) + 1);
                allSegments.add(s);
            }
        }
        for (Segment s : allSegments) {
            balance2.put(s.first.id, balance2.getOrDefault(s.first.id, 0) + 1);
            balance2.put(s.last.id, balance2.getOrDefault(s.last.id, 0) + 1);
        }
        balance = balance2;
        boolean flag = true;
        int breaki = -1;
        for (Integer i : balance.keySet()) {
            int val = balance.get(i);
            if (i == externalPoint1.id && !externalPoint1.equals(externalPoint2) && val != 1) {
                shell.buff.add(printBalance, "external 1 " + (i == externalPoint1.id) + " "
                        + (!externalPoint1.equals(externalPoint2)) + " "
                        + (val != 1));
                flag = false;
                breaki = i;
            } else if (i == externalPoint2.id && !externalPoint1.equals(externalPoint2) && val != 1) {
                flag = false;
                breaki = i;
                shell.buff.add(printBalance, "external 2 " + (i == externalPoint2.id) + " "
                        + (!externalPoint1.equals(externalPoint2)) + " "
                        + (val != 1));
            } else if (i == externalPoint1.id && externalPoint1.equals(externalPoint2) && val != 2) {
                flag = false;
                breaki = i;
                shell.buff.add(printBalance, "external 1 & 2 " + (i == externalPoint2.id) + " "
                        + (externalPoint1.equals(externalPoint2)) + " "
                        + (val != 2));
            } else if (i != externalPoint1.id && i != externalPoint2.id && val != 2) {
                flag = false;
                breaki = i;
                shell.buff.add(printBalance,
                        "regular: " + (i != externalPoint1.id) + " " + (i != externalPoint2.id) + " "
                                + (val != 2) + " ext1id:  " + externalPoint1.id + " ext2id:  " + externalPoint2.id);
            }
        }

        // TODO: NEED TO CHECK THAT IF WE TRAVERSE FROM KNOT POINT TO KNOT POINT TAT WE
        // HIT ALL OF THE INTERNAL POINTS,
        // BASICALLY CAN'tHAVE MUTLIPLE CYCLES< UNSURE HOW TO CHECK WITH OUT FORMING A
        // NEW KNOT
        if (flag) {

        }

        if (!flag) {
            shell.buff.add(printBalance, this);
            shell.buff.add(printBalance, balance);
            shell.buff.add(printBalance, balance2);
            shell.buff.add(printBalance, s1);
            shell.buff.add(printBalance, s2);
            shell.buff.add(printBalance, cutSegment1);
            shell.buff.add(printBalance, cutSegments);

            shell.buff.add(printBalance, "externalPoint1: " + externalPoint1);
            shell.buff.add(printBalance, "externalPoint2: " + externalPoint2);

            shell.buff.add(printBalance, c.lowerMatchSegment);
            shell.buff.add(printBalance, c.upperMatchSegment);
            shell.buff.add(printBalance, " allSegments: " + allSegments);

            shell.buff.add(printBalance, "breaki " + breaki);
        }
        return flag;
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

}