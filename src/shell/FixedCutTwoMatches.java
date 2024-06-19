package shell;

import java.util.ArrayList;
import org.apache.commons.math3.util.Pair;

public class FixedCutTwoMatches extends FixedCut {

    public FixedCutTwoMatches(CutInfo c) {
        super(c);
    }

    @Override
    public CutMatchList findCutMatchListFixedCut() throws SegmentBalanceException, BalancerException {
        // TODO Auto-generated method stubSegmentBalanceException sbe = new
        // SegmentBalanceException(shell, null, superKnot, cutSegment1,

        if (upperMatchSegment == null) {
            new CutMatchList(shell, sbe, c.superKnot);
            throw new SegmentBalanceException(sbe);
        }
        if (!(knot.contains(cutSegment1.first) && knot.contains(cutSegment1.last))) {
            new CutMatchList(shell, sbe, c.superKnot);
            throw new SegmentBalanceException(sbe);
        }
        if ((knot.contains(external1) || knot.contains(external2))) {
            new CutMatchList(shell, sbe, c.superKnot);
            throw new SegmentBalanceException(sbe);
        }

        ArrayList<VirtualPoint> uniqueNeighborPoints = new ArrayList<>();
        ArrayList<Segment> uniqueNeighborSegments = new ArrayList<>();
        VirtualPoint otherNeighborPoint = external2;
        VirtualPoint otherNeighborPoint2 = external2;
        Segment otherNeighborSegment = c.superKnot.getOtherSegment(c.upperCutSegment, external2);
        uniqueNeighborPoints.add(external2);
        for (Pair<Segment, VirtualPoint> p : neighborCutSegments) {
            if (!p.getSecond().equals(external2)) {
                uniqueNeighborPoints.add(p.getSecond());
                uniqueNeighborSegments.add(p.getFirst());
            }
        }
        shell.buff.add(uniqueNeighborPoints);
        int numUnique = uniqueNeighborPoints.size();
        if (numUnique > 1) {
            uniqueNeighborPoints.remove(external2);
            otherNeighborPoint = uniqueNeighborPoints.get(0);
            otherNeighborSegment = uniqueNeighborSegments.get(0);
        }
        if (numUnique > 2) {
            shell.buff.add("REEE " + uniqueNeighborPoints);

            uniqueNeighborPoints.remove(otherNeighborPoint);
            otherNeighborPoint2 = uniqueNeighborPoints.get(0);
        }
        uniqueNeighborPoints.add(otherNeighborPoint);
        uniqueNeighborPoints.add(external2);
        shell.buff.add("unique neighbor points: " + numUnique +
                " otherNeghborPoint2: " + otherNeighborPoint2 + " onp: " + otherNeighborPoint + " ex2: " + external2);

        double minDelta = Double.MAX_VALUE;
        int overlapping = -1;
        CutMatchList result = null;

        Segment orgCutSegment = c.upperCutSegment;

        VirtualPoint knotPoint11 = cutSegment1.first;
        VirtualPoint knotPoint12 = cutSegment1.last;
        int numMatchesNeeded = c.balanceMap.getNumMatchesNeeded(external2);
        int numMatchesNeededOther = c.balanceMap.getNumMatchesNeeded(otherNeighborPoint);

        for (int b = 0; b < knot.knotPoints.size(); b++) {

            VirtualPoint knotPoint21 = knot.knotPoints.get(b);
            VirtualPoint knotPoint22 = knot.knotPoints.get(b + 1 >= knot.knotPoints.size() ? 0 : b + 1);

            Segment cutSegment2 = knot.getSegment(knotPoint21, knotPoint22);
            if (c.cutID == 11) {
                float z = 1;
            }
            double delta = Double.MAX_VALUE;

            CutMatchList cutMatch1 = tryCombo(knotPoint11, knotPoint12, external2, cutSegment1, knotPoint21,
                    knotPoint22, otherNeighborPoint, cutSegment2, orgCutSegment, otherNeighborSegment, numMatchesNeeded,
                    numMatchesNeededOther);
            double d1 = Double.MAX_VALUE;
            if (cutMatch1 != null) {
                d1 = cutMatch1.delta;
                delta = d1 < delta ? d1 : delta;
            }

            CutMatchList cutMatch2 = tryCombo(knotPoint12, knotPoint11, external2, cutSegment1, knotPoint22,
                    knotPoint21, otherNeighborPoint, cutSegment2, orgCutSegment, otherNeighborSegment, numMatchesNeeded,
                    numMatchesNeededOther);
            double d2 = Double.MAX_VALUE;
            if (cutMatch2 != null) {
                d2 = cutMatch2.delta;
                delta = d2 < delta ? d2 : delta;
            }

            CutMatchList cutMatch3 = tryCombo(knotPoint11, knotPoint12, external2, cutSegment1, knotPoint22,
                    knotPoint21, otherNeighborPoint, cutSegment2, orgCutSegment, otherNeighborSegment, numMatchesNeeded,
                    numMatchesNeededOther);
            double d3 = Double.MAX_VALUE;
            if (cutMatch3 != null) {
                d3 = cutMatch3.delta;
                delta = d3 < delta ? d3 : delta;
            }

            CutMatchList cutMatch4 = tryCombo(knotPoint12, knotPoint11, external2, cutSegment1, knotPoint21,
                    knotPoint22, otherNeighborPoint, cutSegment2, orgCutSegment, otherNeighborSegment, numMatchesNeeded,
                    numMatchesNeededOther);
            double d4 = Double.MAX_VALUE;
            if (cutMatch4 != null) {
                d4 = cutMatch4.delta;
                delta = d4 < delta ? d4 : delta;
            }

            if (delta < minDelta) {
                if (delta == d1) {
                    result = cutMatch1;
                } else if (delta == d2) {
                    result = cutMatch2;
                } else if (delta == d3) {
                    result = cutMatch3;
                } else if (delta == d4) {
                    result = cutMatch4;
                }
                minDelta = delta;
                overlapping = 2;
            }

        }

        if (overlapping == 2) {
            shell.buff.add("GOING WIHT Multi CUT");
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            CutMatchList cml = new CutMatchList(shell, sbe, c.superKnot);
            cml.addDumbCutMatch(knot, superKnot, "FixedCutTwoMatchesNoAvailableCuts");
            throw new SegmentBalanceException(sbe);
        }

    }

