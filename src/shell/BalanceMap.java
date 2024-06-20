package shell;

import java.util.ArrayList;
import java.util.HashMap;

public class BalanceMap {
    Knot knot;
    HashMap<Integer, Integer> balance;
    ArrayList<Segment> cuts;
    HashMap<Integer, Integer> externalBalance;
    HashMap<Integer, Integer> externalGroups;
    ArrayList<Segment> externalMatches;
    SegmentBalanceException sbe;
    static int numCuts = 0;
    int numGroups = 0;
    int ID;
    VirtualPoint topExternal1;
    VirtualPoint topExternal2;

    public BalanceMap(Knot knot, SegmentBalanceException sbe) {
        this.knot = knot;
        balance = new HashMap<>();
        cuts = new ArrayList<>();
        externalBalance = new HashMap<>();
        externalMatches = new ArrayList<>();
        externalGroups = new HashMap<>();
        for (VirtualPoint vp : knot.knotPointsFlattened) {
            balance.put(vp.id, 2);
            externalBalance.put(vp.id, 0);
        }
        this.sbe = sbe;
        ID = numCuts;
        this.numCuts++;
    }

    public BalanceMap(BalanceMap bMap, Knot subKnot, SegmentBalanceException sbe) {
        knot = subKnot;
        balance = new HashMap<>();
        externalBalance = new HashMap<>();
        cuts = new ArrayList<>(bMap.cuts);
        externalMatches = new ArrayList<>(bMap.externalMatches);
        for (VirtualPoint vp : subKnot.knotPointsFlattened) {
            if (bMap.balance.containsKey(vp.id)) {
                balance.put(vp.id, 2 - bMap.externalBalance.get(vp.id));
                externalBalance.put(vp.id, bMap.externalBalance.get(vp.id));
            } else {
                balance.put(vp.id, 2);
                externalBalance.put(vp.id, 0);
            }
        }
        externalGroups = new HashMap<>(bMap.externalGroups);
        this.sbe = sbe;
        this.ID = numCuts;
        this.numGroups = bMap.numGroups;
        this.topExternal1 = bMap.topExternal1;
        this.topExternal2 = bMap.topExternal2;
        numCuts++;
    }

    public void addExternalMatch(VirtualPoint vp, VirtualPoint external, Knot superKnot) throws BalancerException {
        int newBalance = externalBalance.get(vp.id) + 1;
        Segment newMatch = vp.getClosestSegment(external, null);
        if (newBalance > 2) {
            throw new BalancerException(vp, newMatch, sbe, "BAD External Match: ");
        }
        externalBalance.put(vp.id, newBalance);
        if (externalMatches.contains(newMatch)) {
            throw new BalancerException(vp, newMatch, sbe, "DUP External Match");
        }
        if (cuts.contains(newMatch)) {
            throw new BalancerException(vp, newMatch, sbe, "Matching Cut");
        }
        externalMatches.add(newMatch);

        if (topExternal1 != null && topExternal2 != null
                && (external.equals(topExternal1) || external.equals(topExternal2))) {
            float z = 0;
        }

        int groupId = -1;

        if (superKnot == null) {
            if (topExternal1 == null) {
                topExternal1 = external;
            } else {
                topExternal2 = external;
                groupId = externalGroups.get(topExternal1.id);
                externalGroups.put(vp.id, groupId);
                externalGroups.put(external.id, groupId);
            }
        }
        if (superKnot != null) {
            for (VirtualPoint sVP : superKnot.knotPointsFlattened) {
                if (!knot.contains(sVP)) {
                    if (externalGroups.containsKey(sVP.id)
                            && (!externalBalance.containsKey(sVP.id) || externalBalance.get(sVP.id) < 2)) {
                        groupId = externalGroups.get(sVP.id);
                        externalGroups.put(vp.id, groupId);
                    }
                }
            }
        }
        if (groupId == -1) {
            if (externalGroups.containsKey(external.id)) {
                groupId = externalGroups.get(external.id);
                externalGroups.put(vp.id, groupId);
            } else {
                groupId = numGroups;
                externalGroups.put(vp.id, groupId);
                externalGroups.put(external.id, groupId);
                numGroups++;
            }
        }
        if (superKnot != null) {
            for (VirtualPoint sVP : superKnot.knotPointsFlattened) {
                if (!knot.contains(sVP)) {
                    externalGroups.put(sVP.id, groupId);
                }
            }
        }
    }

