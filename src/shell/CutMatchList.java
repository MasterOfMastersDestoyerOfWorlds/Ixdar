package shell;

import java.util.ArrayList;
import java.util.HashMap;

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

    public CutMatch(Shell shell, SegmentBalanceException sbe) {
        cutSegments = new ArrayList<>();
        matchSegments = new ArrayList<>();
        this.shell = shell;
        this.sbe = sbe;
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
                float zero = 1 / 0;
            }
        }
        for (Segment s : matchSegments) {
            if (cutSegments.contains(s)) {
                float zero = 1 / 0;
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
                    throw new SegmentBalanceException(sbe);
                }
            }
        }
    }

    public String toString() {
        String str = "CM[\n" +
                "cutSegments: " + cutSegments + " \n" +
                "matchSegments: " + matchSegments + " \n" +
                "knot: " + knot + " \n" +
                "super: " + superKnot + " \n" +
                "diff: " + diff + " \n" +
                "kpSegment: " + kpSegment + " \n" +
                "delta: " + delta + " \n]";
        return str;

    }

    public CutMatch copy() {
        CutMatch copy = new CutMatch(shell, sbe);
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

    public CutMatchList(Shell shell, SegmentBalanceException sbe) {
        cutMatches = new ArrayList<>();
        sbe.cutMatchList = this;
        this.shell = shell;
        this.sbe = sbe;
    }

    public String toString() {
        String str = "CML[\n" + cutMatches + " \n]\n totalDelta: " + delta;
        return str;

    }

    public void addCut(Segment cutSegment, Segment matchSegment1, Segment matchSegment2, Knot knot,
            VirtualPoint kp1, VirtualPoint kp2) throws SegmentBalanceException {
        CutMatch cm = new CutMatch(shell, sbe);
        cm.cutSegments.add(cutSegment);
        cm.matchSegments.add(matchSegment1);
        cm.matchSegments.add(matchSegment2);
        cm.knot = knot;
        cm.kp1 = kp1;
        cm.kp2 = kp2;
        cm.updateDelta();
        cm.checkValid();
        delta += cm.delta;
        cutMatches.add(cm);
    }

    public void addCut(Segment cutSegment, Segment matchSegment1, Segment matchSegment2, Knot knot,
            VirtualPoint kp1, VirtualPoint kp2, Knot superKnot, Segment kpSegment,
            ArrayList<Segment> innerNeighborSegments,  MultiKeyMap<Integer, Segment> innerNeighborSegmentLookup, ArrayList<Segment> neighborSegments,
            ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments, Segment upperCutSegment,
            VirtualPoint topCutPoint, boolean match1, boolean needTwoNeighborMatches)
            throws SegmentBalanceException {
        CutMatch cm = new CutMatch(shell, sbe);
        if (match1) {
            cm.matchSegments.add(matchSegment1);
        }
        cm.matchSegments.add(matchSegment2);
        cm.knot = knot;
        cm.kp1 = kp1;
        cm.kp2 = kp2;
        cm.superKnot = superKnot;

        cutMatches.add(cm);
        boolean balanced = this.checkCutMatchBalance(matchSegment1, matchSegment2, cutSegment, null,
                matchSegment1.getOther(kp1),
                matchSegment2.getOther(kp2), knot, neighborSegments, superKnot, true);
        if (!balanced) {
            CutMatch diff = diffKnots(knot, superKnot, cm, cutSegment, kpSegment, innerNeighborSegments, innerNeighborSegmentLookup,
                    neighborSegments, neighborCutSegments, upperCutSegment, topCutPoint, needTwoNeighborMatches);
            cm.diff = diff;
            cm.diff.kpSegment = kpSegment;
            cm.cutSegments.addAll(diff.cutSegments);
            cm.matchSegments.addAll(diff.matchSegments);
        }
        cm.updateDelta();
        cm.checkValid();
        delta += cm.delta;
    }

    public void addTwoCut(Segment cutSegment, Segment cutSegment2, Segment matchSegment1, Segment matchSegment2,
            Knot knot, VirtualPoint kp1, VirtualPoint kp2, CutMatchList cml) throws SegmentBalanceException {
        CutMatch cm = new CutMatch(shell, sbe);
        cm.cutSegments.add(cutSegment);
        cm.cutSegments.add(cutSegment2);
        cm.matchSegments.add(matchSegment1);
        cm.matchSegments.add(matchSegment2);
        cm.knot = knot;
        cm.kp1 = kp1;
        cm.kp2 = kp2;
        cutMatches.add(cm);
        for (CutMatch m : cml.cutMatches) {
            if (m.knot == knot) {
                cm.matchSegments.addAll(m.matchSegments);
                cm.cutSegments.addAll(m.cutSegments);
            } else {
                cutMatches.add(m);
            }
        }
        cm.updateDelta();
        cm.checkValid();
        delta += cm.delta;
    }

    public void addTwoCut(Segment cutSegment, Segment cutSegment2, Segment matchSegment1, Segment matchSegment2,
            Knot knot, VirtualPoint kp1, VirtualPoint kp2, CutMatchList cml, Knot superKnot, Segment kpSegment,
            ArrayList<Segment> innerNeighborSegments,  MultiKeyMap<Integer, Segment> innerNeighborSegmentLookup, ArrayList<Segment> neighborSegments,
            ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments, Segment upperCutSegment,
            VirtualPoint topCutPoint, boolean match1)
            throws SegmentBalanceException {
        CutMatch cm = new CutMatch(shell, sbe);
        cm.cutSegments.add(cutSegment2);
        if (match1) {
            cm.matchSegments.add(matchSegment1);
        }
        cm.matchSegments.add(matchSegment2);
        cm.knot = knot;
        cm.kp1 = kp1;
        cm.kp2 = kp2;
        cm.superKnot = superKnot;
        cutMatches.add(cm);
        for (CutMatch m : cml.cutMatches) {
            if (m.knot == knot) {
                cm.matchSegments.addAll(m.matchSegments);
                cm.matchSegments.addAll(m.cutSegments);
            } else {
                cutMatches.add(m);
            }
        }

        // need to check if the knot as is is balanced i.e. each VirtualPoint in the
        // knot has 2 matches
        // (and externals have 1) given the above cutmatch
        // if it is balanced nothing further is required, otherwise we need to check the
        // difference
        // between the knot and its super knot and add any missing segments from the
        // subknot
        // and cut any extra ones from the superknot
        // now how to check if its balanced ...
        boolean balanced = this.checkCutMatchBalance(matchSegment1, matchSegment2, cutSegment, cutSegment2,
                matchSegment1.getOther(kp1),
                matchSegment2.getOther(kp2), knot, neighborSegments, superKnot, true);
        shell.buff.add("BALANCE :" + balanced);
        if (!balanced) {
            CutMatch diff = diffKnots(knot, superKnot, cm, cutSegment, kpSegment, innerNeighborSegments, innerNeighborSegmentLookup,
                    neighborSegments, neighborCutSegments, upperCutSegment, topCutPoint, false);
            cm.cutSegments.addAll(diff.cutSegments);
            cm.matchSegments.addAll(diff.matchSegments);
            cm.diff = diff;
            cm.diff.kpSegment = kpSegment;
        }

        this.updateDelta();
        cm.checkValid();
    }

    public void addTwoCutTwoMatch(Segment cutSegment, Segment cutSegment2, Segment matchEx1, VirtualPoint knotPoint1,
            Segment matchEx2, VirtualPoint knotPoint2,
            Segment matchSegmentToUpperCutPoint1, VirtualPoint internalKnotPoint1,
            Segment matchSegmentToUpperCutPoint2, VirtualPoint internalKnotPoint2,
            Segment matchSegmentToUpperKnotPoint, VirtualPoint internalKnotPoint3,
            boolean bothKnotPointsInside, Knot knot, Knot superKnot,
            Segment kpSegment, ArrayList<Segment> innerNeighborSegments,  MultiKeyMap<Integer, Segment> innerNeighborSegmentLookup, ArrayList<Segment> neighborSegments,
            ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments, Segment upperCutSegment,
            VirtualPoint topCutPoint)
            throws SegmentBalanceException {

        shell.buff.add("MAKING TWO CUT TWO MATCH ---------------------=================");

        CutMatch cm = new CutMatch(shell, sbe);
        cm.cutSegments.add(cutSegment2);
        if (!matchSegmentToUpperCutPoint1.isDegenerate()) {
            cm.matchSegments.add(matchSegmentToUpperCutPoint1);
        }
        if (matchSegmentToUpperCutPoint2 != null && !matchSegmentToUpperCutPoint2.isDegenerate()) {
            cm.matchSegments.add(matchSegmentToUpperCutPoint2);
        }
        if (matchSegmentToUpperKnotPoint != null && !matchSegmentToUpperKnotPoint.isDegenerate()) {
            cm.matchSegments.add(matchSegmentToUpperKnotPoint);
        }
        cm.knot = knot;
        cm.kp1 = knotPoint1;
        cm.kp2 = knotPoint2;
        cm.superKnot = superKnot;
        cutMatches.add(cm);

        boolean balanced = this.checkCutMatchBalance(matchEx1, matchEx2, cutSegment, cutSegment2,
                matchEx1.getOther(knotPoint1),
                matchEx2.getOther(knotPoint2), knot, neighborSegments, superKnot,
                false);
        if (!balanced) {
            CutMatch diff = diffKnots(knot, superKnot, cm, cutSegment, kpSegment, innerNeighborSegments, innerNeighborSegmentLookup,
                    neighborSegments, neighborCutSegments, upperCutSegment, topCutPoint, true);
            cm.diff = diff;
            cm.diff.kpSegment = kpSegment;
            cm.cutSegments.addAll(diff.cutSegments);
            cm.matchSegments.addAll(diff.matchSegments);
        }
        cm.updateDelta();

        cm.checkValid();
        delta += cm.delta;

    }

    public void addSimpleMatch(Segment matchSegment, Knot knot) throws SegmentBalanceException {
        CutMatch cm = new CutMatch(shell, sbe);
        cm.matchSegments.add(matchSegment);
        cm.knot = knot;
        cm.updateDelta();
        cm.checkValid();
        delta += cm.delta;
        cutMatches.add(cm);
    }

    public CutMatch diffKnots(Knot subKnot, Knot superKnot, CutMatch cm, Segment cutSegment, Segment kpSegment,
            ArrayList<Segment> innerNeighborSegments, MultiKeyMap<Integer, Segment> innerNeighborSegmentLookup, ArrayList<Segment> neighborSegments,
            ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments, Segment upperCutSegment,
            VirtualPoint topCutPoint, boolean needTwoNeighborMatches)
            throws SegmentBalanceException {
        innerNeighborSegments = new ArrayList<Segment>(innerNeighborSegments);
        boolean hasCutSegment = false;
        shell.buff.add("SUPER: !!!!!!!!!!!!!!! " + superKnot);
        if (neighborCutSegments.size() > 0) {
            for (int i = 0; i < neighborCutSegments.size(); i++) {
                Segment neighborCutSegment = neighborCutSegments.get(i).getFirst();

                VirtualPoint neighbor = neighborCutSegments.get(i).getSecond();
                if (upperCutSegment.hasPoints(10, 0) && superKnot.knotPoints.size() > 4 && neighborCutSegment.hasPoints(5, 0)) {
                    float z = 0;
                }
                if (neighborCutSegments.size() > 1) {
                    float z = 1;
                }
                ArrayList<Segment> totalNeighborSegments = new ArrayList<Segment>(neighborSegments);
                VirtualPoint topKnotPoint = upperCutSegment.getOther(topCutPoint);
                if (neighbor.equals(topKnotPoint)) {
                    totalNeighborSegments.add(upperCutSegment);
                }
                if (!topCutPoint.equals(neighbor) || (topCutPoint.equals(neighbor) && needTwoNeighborMatches)) {
                    VirtualPoint innerNeighbor = neighborCutSegment.getOther(neighbor);
                    boolean newMatch = false;

                    int neighborSegmentsTarget = 1;
                    if (topCutPoint.equals(neighbor) && needTwoNeighborMatches) {
                        neighborSegmentsTarget = 2;
                    } else if (needTwoNeighborMatches) {
                        int count = 0;
                        for (Pair<Segment, VirtualPoint> ncs : neighborCutSegments) {
                            if(ncs.getFirst().contains(neighbor)){
                                count ++;
                            }
                        }
                        if(count >= 2){
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
                            Segment lookup = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(neighborCutSegment), Segment.getLastOrderId(neighborCutSegment));
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
            return new CutMatch(shell, sbe);
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
            if (!superKnotSegments.contains(s) && !cm.cutSegments.contains(s) && !s.equals(cutSegment)
                    && !s.equals(kpSegment) && !innerNeighborSegments.contains(s)) {
                diffList.add(s);
            }
        }
        
        shell.buff.add(diffList);
        shell.buff.add(diffList2);
        
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

        CutMatch cmNew = new CutMatch(shell, sbe);
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
        delta = 0;
        for (CutMatch cm : cutMatches) {
            cm.updateDelta();
            delta += cm.delta;
        }
    }

    public void addNeighborCut(Segment neighborCut, Knot knot, CutMatchList cml) throws SegmentBalanceException {
        CutMatch cm = new CutMatch(shell, sbe);
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
        CutMatchList copy = new CutMatchList(shell, sbe);
        copy.delta = delta;
        for (CutMatch cm : cutMatches) {
            CutMatch copyCM = cm.copy();
            copy.cutMatches.add(copyCM);

        }
        return copy;
    }

    public boolean checkCutMatchBalance(Segment s1, Segment s2, Segment cutSegment1, Segment cutSegment2,
            VirtualPoint external1, VirtualPoint external2, Knot knot, ArrayList<Segment> neighborSegments,
            Knot superKnot, boolean doubleCount) {
        HashMap<Integer, Integer> balance = new HashMap<>();
        for (int j = 0; j < superKnot.knotPointsFlattened.size(); j++) {
            VirtualPoint k1 = superKnot.knotPoints.get(j);
            VirtualPoint k2 = superKnot.knotPoints.get(j + 1 >= superKnot.knotPoints.size() ? 0 : j + 1);
            if (knot.contains(k1) && knot.contains(k2)) {
                balance.put(k1.id, balance.getOrDefault(k1.id, 0) + 1);
                balance.put(k2.id, balance.getOrDefault(k2.id, 0) + 1);
            }
        }
        balance.put(cutSegment1.first.id, balance.getOrDefault(cutSegment1.first.id, 0) - 1);
        balance.put(cutSegment1.last.id, balance.getOrDefault(cutSegment1.last.id, 0) - 1);
        if (!doubleCount) {
            balance.put(cutSegment2.first.id, balance.getOrDefault(cutSegment2.first.id, 0) - 1);
            balance.put(cutSegment2.last.id, balance.getOrDefault(cutSegment2.last.id, 0) - 1);
        }
        balance.put(s1.first.id, balance.getOrDefault(s1.first.id, 0) + 1);
        balance.put(s1.last.id, balance.getOrDefault(s1.last.id, 0) + 1);
        if (!doubleCount) {
            balance.put(s2.first.id, balance.getOrDefault(s2.first.id, 0) + 1);
            balance.put(s2.last.id, balance.getOrDefault(s2.last.id, 0) + 1);
        }
        VirtualPoint external1Point = s1.getKnotPoint(external1.knotPointsFlattened);
        VirtualPoint external2Point = s2.getKnotPoint(external2.knotPointsFlattened);

        for (Segment s : neighborSegments) {
            if (knot.contains(s.first)) {
                balance.put(s.first.id, balance.getOrDefault(s.first.id, 0) + 1);
            } else if (knot.contains(s.last)) {
                balance.put(s.last.id, balance.getOrDefault(s.last.id, 0) + 1);
            }
        }
        for (CutMatch cm : cutMatches) {
            for (Segment s : cm.cutSegments) {
                balance.put(s.first.id, balance.getOrDefault(s.first.id, 0) - 1);
                balance.put(s.last.id, balance.getOrDefault(s.last.id, 0) - 1);
            }
            for (Segment s : cm.matchSegments) {
                balance.put(s.first.id, balance.getOrDefault(s.first.id, 0) + 1);
                balance.put(s.last.id, balance.getOrDefault(s.last.id, 0) + 1);
            }
        }
        boolean flag = true;
        int breaki = -1;
        for (Integer i : balance.keySet()) {
            int val = balance.get(i);
            if (i == external1Point.id && !external1Point.equals(external2Point) && val != 1) {
                shell.buff.add("external 1 " + (i == external1Point.id) + " "
                        + (!external1Point.equals(external2Point)) + " "
                        + (val != 1));
                flag = false;
                breaki = i;
            } else if (i == external2Point.id && !external1Point.equals(external2Point) && val != 1) {
                flag = false;
                breaki = i;
                shell.buff.add("external 2 " + (i == external2Point.id) + " "
                        + (!external1Point.equals(external2Point)) + " "
                        + (val != 1));
            } else if (i == external1Point.id && external1Point.equals(external2Point) && val != 2) {
                flag = false;
                breaki = i;
                shell.buff.add("external 1 & 2 " + (i == external2Point.id) + " "
                        + (external1Point.equals(external2Point)) + " "
                        + (val != 2));
            } else if (i != external1Point.id && i != external2Point.id && val != 2) {
                flag = false;
                breaki = i;
                shell.buff.add("regular: " + (i != external1Point.id) + " " + (i != external2Point.id) + " "
                        + (val != 2) + " ext1id:  " + external1Point.id + " ext2id:  " + external2Point.id);
            }
        }
        if (!flag) {
            shell.buff.add(this);
            shell.buff.add(balance);
            shell.buff.add(s1);
            shell.buff.add(s2);
            shell.buff.add(cutSegment1);
            shell.buff.add(cutSegment2);

            shell.buff.add(external1);
            shell.buff.add(external2);

            shell.buff.add("breaki " + breaki);
        }
        return flag;
    }

    public void addDumbCutMatch(Knot knot, Knot superKnot) {
        CutMatch cm = new CutMatch(shell, sbe);
        cm.knot = knot;
        cutMatches.add(cm);
    }

    public void addCutDiff(Segment leftCut, Knot knot) {
        shell.buff.add("making left/right cut: " + leftCut);

        if (knot.hasSegment(leftCut)) {

            CutMatch cm = new CutMatch(shell, sbe);
            cm.cutSegments.add(leftCut);
            cm.knot = knot;
            cutMatches.add(cm);
            this.updateDelta();
        }
    }

}