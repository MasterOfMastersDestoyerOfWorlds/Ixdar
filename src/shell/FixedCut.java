package shell;

import java.util.ArrayList;

import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.commons.math3.util.Pair;

public class FixedCut implements FixedCutInterface {
    Knot knot;
    VirtualPoint external1;
    VirtualPoint external2;
    Segment cutSegment1;
    VirtualPoint kp1;
    VirtualPoint cp1;
    Knot superKnot;

    Segment kpSegment;
    ArrayList<Segment> innerNeighborSegments;

    MultiKeyMap<Integer, Segment> innerNeighborSegmentLookup;
    ArrayList<Segment> neighborSegments;

    Segment upperCutSegment;
    ArrayList<Pair<Segment, VirtualPoint>> neighborCutSegments;

    VirtualPoint topCutPoint;

    boolean needTwoNeighborMatches;
    boolean bothKnotPointsInside;
    boolean bothCutPointsOutside;

    VirtualPoint upperKnotPoint;

    Segment upperMatchSegment;
    VirtualPoint lowerKnotPoint;
    Segment lowerCutSegment;
    Shell shell;
    CutInfo c;
    CutEngine cutEngine;
    SegmentBalanceException sbe;
    boolean bothKnotPointsOutside;

    public FixedCut(CutInfo c) {
        this.shell = c.shell;
        this.cutEngine = shell.cutEngine;
        this.knot = c.knot;
        this.external1 = c.external1;
        this.external2 = c.external2;
        this.cutSegment1 = c.cutSegment1;
        this.kp1 = c.kp1;
        this.cp1 = c.cp1;
        this.superKnot = c.superKnot;
        this.kpSegment = c.kpSegment;
        this.innerNeighborSegments = c.innerNeighborSegments;
        this.innerNeighborSegmentLookup = c.innerNeighborSegmentLookup;
        this.neighborSegments = c.neighborSegments;
        this.upperCutSegment = c.upperCutSegment;
        this.neighborCutSegments = c.neighborCutSegments;
        this.topCutPoint = c.upperCutPoint;
        this.needTwoNeighborMatches = c.needTwoNeighborMatches;
        this.bothKnotPointsInside = c.bothKnotPointsInside;
        this.bothCutPointsOutside = c.bothCutPointsOutside;
        this.bothKnotPointsOutside = c.bothKnotPointsOutside;
        this.upperKnotPoint = c.upperKnotPoint;
        this.upperMatchSegment = c.upperMatchSegment;
        this.lowerKnotPoint = c.lowerKnotPoint;
        this.lowerCutSegment = c.lowerCutSegment;
        this.c = c;
        this.sbe = c.sbe;
    }

    @Override
    public String toString() {
        return " FixedCut : minKnot: " + knot + " | external " + external1 + " | neighbor: " + external2
                + " | Lower Cut: "
                + cutSegment1 + " | kp: " + kp1
                + " | vp: " + cp1 + " | superKnot: " + superKnot + " | kpSegment: " + kpSegment
                + " \ninnerNeighborSegments: " + innerNeighborSegments + " neighborSegments: "
                + neighborSegments + " upperCutSegment: " + upperCutSegment + " neighborCuts: "
                + Utils.pairsToString(neighborCutSegments) +
                " upperCutPointIsOutside: " + needTwoNeighborMatches + " bothKnotPOintsInside: "
                + bothKnotPointsInside + " upperKnotPoint: " + upperKnotPoint + " upperMatchSegment: "
                + upperMatchSegment
                + " ex2: " + upperMatchSegment.getOther(upperKnotPoint);
    }