    public void addCut(VirtualPoint vp1, VirtualPoint vp2) throws BalancerException {
        Segment newCut = vp1.getClosestSegment(vp2, null);
        if (!cuts.contains(newCut)) {
            int newBalance = balance.get(vp1.id) - 1;
            int newBalance2 = balance.get(vp2.id) - 1;
            if (newBalance < 0 || newBalance2 < 0) {
                // throw new BalancerException(vp1, vp2, sbe);
            }
            cuts.add(newCut);
            balance.put(vp1.id, newBalance);
            balance.put(vp2.id, newBalance);
        }
    }

    public void addInternalMatch(VirtualPoint vp1, VirtualPoint vp2) throws BalancerException {
        int newBalance = balance.get(vp1.id) + 1;
        int newBalance2 = balance.get(vp2.id) + 1;
        if (newBalance < 0 || newBalance2 < 0) {
            throw new BalancerException(sbe);
        }
        balance.put(vp1.id, newBalance);
        balance.put(vp2.id, newBalance);
    }

    public boolean canMatchTo(VirtualPoint vp) {
        if (externalBalance.get(vp.id) < 2) {
            return true;
        }
        return false;
    }

    public boolean canMatchTo(VirtualPoint vp, VirtualPoint ex1, Segment matchSegment1, VirtualPoint vp2,
            VirtualPoint ex2, Segment matchSegment2,
            Knot miKnot) {

        if (vp.equals(vp2) && ex1.equals(ex2)) {
            return false;
        }
        if (vp.equals(vp2) && externalGroups.get(ex1.id) == externalGroups.get(ex2.id)) {
            return false;
        }
        if (externalGroups.containsKey(vp.id) && externalGroups.containsKey(ex1.id)) {
            if (externalGroups.get(vp.id) == externalGroups.get(ex1.id) && !externalMatches.contains(matchSegment1)) {
                return false;
            }
        }
        if (externalGroups.containsKey(vp2.id) && externalGroups.containsKey(ex2.id)) {
            if (externalGroups.get(vp2.id) == externalGroups.get(ex2.id) && !externalMatches.contains(matchSegment2)) {
                return false;
            }
        }
        if (vp.equals(vp2) && !externalGroups.containsKey(ex2.id)) {
            float z = 0;
            int groupId = -1;
            if (miKnot.contains(knot.getPrev(ex2))) {
                VirtualPoint next = knot.getNext(ex2);
                while (!miKnot.contains(next)) {
                    if (externalGroups.containsKey(next.id)) {
                        groupId = externalGroups.get(next.id);
                        break;
                    }
                    next = knot.getNext(next);
                }
            } else {
                VirtualPoint prev = knot.getPrev(ex2);
                while (!miKnot.contains(prev)) {
                    if (externalGroups.containsKey(prev.id)) {
                        groupId = externalGroups.get(prev.id);
                        break;
                    }
                    prev = knot.getPrev(prev);
                }
            }
            if (externalGroups.get(ex1.id) == groupId) {
                return false;
            }
        }
        if (vp.equals(vp2) && externalBalance.get(vp.id) == 1 && externalMatches.contains(matchSegment1)) {
            return true;
        }
        if (vp.equals(vp2) && externalBalance.get(vp.id) == 0) {
            return true;
        } else if (!vp.equals(vp2) && externalBalance.get(vp.id) < 2 && externalBalance.get(vp2.id) < 2) {
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "BalanceMap: [\nexts: " + externalBalance.toString() + "\n" + "bal:" + balance.toString() + "\n]";
    }

    public boolean isBalanced() {
        boolean balanced = true;
        for (VirtualPoint vp : knot.knotPointsFlattened) {
            if (balance.get(vp.id) + externalBalance.get(vp.id) != 2) {
                return false;
            }
        }
        return balanced;
    }

    public boolean canCutSegments(VirtualPoint cutPoint1, Segment cutSegment1, VirtualPoint cutPoint2,
            Segment cutSegment2) {
        VirtualPoint vp1 = cutSegment1.first;
        VirtualPoint vp2 = cutSegment1.last;
        int matchesNeeded1 = Math.max(balance.get(vp1.id) - 1, 0) - (2 - externalBalance.get(vp1.id));
        int matchesNeeded2 = Math.max(balance.get(vp2.id) - 1, 0) - (2 - externalBalance.get(vp2.id));
        int matchesNeed = Math.max(balance.get(cutPoint1.id) - 1, 0) - (2 - externalBalance.get(cutPoint1.id));

        VirtualPoint vp3 = cutSegment2.first;
        VirtualPoint vp4 = cutSegment2.last;
        int matchesNeeded3 = cutSegment1.contains(vp3) ? 0
                : Math.max(balance.get(vp3.id) - 1, 0) - (2 - externalBalance.get(vp3.id));
        int matchesNeeded4 = cutSegment1.contains(vp4) ? 0
                : Math.max(balance.get(vp4.id) - 1, 0) - (2 - externalBalance.get(vp4.id));
        int matchesNeed2 = Math.max(balance.get(cutPoint2.id) - 1, 0) - (2 - externalBalance.get(cutPoint2.id));
        int nonLockedPoints = 0;
        for (VirtualPoint p : knot.knotPointsFlattened) {
            if (externalBalance.get(p.id) == 2) {
                continue;
            } else if (!p.equals(cutSegment1.getOther(cutPoint1)) && !p.equals(cutSegment2.getOther(cutPoint2))) {
                nonLockedPoints++;
            }
        }
        if (externalBalance.get(cutPoint1.id) < 2) {
            nonLockedPoints--;
        }
        if (externalBalance.get(cutPoint2.id) < 2) {
            nonLockedPoints--;
        }
        if (nonLockedPoints <= 0) {
            return false;
        }
        return true;
    }

    public boolean balancedOmega(VirtualPoint kp1, VirtualPoint cp1, Segment cutSegment1, VirtualPoint external1,
            Segment matchSegment1,
            VirtualPoint kp2,
            VirtualPoint cp2, Segment cutSegment2, VirtualPoint external2, Segment matchSegment2, Knot subKnot,
            CutInfo c, boolean wouldFormLoop) {
        if (cutSegment1.hasPoints(0, 3) &&
                cutSegment2.hasPoints(1, 0) && kp1.id == 0 && kp2.id == 0 && c.cutID == 104) {
            float z = 1;
        }
        if (kp1.equals(kp2) && wouldFormLoop) {
            return false;
        }

        int externalsKp1 = (externalBalance.get(kp1.id) + 1);
        boolean doubleCount = false;
        if (externalMatches.contains(matchSegment1)) {
            externalsKp1--;
            doubleCount = true;
        }
        if (kp1.equals(kp2)) {
            externalsKp1++;
        }
        int currMatchesKp1 = externalsKp1;
        int kp1Idx = subKnot.knotPointsFlattened.indexOf(kp1);
        Segment prevSegment = kp1.getClosestSegment(subKnot.getPrev(kp1Idx), null);
        if (!prevSegment.equals(cutSegment1) && !prevSegment.equals(cutSegment2) && !cuts.contains(prevSegment)) {
            currMatchesKp1++;
        }
        Segment nextSegment = kp1.getClosestSegment(subKnot.getNext(kp1Idx), null);
        if (!nextSegment.equals(cutSegment1) && !nextSegment.equals(cutSegment2) && !cuts.contains(nextSegment)) {
            currMatchesKp1++;
        }
        if (currMatchesKp1 > 2) {
            return false;
        }
        boolean hasPossibleMatch = currMatchesKp1 >= 2;
        if (!hasPossibleMatch) {
            ArrayList<VirtualPoint> allCutPoints = new ArrayList<>();
            if (cutSegment1.contains(kp1)) {
                allCutPoints.add(cp1);
            }
            if (cutSegment2.contains(kp1)) {
                allCutPoints.add(cutSegment2.getOther(kp1));
            }
            for (Segment s : cuts) {
                if (s.contains(kp1)) {
                    allCutPoints.add(s.getOther(kp1));
                }
            }
            for (VirtualPoint vp : subKnot.knotPointsFlattened) {
                if (!vp.equals(kp1)) {
                    int lockedInMatches = externalBalance.get(vp.id);
                    if (vp.equals(kp2)) {
                        lockedInMatches++;
                    }
                    if (lockedInMatches >= 2) {
                        continue;
                    }
                    if (allCutPoints.contains(vp)) {
                        continue;
                    }
                    hasPossibleMatch = true;
                    break;
                }
            }
        }
        if (!hasPossibleMatch) {
            return false;
        }

        int externalsKp2 = (externalBalance.get(kp2.id) + 1);
        if (kp1.equals(kp2)) {
            if (!doubleCount) {
                externalsKp2++;
            }
        }
        int currMatchesKp2 = externalsKp2;
        int Kp2Idx = subKnot.knotPointsFlattened.indexOf(kp2);
        Segment prevSegment2 = kp2.getClosestSegment(subKnot.getPrev(Kp2Idx), null);
        if (!prevSegment2.equals(cutSegment1) && !prevSegment2.equals(cutSegment2) && !cuts.contains(prevSegment2)) {
            currMatchesKp2++;
        }
        Segment nextSegment2 = kp2.getClosestSegment(subKnot.getNext(Kp2Idx), null);
        if (!nextSegment2.equals(cutSegment1) && !nextSegment2.equals(cutSegment2) && !cuts.contains(nextSegment2)) {
            currMatchesKp2++;
        }
        if (currMatchesKp2 > 2) {
            return false;
        }
        boolean hasPossibleMatch2 = currMatchesKp2 >= 2;
        if (!hasPossibleMatch2) {
            ArrayList<VirtualPoint> allCutPoints = new ArrayList<>();
            if (cutSegment1.contains(kp2)) {
                allCutPoints.add(cutSegment1.getOther(kp2));
            }
            if (cutSegment2.contains(kp2)) {
                allCutPoints.add(cp2);
            }
            for (Segment s : cuts) {
                if (s.contains(kp2)) {
                    allCutPoints.add(s.getOther(kp2));
                }
            }
            for (VirtualPoint vp : subKnot.knotPointsFlattened) {
                if (!vp.equals(kp2)) {
                    int lockedInMatches = externalBalance.get(vp.id);
                    if (vp.equals(kp1)) {
                        lockedInMatches++;
                    }
                    if (lockedInMatches >= 2) {
                        continue;
                    }
                    if (allCutPoints.contains(vp)) {
                        continue;
                    }
                    hasPossibleMatch2 = true;
                    break;
                }
            }
        }

        return hasPossibleMatch2;
    }

    public boolean balancedAlpha(VirtualPoint kp1, VirtualPoint cp1, Segment cutSegment1, VirtualPoint external1,
    Segment matchSegment1, Knot subKnot, CutInfo c) {
        int externalsKp1 = (externalBalance.get(kp1.id) + 1);
        int currMatchesKp1 = externalsKp1;
        int kp1Idx = subKnot.knotPointsFlattened.indexOf(kp1);
        Segment prevSegment = kp1.getClosestSegment(subKnot.getPrev(kp1Idx), null);
        VirtualPoint prevPoint = prevSegment.getOther(kp1);
        if (!prevSegment.equals(cutSegment1) && !cuts.contains(prevSegment) && externalBalance.get(prevPoint.id) == 0) {
            currMatchesKp1++;
        }
        Segment nextSegment = kp1.getClosestSegment(subKnot.getNext(kp1Idx), null);
        VirtualPoint nextPoint = nextSegment.getOther(kp1);
        if (!nextSegment.equals(cutSegment1) && !cuts.contains(nextSegment) && externalBalance.get(nextPoint.id) == 0) {
            currMatchesKp1++;
        }
        boolean doubleCount = false;
        if (externalMatches.contains(matchSegment1)) {
            currMatchesKp1--;
            doubleCount = true;
        }
        if (currMatchesKp1 > 2) {
            return false;
        }
        boolean hasPossibleMatch = currMatchesKp1 >= 2;
        if (!hasPossibleMatch) {
            ArrayList<VirtualPoint> allCutPoints = new ArrayList<>();
            if (cutSegment1.contains(kp1)) {
                allCutPoints.add(cp1);
            }
            for (Segment s : cuts) {
                if (s.contains(kp1)) {
                    allCutPoints.add(s.getOther(kp1));
                }
            }
            for (VirtualPoint vp : subKnot.knotPointsFlattened) {
                if (!vp.equals(kp1)) {
                    int lockedInMatches = externalBalance.get(vp.id);
                    if (lockedInMatches >= 2) {
                        continue;
                    }
                    if (allCutPoints.contains(vp)) {
                        continue;
                    }
                    hasPossibleMatch = true;
                    break;
                }
            }
        }
        return hasPossibleMatch;
    }

    public Segment getCutOutsideMinKnot(Knot minKnot, Knot k) {
        for (Segment s : cuts) {
            boolean first = minKnot.contains(s.first), last = minKnot.contains(s.last);
            if ((first && !last) || (last && !first)) {
                if (k.hasSegment(s)) {
                    return s;
                }
            }
        }
        return null;
    }

    public int getNumMatchesNeeded(VirtualPoint neighbor) {
        return 2 - externalBalance.get(neighbor.id);
    }

}