    public CutMatchList tryCombo(VirtualPoint kp1, VirtualPoint cp1, VirtualPoint external2, Segment cutSegment1,
            VirtualPoint kp2, VirtualPoint cp2, VirtualPoint otherNeighborPoint,
            Segment cutSegment2, Segment orgCutSegment, Segment otherNeighborSegment, int numMatchesNeeded,
            int numMatchesNeededOther)
            throws SegmentBalanceException {
        Segment matchSegment11 = kp1.getClosestSegment(external2, null);
        Segment matchSegment12 = kp2.getClosestSegment(otherNeighborPoint, null);

        Segment matchSegment21 = kp1.getClosestSegment(otherNeighborPoint, null);
        Segment matchSegment22 = kp2.getClosestSegment(external2, null);
        if (kp2.equals(cp2)) {
            float z = 1;
        }
        if (c.cutID == 110) {
            float z = 1;
        }
        boolean canMatch = true;
        boolean canMatchExternals = c.balanceMap.canMatchTo(kp1, external2, matchSegment11, kp2, otherNeighborPoint,
                matchSegment12, knot);
        boolean wouldBeStartingUnbalanced = c.balanceMap.balancedOmega(kp1, cp1, cutSegment1, external2, matchSegment11,
                kp2, cp2, cutSegment2, otherNeighborPoint, matchSegment12,
                knot, c, true);
        boolean failCutMatch = !canMatchExternals || !wouldBeStartingUnbalanced;
        if (failCutMatch
                || c.balanceMap.cuts.contains(matchSegment11)
                || c.balanceMap.cuts.contains(matchSegment12)) {
            canMatch = false;
        }
        boolean canMatch2 = true;
        if (failCutMatch
                || c.balanceMap.cuts.contains(matchSegment21)
                || c.balanceMap.cuts.contains(matchSegment22)) {
            canMatch2 = false;
        }
        CutMatchList cutMatch1 = null;
        double d1 = Double.MAX_VALUE;
        if (canMatch || canMatch2) {

            CutMatchList internalCuts = new CutMatchList(shell, sbe, superKnot);
            BalanceMap balanceMap = new BalanceMap(c.balanceMap, knot, sbe);
            try {
                balanceMap.addCut(cutSegment1.first, cutSegment1.last);
                balanceMap.addCut(cutSegment2.first, cutSegment2.last);
                balanceMap.addExternalMatch(kp1, external2, c.superKnot);
                balanceMap.addExternalMatch(kp2, otherNeighborPoint, c.superKnot);
            } catch (BalancerException be) {
                throw be;
            }
            try {
                if (balanceMap.externalBalance.get(c.lowerKnotPoint.id) == 2) {
                    internalCuts = cutEngine.internalPathEngine.calculateInternalPathLength(
                            c.upperKnotPoint, kp2, c.upperExternal,
                            kp2, cp2, otherNeighborPoint,
                            knot, balanceMap);
                } else {
                    internalCuts = cutEngine.internalPathEngine.calculateInternalPathLength(
                            c.lowerKnotPoint, c.lowerCutPoint, c.lowerExternal,
                            kp2, cp2, otherNeighborPoint,
                            knot, balanceMap);
                }
            } catch (SegmentBalanceException sbe) {
                throw sbe;
            }
            if (!canMatch2 || (matchSegment11.distance + matchSegment12.distance < matchSegment21.distance
                    + matchSegment22.distance && canMatch)) {
                cutMatch1 = new CutMatchList(shell, sbe, c.superKnot);
                Segment[] cutSegments = new Segment[] { cutSegment1, cutSegment2 };
                if (!matchSegment11.equals(otherNeighborSegment) && !matchSegment12.equals(otherNeighborSegment)) {
                    cutSegments = new Segment[] { otherNeighborSegment, cutSegment1, cutSegment2 };
                }
                try {
                    cutMatch1.addTwoCut(orgCutSegment, cutSegments,
                            matchSegment11,
                            matchSegment12, kp1,
                            cp1, internalCuts, c, true,
                            "FixedCutTwoMatches1");
                } catch (SegmentBalanceException e) {
                    throw e;
                }
            } else {
                cutMatch1 = new CutMatchList(shell, sbe, c.superKnot);

                Segment[] cutSegments = new Segment[] { cutSegment1, cutSegment2 };
                if (!matchSegment21.equals(otherNeighborSegment) && !matchSegment22.equals(otherNeighborSegment)) {
                    cutSegments = new Segment[] { otherNeighborSegment, cutSegment1, cutSegment2 };
                }
                try {
                    cutMatch1.addTwoCut(orgCutSegment, cutSegments,
                            matchSegment21,
                            matchSegment22, kp1,
                            kp2, internalCuts, c, true,
                            "FixedCutTwoMatches2");
                } catch (SegmentBalanceException e) {
                    throw e;
                }
            }
        }
        return cutMatch1;
    }

}