    @Override
    public CutMatchList findCutMatchListFixedCut() throws SegmentBalanceException, BalancerException {
        // TODO Auto-generated method stubSegmentBalanceException sbe = new
        // SegmentBalanceException(shell, null, superKnot, cutSegment1,

        if (needTwoNeighborMatches && !bothCutPointsOutside) {
            shell.buff.add("findCutMatchListFixedCutNeedTwoMatches");
            if (bothKnotPointsInside) {
                return new FixedCutTwoMatches(c).findCutMatchListFixedCut();
            }
        }
        shell.buff.add("findCutMatchListFixed Cut ID: " + c.cutID);
        cutEngine.totalCalls++;
        if (cutEngine.cutLookup.containsKey(knot.id, external2.id, kp1.id, cp1.id, superKnot.id)) {
            cutEngine.resolved++;
            // return cutLookup.get(knot.id, external2.id, kp1.id, cp1.id,
            // superKnot.id).copy();
        }

        if (!(knot.contains(cutSegment1.first) && knot.contains(cutSegment1.last))) {
            shell.buff.add(knot);
            shell.buff.add(cutSegment1);
            new CutMatchList(shell, sbe, c.superKnot);
            throw new SegmentBalanceException(sbe);
        }
        if ((knot.contains(external1) || knot.contains(external2))) {
            new CutMatchList(shell, sbe, c.superKnot);
            throw new SegmentBalanceException(sbe);
        }

        ArrayList<VirtualPoint> innerNeighborSegmentsFlattened = new ArrayList<>();
        for (Segment s : innerNeighborSegments) {
            innerNeighborSegmentsFlattened.add(s.first);
            innerNeighborSegmentsFlattened.add(s.last);
        }
        double minDelta = Double.MAX_VALUE;
        int overlapping = -1;
        CutMatchList result = null;

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

        Segment orgCutSegment = c.upperCutSegment;

        VirtualPoint knotPoint11 = kp1;
        VirtualPoint knotPoint12 = cp1;
        int numMatchesNeeded = c.balanceMap.getNumMatchesNeeded(external2);
        int numMatchesNeededOther = c.balanceMap.getNumMatchesNeeded(otherNeighborPoint);

        for (int b = 0; b < knot.knotPoints.size(); b++) {

            VirtualPoint knotPoint21 = knot.knotPoints.get(b);
            VirtualPoint knotPoint22 = knot.knotPoints.get(b + 1 >= knot.knotPoints.size() ? 0 : b + 1);

            Segment cutSegment2 = knot.getSegment(knotPoint21, knotPoint22);
            if (c.cutID == 136) {
                float z = 1;
            }
            double delta = Double.MAX_VALUE;
            // I think that the continue state instead of being do the ct sgments partially
            // overlapp each other, should instead
            // be like

            if (c.cutID == 6570 && cutSegment2.hasPoints(11, 1)) {
                float z = 1;
            }
            if (cutSegment1.equals(cutSegment2)) {
                Segment s11 = kp1.getClosestSegment(external1, null);
                Segment s12 = cp1.getClosestSegment(external2, s11);
                boolean canMatchExternals = c.balanceMap.canMatchTo(kp1, external1, s11, cp1, external2, s12, knot);
                boolean wouldBeStartingUnbalanced = c.balanceMap.balancedAlpha(cp1, kp1, cutSegment1, external2, s12,
                        knot, c);
                boolean failFlag1 = !canMatchExternals || !wouldBeStartingUnbalanced
                        || c.balanceMap.cuts.contains(s11) || c.balanceMap.cuts.contains(s12);
                if (needTwoNeighborMatches || failFlag1) {
                    shell.buff.add("Skipping: " + cutSegment2);
                    continue;
                }
                shell.buff.add("ONLY YOUUUUUUUUU :" + cutSegment2);
                CutMatchList cutMatch = new CutMatchList(shell, sbe, c.superKnot);
                cutMatch.addCutMatch(new Segment[] {}, new Segment[] { s12 }, c, "FixedCutSegmentsFullyOverlap");
                boolean cutPointsAcross = false;
                for (Segment s : innerNeighborSegments) {
                    if (s.contains(cp1) && s.contains(kp1)) {
                        cutPointsAcross = true;
                    }
                }

                boolean hasSegment = cutPointsAcross;

                if (cutMatch.delta < minDelta && !hasSegment) {
                    result = cutMatch;
                    minDelta = cutMatch.delta;
                    overlapping = 1;
                    shell.buff.add("UPDATING MINDELTA " + minDelta);

                }
                continue;
            }

            CutMatchList cutMatch1 = tryCombo(knotPoint11, knotPoint12, external1, cutSegment1, knotPoint21,
                    knotPoint22, otherNeighborPoint, cutSegment2, orgCutSegment, otherNeighborSegment, numMatchesNeeded,
                    numMatchesNeededOther);
            double d1 = Double.MAX_VALUE;
            if (cutMatch1 != null) {
                d1 = cutMatch1.delta;
                delta = d1 < delta ? d1 : delta;
            }

            CutMatchList cutMatch3 = tryCombo(knotPoint11, knotPoint12, external1, cutSegment1, knotPoint22,
                    knotPoint21, otherNeighborPoint, cutSegment2, orgCutSegment, otherNeighborSegment, numMatchesNeeded,
                    numMatchesNeededOther);
            double d3 = Double.MAX_VALUE;
            if (cutMatch3 != null) {
                d3 = cutMatch3.delta;
                delta = d3 < delta ? d3 : delta;
            }

            if (delta < minDelta) {
                if (delta == d1) {
                    result = cutMatch1;
                } else if (delta == d3) {
                    result = cutMatch3;
                }
                minDelta = delta;
                overlapping = 2;
            }

        }
        if (overlapping == 1) {
            return result;
        } else if (overlapping == 2) {
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            CutMatchList cml = new CutMatchList(shell, sbe, c.superKnot);
            cml.addDumbCutMatch(knot, superKnot, "FixedCutNoAvailableCuts");
            throw new SegmentBalanceException(sbe);
        }

    }

