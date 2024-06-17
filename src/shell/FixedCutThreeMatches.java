package shell;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.commons.math3.util.Pair;

public class FixedCutThreeMatches extends FixedCut {

    public FixedCutThreeMatches(CutInfo c) {
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
        VirtualPoint otherNeighborPoint = external2;
        VirtualPoint otherNeighborPoint2 = external2;
        uniqueNeighborPoints.add(external2);
        for (Pair<Segment, VirtualPoint> p : neighborCutSegments) {
            if (!p.getSecond().equals(external2)) {
                uniqueNeighborPoints.add(p.getSecond());
            }
        }
        shell.buff.add(uniqueNeighborPoints);
        int numUnique = uniqueNeighborPoints.size();
        if (numUnique > 1) {
            uniqueNeighborPoints.remove(external2);
            otherNeighborPoint = uniqueNeighborPoints.get(0);
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
        // TODO: I think the first cut we check against needs to be the simple cut
        if(knot.hasSegment(c.lowerCutSegment)){

        }
        for (int a = 0; a < knot.knotPoints.size(); a++) {
            for (int b = 0; b < knot.knotPoints.size(); b++) {
                VirtualPoint knotPoint11 = knot.knotPoints.get(a);
                VirtualPoint knotPoint12 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);

                VirtualPoint knotPoint21 = knot.knotPoints.get(b);
                VirtualPoint knotPoint22 = knot.knotPoints.get(b + 1 >= knot.knotPoints.size() ? 0 : b + 1);


                Segment cutSegment1 = knot.getSegment(knotPoint11, knotPoint12);
                Segment cutSegment2 = knot.getSegment(knotPoint21, knotPoint22);               
                if(cutSegment1.equals(cutSegment2)){
                    continue;
                }
                if(c.cutID == 11){
                    float z = 1;
                }
                double delta = Double.MAX_VALUE;

                CutMatchList cutMatch1 = tryCombo(knotPoint11, knotPoint12, external2, knotPoint12,
                        knotPoint22, otherNeighborPoint, cutSegment1, cutSegment2, orgCutSegment);
                double d1 = Double.MAX_VALUE;
                if (cutMatch1 != null) {
                    d1 = cutMatch1.delta;
                    delta = d1 < delta ? d1 : delta;
                }

                CutMatchList cutMatch2 = tryCombo(knotPoint12, knotPoint11, external2, knotPoint22,
                        knotPoint21, otherNeighborPoint, cutSegment1, cutSegment2, orgCutSegment);
                double d2 = Double.MAX_VALUE;
                if (cutMatch2 != null) {
                    d2 = cutMatch2.delta;
                    delta = d2 < delta ? d2 : delta;
                }

                CutMatchList cutMatch3 = tryCombo(knotPoint11, knotPoint12, external2, knotPoint22,
                        knotPoint21, otherNeighborPoint, cutSegment1, cutSegment2, orgCutSegment);
                double d3 = Double.MAX_VALUE;
                if (cutMatch3 != null) {
                    d3 = cutMatch3.delta;
                    delta = d3 < delta ? d3 : delta;
                }

                CutMatchList cutMatch4 = tryCombo(knotPoint12, knotPoint11, external2, knotPoint21,
                        knotPoint22, otherNeighborPoint, cutSegment1, cutSegment2, orgCutSegment);
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
        }
        if (overlapping == 1)

        {
            shell.buff.add("GOING WIHT SINGLE CUT");
            return result;

        } else if (overlapping == 2) {
            shell.buff.add("GOING WIHT Multi CUT");
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            CutMatchList cml = new CutMatchList(shell, sbe, c.superKnot);
            cml.addDumbCutMatch(knot, superKnot, "FixedCutThreeMatchesNoAvailableCuts");
            throw new SegmentBalanceException(sbe);
        }

    }

    public CutMatchList tryCombo(VirtualPoint kp1, VirtualPoint cp1, VirtualPoint external2, VirtualPoint kp2,
            VirtualPoint cp2,
            VirtualPoint otherNeighborPoint, 
            Segment cutSegment1, Segment cutSegment2, Segment orgCutSegment)
            throws SegmentBalanceException {
        Segment matchSegment11 = kp1.getClosestSegment(external2, null);
        Segment matchSegment12 = kp2.getClosestSegment(otherNeighborPoint, null);

        Segment matchSegment21 = kp1.getClosestSegment(otherNeighborPoint, null);
        Segment matchSegment22 = kp2.getClosestSegment(external2, null);
        
        boolean canMatch = true;
        boolean canMatchExternals = c.balanceMap.canMatchTo(kp1, external2, matchSegment11, kp2, otherNeighborPoint, matchSegment12);
        boolean wouldBeStartingUnbalanced  = c.balanceMap.balancedOmega(kp1, cp1, cutSegment1, external2, matchSegment11,
         kp2, cp2, cutSegment2, otherNeighborPoint, matchSegment12, knot, c, true);
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
                balanceMap.addExternalMatch(kp1, external2);
                balanceMap.addExternalMatch(kp2, otherNeighborPoint);
                internalCuts = cutEngine.internalPathEngine.calculateInternalPathLength(
                        kp1, cp1, external2,
                        kp2, cp2, otherNeighborPoint,
                        knot, balanceMap);
            } catch (BalancerException be) {
                throw be;
            }
            if (matchSegment11.distance + matchSegment12.distance < matchSegment21.distance
                    + matchSegment22.distance && canMatch) {
                cutMatch1 = new CutMatchList(shell, sbe, c.superKnot);
                try {
                    cutMatch1.addTwoCut(orgCutSegment, new Segment[] { cutSegment1, cutSegment2 },
                            matchSegment11,
                            matchSegment12, kp1,
                            cp1, internalCuts, c, true,
                            "FixedCutThreeMatches1");
                } catch (SegmentBalanceException e) {
                    throw e;
                }
            } else {
                cutMatch1 = new CutMatchList(shell, sbe, c.superKnot);
                try {
                    cutMatch1.addTwoCut(orgCutSegment, new Segment[] { cutSegment1, cutSegment2 },
                            matchSegment21,
                            matchSegment22, kp1,
                            kp2, internalCuts, c, true,
                            "FixedCutThreeMatches2");
                } catch (SegmentBalanceException e) {
                    throw e;
                }
            }
        }
        return cutMatch1;
    }

    public Triple<Set<Segment>, Set<Segment>, Pair<VirtualPoint, VirtualPoint>> findSimpleMatch(
            Segment cutSegment1Segment) {
        Segment matchSegment1 = null;
        Segment matchSegment2 = null;
        Segment matchSegment3 = null;
        Segment cutSegment2Final = null;
        Segment innerSegment1 = null;
        Segment innerSegment2 = null;
        Segment innerSegment3 = null;
        Set<Segment> previousMatches = new HashSet<>();
        Set<Segment> internalCuts = new HashSet<>();
        if (neighborCutSegments.size() > 0) {
            matchSegment1 = neighborCutSegments.get(0).getFirst();
            previousMatches.add(matchSegment1);
            innerSegment1 = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(matchSegment1),
                    Segment.getLastOrderId(matchSegment1));
            internalCuts.add(innerSegment1);
        }
        if (neighborCutSegments.size() > 1) {
            matchSegment2 = neighborCutSegments.get(1).getFirst();
            previousMatches.add(matchSegment2);
            innerSegment2 = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(matchSegment2),
                    Segment.getLastOrderId(matchSegment2));
            internalCuts.add(innerSegment2);
        }
        if (neighborCutSegments.size() > 2) {
            matchSegment3 = neighborCutSegments.get(2).getFirst();
            previousMatches.add(matchSegment3);
            innerSegment3 = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(matchSegment3),
                    Segment.getLastOrderId(matchSegment3));
            internalCuts.add(innerSegment3);
        }
        if (innerSegment1 != null && innerSegment1.equals(innerSegment2)
                || innerSegment1 != null && innerSegment1.equals(innerSegment3)) {
            cutSegment2Final = innerSegment1;
        } else if (innerSegment2 != null && innerSegment2.equals(innerSegment3)) {
            cutSegment2Final = innerSegment2;
        } else {
            cutSegment2Final = innerSegment1;
        }

        Pair<VirtualPoint, VirtualPoint> mirrors = null;
        if (cutSegment1.contains(upperKnotPoint)) {
            mirrors = Utils.marchLookup(knot, upperKnotPoint, cutSegment1.getOther(upperKnotPoint),
                    cutSegment2Final);
        } else {

            mirrors = Utils.marchLookup(knot, upperKnotPoint, cutSegment2Final.getOther(upperKnotPoint),
                    cutSegment1);
        }

        return new ImmutableTriple<>(previousMatches, internalCuts, mirrors);
    }

}
