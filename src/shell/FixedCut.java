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

    public CutMatchList findCutMatchListFixedCut()
            throws SegmentBalanceException, BalancerException {

        if (needTwoNeighborMatches && !bothCutPointsOutside) {
            shell.buff.add("findCutMatchListFixedCutNeedTwoMatches");

            return new FixedCutTwoMatches(c).findCutMatchListFixedCut();
        } else if (bothKnotPointsOutside && !bothCutPointsOutside) {
            shell.buff.add("findCutMatchListBothCutsInside");
            return new FixedCutBothKnotPointsOutside(c).findCutMatchListFixedCut();
        } else if (bothCutPointsOutside) {
            shell.buff.add("findCutMatchListBothCutsOutside");
            return new FixedCutBothCutsOutside(c).findCutMatchListFixedCut();
        }
        shell.buff.add("findCutMatchListFixed Cut");
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

        for (int a = 0; a < knot.knotPoints.size(); a++) {

            VirtualPoint knotPoint21 = knot.knotPoints.get(a);
            VirtualPoint knotPoint22 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
            Segment cutSegment2 = knot.getSegment(knotPoint21, knotPoint22);

            // I think that the continue state instead of being do the ct sgments partially
            // overlapp each other, should instead
            // be like

            if (c.cutID == 6570 && cutSegment2.hasPoints(11, 1)) {
                float z = 1;
            }
            if (cutSegment1.partialOverlaps(cutSegment2) && !cutSegment2.equals(kpSegment)) {
                shell.buff.add("Checking: " + cutSegment2);

                boolean leftHasOneOut = Utils.marchUntilHasOneKnotPoint(cutSegment1.first, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                boolean rightHasOneOut = Utils.marchUntilHasOneKnotPoint(cutSegment1.last, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                shell.buff.add(leftHasOneOut + " " + rightHasOneOut);
                shell.buff.add("!(leftHasOneOut || rightHasOneOut)" + !(leftHasOneOut || rightHasOneOut));
                shell.buff
                        .add("(cutSegment1.contains(kp1) XOR cutSegment2.contains(kp1)" + kp1 + " " + cutSegment2 + " "
                                + ((cutSegment2.contains(kp1) || cutSegment2.contains(c.upperKnotPoint))));
                boolean skipFlag = true;
                if (skipFlag || !(leftHasOneOut || rightHasOneOut)
                        || ((cutSegment2.contains(kp1) || cutSegment2.contains(c.upperKnotPoint)))) {
                    shell.buff.add("ONE SIDE WOULD BE UNBALANCED " + cutSegment2);

                    continue;
                }

            }
            if (cutSegment1.equals(cutSegment2)) {
                if (needTwoNeighborMatches) {
                    shell.buff.add("Skipping: " + cutSegment2);
                    continue;
                }
                shell.buff.add("ONLY YOUUUUUUUUU :" + cutSegment2);
                Segment s11 = kp1.getClosestSegment(external1, null);
                Segment s12 = cp1.getClosestSegment(external2, s11);
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
            } else {
                shell.buff.add("Garunteed: " + cutSegment2);
                double delta = Double.MAX_VALUE;
                VirtualPoint cp2 = knotPoint22;
                VirtualPoint kp2 = knotPoint21;

                // boolean orphanFlag = wouldOrphan(cp1, kp1, cp2, kp2,
                // knot.knotPointsFlattened);

                Segment s11 = kp1.getClosestSegment(external1, null);
                Segment s12 = kp2.getClosestSegment(external2, s11);

                boolean hasSegment = canCutSegment(kp2, s12, cp2, cutSegment2, innerNeighborSegmentsFlattened);

                CutMatchList internalCuts1 = null;
                CutMatchList cutMatch1 = null;
                double d1 = Double.MAX_VALUE;
                if (!hasSegment) {
                    shell.buff.currentDepth++;
                    BalanceMap balanceMap = new BalanceMap(c.balanceMap, knot, sbe);
                    balanceMap.addCut(kp1, cp1);
                    balanceMap.addCut(kp2, cp2);
                    balanceMap.addExternalMatch(kp1);
                    balanceMap.addExternalMatch(kp2);
                    if (kp1.equals(kp2)) {
                        internalCuts1 = new CutMatchList(shell, sbe, superKnot);
                    } else {
                        internalCuts1 = cutEngine.internalPathEngine.calculateInternalPathLength(kp1, cp1, external1,
                                kp2,
                                cp2,
                                external2, knot, balanceMap);
                    }
                    shell.buff.currentDepth--;

                    shell.buff.add("" + internalCuts1);

                    cutMatch1 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch1.addTwoCut(cutSegment1, new Segment[] { cutSegment2 }, s11,
                            s12, kp1,
                            kp2, internalCuts1, c, false, "FixedCut");

                    d1 = cutMatch1.delta;

                    delta = d1 < delta ? d1 : delta;
                }

                // boolean orphanFlag2 = wouldOrphan(cp1, kp1, kp2, cp2,
                // knot.knotPointsFlattened);

                Segment s21 = kp1.getClosestSegment(external1, null);
                Segment s22 = cp2.getClosestSegment(external2, s21);

                CutMatchList internalCuts2 = null;
                CutMatchList cutMatch2 = null;
                double d2 = Double.MAX_VALUE;
                boolean hasSegment2 = canCutSegment(cp2, s22, kp2, cutSegment2, innerNeighborSegmentsFlattened);
                if (!hasSegment2) {
                    shell.buff.currentDepth++;
                    BalanceMap balanceMap2 = new BalanceMap(c.balanceMap, knot, sbe);
                    balanceMap2.addCut(kp1, cp1);
                    balanceMap2.addCut(kp2, cp2);
                    balanceMap2.addExternalMatch(kp1);
                    balanceMap2.addExternalMatch(cp2);
                    internalCuts2 = cutEngine.internalPathEngine.calculateInternalPathLength(kp1, cp1, external1, cp2,
                            kp2,
                            external2, knot, balanceMap2);
                    shell.buff.currentDepth--;

                    cutMatch2 = new CutMatchList(shell, sbe, c.superKnot);
                    cutMatch2.addTwoCut(cutSegment1, new Segment[] { cutSegment2 }, s21,
                            s22, kp1,
                            cp2, internalCuts2, c, false, "FixedCut");

                    d2 = cutMatch2.delta;

                    delta = d2 < delta ? d2 : delta;
                }

                if (delta < minDelta) {
                    if (!hasSegment && delta == d1) {
                        result = cutMatch1;
                    } else if (!hasSegment2 && delta == d2) {
                        result = cutMatch2;

                    }

                    minDelta = delta;
                    overlapping = 2;
                }

            }
        }
        if (result != null && overlapping == 1) {
            return result;
        } else if (result != null && overlapping == 2) {
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            CutMatchList cml = new CutMatchList(shell, sbe, c.superKnot);
            cml.addDumbCutMatch(knot, superKnot, "FixedCutNoAvailableCuts");
            throw new SegmentBalanceException(sbe);
        }
    }

    public boolean canCutSegment(VirtualPoint kp2, Segment s22, VirtualPoint cp2, Segment cutSegment2,
            ArrayList<VirtualPoint> innerNeighborSegmentsFlattened) {

        boolean innerNeighbor2 = false;
        for (Segment s : innerNeighborSegments) {
            if (s.contains(kp2)) {
                innerNeighbor2 = true;
            }
        }

        boolean replicatesNeighbor2 = false;
        for (Segment s : neighborSegments) {
            if (s.equals(s22)) {
                replicatesNeighbor2 = false;
            }
        }

        boolean outerNeighbor2 = false;
        for (Segment s : neighborSegments) {
            if (s.contains(kp2)) {
                outerNeighbor2 = true;
            }
        }

        boolean cutPointsAcross2 = false;
        for (Segment s : innerNeighborSegments) {
            if (s.equals(cutSegment2)) {
                cutPointsAcross2 = true;
            }
        }

        boolean neighborIntersect2 = false;
        if (innerNeighborSegmentsFlattened.contains(cp1) && innerNeighborSegmentsFlattened.contains(kp2)) {
            neighborIntersect2 = true;
        }
        boolean hasSegment2 = replicatesNeighbor2 || innerNeighbor2
                || neighborIntersect2 || s22.equals(upperCutSegment) || cutPointsAcross2;
        // false;//
        // superKnot.hasSegment(s22)
        // ||
        // kpSegment.contains(cp2);

        shell.buff.add("hasSegment2: " + s22 + " " + hasSegment2 + " " + replicatesNeighbor2 + " " + innerNeighbor2
                + " " + outerNeighbor2 + " " + " " + neighborIntersect2 + " "
                + s22.equals(upperCutSegment));
        if (hasSegment2) {
            shell.buff.add("REEE cutSeg1: " + cutSegment1 + " cutSeg2: " + cutSegment2 + " s22: " + s22
                    + " cp2 :" + kp2 + " kpSegment " + kpSegment);

        }
        return hasSegment2;
    }

}