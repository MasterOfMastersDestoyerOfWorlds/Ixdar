package shell.cuts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.commons.math3.util.Pair;

import shell.exceptions.SegmentBalanceException;
import shell.file.FileStringable;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.shell.Shell;

public class CutMatchList implements FileStringable {

    public ArrayList<CutMatch> cutMatches;
    public double delta;
    public Shell shell;
    SegmentBalanceException sbe;
    public Knot superKnot;

    public CutMatchList(Shell shell, SegmentBalanceException sbe, Knot superKnot) {
        cutMatches = new ArrayList<>();
        sbe.cutMatchList = this;
        this.shell = shell;
        this.sbe = sbe;
        this.superKnot = superKnot;
    }

    public CutMatchList(Shell shell,Knot superKnot) {
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
        if (!this.checkCutMatchBalance(c.lowerMatchSegment, c.upperMatchSegment, c.cutSegment1,
                cutSegments, c,
                false, true)) {
            shell.buff.add(internalCuts);

            throw new SegmentBalanceException(shell, this, c);
        }

    }

    public void addTwoCut(Segment cutSegment, Segment[] segments, Segment matchSegment1, Segment matchSegment2,
            VirtualPoint kp1, VirtualPoint kp2, CutMatchList cml, CutInfo c, boolean match1, String cutType)
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
        boolean balanced = this.checkCutMatchBalance(matchSegment1, matchSegment2, cutSegment, segments, c, true,
                true);
        shell.buff.add("BALANCE :" + balanced);
        if (!balanced) {
            CutMatch diff = diffKnots(cm, c, c.needTwoNeighborMatches, cutType);
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
            CutMatch diff = diffKnots(cm, c, c.needTwoNeighborMatches, cutType);
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
        ArrayList<Segment> innerNeighborSegments = c.innerNeighborSegments;
        MultiKeyMap<Integer, Segment> innerNeighborSegmentLookup = c.innerNeighborSegmentLookup;
        ArrayList<Segment> neighborSegments = c.neighborSegments;
        ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments = c.neighborCutSegments;
        Segment upperCutSegment = c.upperCutSegment;
        VirtualPoint topCutPoint = c.upperCutPoint;
        innerNeighborSegments = new ArrayList<Segment>(innerNeighborSegments);
        boolean hasCutSegment = false;
        if (neighborCutSegments.size() > 0) {//
            for (int i = 0; i < neighborCutSegments.size(); i++) {
                Segment neighborCutSegment = neighborCutSegments.get(i).getFirst();

                VirtualPoint neighbor = neighborCutSegments.get(i).getSecond();
                ArrayList<Segment> totalNeighborSegments = new ArrayList<Segment>(neighborSegments);
                VirtualPoint topKnotPoint = upperCutSegment.getOther(topCutPoint);
                if (neighbor.equals(topKnotPoint)) {
                    totalNeighborSegments.add(upperCutSegment);
                }
                if (!topCutPoint.equals(neighbor) || (topCutPoint.equals(neighbor) && needTwoNeighborMatches)
                        || (topCutPoint.equals(neighbor) && !needTwoNeighborMatches
                                && !neighborCutSegment.equals(upperCutSegment))) {
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
        ArrayList<Segment> diffMatchList = new ArrayList<>();
        for (int a = 0; a < subKnot.knotPoints.size(); a++) {
            VirtualPoint knotPoint11 = subKnot.knotPoints.get(a);
            VirtualPoint knotPoint12 = subKnot.knotPoints.get(a + 1 >= subKnot.knotPoints.size() ? 0 : a + 1);
            Segment s = subKnot.getSegment(knotPoint11, knotPoint12);
            subKnotSegments.add(s);

        }

        ArrayList<Segment> superKnotSegments = new ArrayList<>();
        ArrayList<Segment> diffCutList = new ArrayList<>();
        for (int a = 0; a < superKnot.knotPoints.size(); a++) {
            VirtualPoint knotPoint11 = superKnot.knotPoints.get(a);
            VirtualPoint knotPoint12 = superKnot.knotPoints.get(a + 1 >= superKnot.knotPoints.size() ? 0 : a + 1);
            Segment s = superKnot.getSegment(knotPoint11, knotPoint12);
            superKnotSegments.add(s);
            if (!subKnotSegments.contains(s) && !cm.matchSegments.contains(s) && subKnot.contains(knotPoint11)
                    && subKnot.contains(knotPoint12)
                    && !innerNeighborSegments.contains(s)
                    && !(innerNeighborSegmentsFlattened.contains(knotPoint12)
                            && innerNeighborSegmentsFlattened.contains(knotPoint11))) {

                diffCutList.add(s);
            }
        }

        for (Segment s : subKnotSegments) {
            if (!superKnotSegments.contains(s) && !cm.cutSegments.contains(s) && !s.equals(c.cutSegment1)
                    && !innerNeighborSegments.contains(s)) {
                if (!(c.balanceMap.externalBalance.get(s.first.id) == 2)
                        && !(c.balanceMap.externalBalance.get(s.last.id) == 2)) {
                    diffMatchList.add(s);
                }
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
        cmNew.cutSegments.addAll(diffCutList);
        cmNew.matchSegments.addAll(diffMatchList);
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
                if (!seenCuts.contains(s) && this.superKnot.hasSegment(s)) {
                    delta -= s.distance;
                    seenCuts.add(s);
                }
            }
            for (Segment s : cm.matchSegments) {
                if (!seenMatches.contains(s) && !this.superKnot.hasSegment(s)) {
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
        CutMatchList copy = new CutMatchList(shell, sbe, superKnot);
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
        for (Segment externalMatch : c.balanceMap.externalMatches) {
            allSegments.add(externalMatch);
        }
        for (Segment upperCuts : c.balanceMap.cuts) {
            allSegments.remove(upperCuts);
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
            boolean isExternal = false;
            boolean appearsTwice = false;
            int count = 0;
            for (VirtualPoint vp : c.balanceMap.externals) {
                if (vp.id == i) {
                    count++;
                    isExternal = true;
                    if (count >= 2) {
                        appearsTwice = true;
                    }
                }
            }
            if (isExternal && !appearsTwice) {
                int count2 = 0;
                for (Segment s : c.balanceMap.externalMatches) {

                    if (s.hasPoint(i)) {
                        count2++;
                    }
                }
                if (count2 == 2) {
                    appearsTwice = true;
                }
            }
            if (isExternal && !appearsTwice && val != 1) {
                shell.buff.add(printBalance, "external 1 " + isExternal + " "
                        + !appearsTwice + " "
                        + (val != 1));
                flag = false;
                breaki = i;
            } else if (isExternal && appearsTwice && val != 2) {
                flag = false;
                breaki = i;
                shell.buff.add(printBalance, "external 1 & 2 " + isExternal + " "
                        + appearsTwice + " "
                        + (val != 2));
            } else if (!isExternal && val != 2) {
                flag = false;
                breaki = i;
                shell.buff.add(printBalance,
                        "regular: " + !isExternal + " "
                                + (val != 2));
            }
        }

        // TODO: NEED TO CHECK THAT IF WE TRAVERSE FROM KNOT POINT TO KNOT POINT TAT WE
        // HIT ALL OF THE INTERNAL POINTS,
        // BASICALLY CAN'tHAVE MUTLIPLE CYCLES< UNSURE HOW TO CHECK WITH OUT FORMING A
        // NEW KNOT
        if (flag)

        {

        }

        if (!flag) {
            shell.buff.add(printBalance, this);
            shell.buff.add(printBalance, balance);
            shell.buff.add(printBalance, balance2);
            shell.buff.add(printBalance, s1);
            shell.buff.add(printBalance, s2);
            shell.buff.add(printBalance, cutSegment1);
            shell.buff.add(printBalance, cutSegments);

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
        for (Segment s : this.cutMatches.get(0).cutSegments) {
            fileString += s.first + " " + s.last + " ";
        }
        fileString += "MATCHES ";
        for (Segment s : this.cutMatches.get(0).matchSegments) {
            fileString += s.first + " " + s.last + " ";
        }
        return fileString;
    }

}