    public CutMatchList tryCombo(VirtualPoint kp1, VirtualPoint cp1, VirtualPoint external1, Segment cutSegment1,
            VirtualPoint kp2, VirtualPoint cp2, VirtualPoint otherNeighborPoint,
            Segment cutSegment2, Segment orgCutSegment, Segment otherNeighborSegment, int numMatchesNeeded,
            int numMatchesNeededOther)
            throws SegmentBalanceException {
        Segment matchSegment11 = kp1.getClosestSegment(external1, null);
        Segment matchSegment12 = kp2.getClosestSegment(otherNeighborPoint, null);

        if (kp2.equals(cp2)) {
            float z = 1;
        }
        boolean canMatch = true;
        if (c.cutID == 120) {
            float z = 10;
        }
        boolean canMatchExternals = c.balanceMap.canMatchTo(kp1, external1, matchSegment11, kp2, otherNeighborPoint,
                matchSegment12, knot);
        boolean canReBalance = c.balanceMap.balancedOmega(kp1, cp1, cutSegment1, external1, matchSegment11,
                kp2, cp2, cutSegment2, external2, matchSegment12,
                knot, c, false);
        boolean failCutMatch = !canMatchExternals || !canReBalance;
        if (failCutMatch
                || c.balanceMap.cuts.contains(matchSegment11)
                || c.balanceMap.cuts.contains(matchSegment12)) {
            canMatch = false;
        }
        CutMatchList cutMatch1 = null;
        double d1 = Double.MAX_VALUE;
        if (canMatch) {

            CutMatchList internalCuts = new CutMatchList(shell, sbe, superKnot);
            BalanceMap balanceMap = new BalanceMap(c.balanceMap, knot, sbe);
            try {
                balanceMap.addCut(cutSegment1.first, cutSegment1.last);
                balanceMap.addCut(cutSegment2.first, cutSegment2.last);
                balanceMap.addExternalMatch(kp2, otherNeighborPoint, c.superKnot);
            } catch (BalancerException be) {
                throw be;
            }

            if (c.cutID == 11 && balanceMap.ID == 5) {
                float z = 1;
            }
            try {
                internalCuts = cutEngine.internalPathEngine.calculateInternalPathLength(
                        kp1, cp1, external1,
                        kp2, cp2, otherNeighborPoint,
                        knot, balanceMap);
            } catch (SegmentBalanceException sbe) {
                throw sbe;
            }
            cutMatch1 = new CutMatchList(shell, sbe, c.superKnot);
            Segment[] cutSegments = new Segment[] { cutSegment1, cutSegment2 };
            try {
                cutMatch1.addTwoCut(orgCutSegment, cutSegments,
                        matchSegment11,
                        matchSegment12, kp1,
                        cp1, internalCuts, c, false,
                        "FixedCut1");
            } catch (SegmentBalanceException e) {
                throw e;
            }
        }
        return cutMatch1;
    }

}