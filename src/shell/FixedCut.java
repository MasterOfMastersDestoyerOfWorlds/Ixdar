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
    protected SegmentBalanceException sbe;

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
        this.topCutPoint = c.topCutPoint;
        this.needTwoNeighborMatches = c.needTwoNeighborMatches;
        this.bothKnotPointsInside = c.bothKnotPointsInside;
        this.bothCutPointsOutside = c.bothCutPointsOutside;
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
                + InternalPathEngine.pairsToString(neighborCutSegments) +
                " upperCutPointIsOutside: " + needTwoNeighborMatches + " bothKnotPOintsInside: "
                + bothKnotPointsInside + " upperKnotPoint: " + upperKnotPoint + " upperMatchSegment: "
                + upperMatchSegment
                + " ex2: " + upperMatchSegment.getOther(upperKnotPoint);
    }

    public CutMatchList findCutMatchListFixedCut()
            throws SegmentBalanceException {

        if (needTwoNeighborMatches && !bothCutPointsOutside) {
            shell.buff.add("findCutMatchListFixedCutNeedTwoMatches");

            return new FixedCutTwoMatches(c).findCutMatchListFixedCut();
        } else if (bothKnotPointsInside && !bothCutPointsOutside) {
            shell.buff.add("findCutMatchListBothCutsInside");
            return new FixedCutBothCutsInside(c).findCutMatchListFixedCut();
        } else if (bothCutPointsOutside) {
            shell.buff.add("findCutMatchListBothCutsOutside");
            return new FixedCutBothCutsOutside(c).findCutMatchListFixedCut();

        }
        cutEngine.totalCalls++;
        if (cutEngine.cutLookup.containsKey(knot.id, external2.id, kp1.id, cp1.id, superKnot.id)) {
            cutEngine.resolved++;
            // return cutLookup.get(knot.id, external2.id, kp1.id, cp1.id,
            // superKnot.id).copy();
        }

        if (!(knot.contains(cutSegment1.first) && knot.contains(cutSegment1.last))) {
            shell.buff.add(knot);
            shell.buff.add(cutSegment1);
            new CutMatchList(shell, sbe);
            throw new SegmentBalanceException(sbe);
        }
        if ((knot.contains(external1) || knot.contains(external2))) {
            float z = 1 / 0;
        }

        ArrayList<VirtualPoint> innerNeighborSegmentsFlattened = new ArrayList<>();
        for (Segment s : innerNeighborSegments) {
            innerNeighborSegmentsFlattened.add(s.first);
            innerNeighborSegmentsFlattened.add(s.last);
        }
        double minDelta = Double.MAX_VALUE;
        int overlapping = -1;
        Segment matchSegment2Final = null;
        Segment cutSegmentFinal = null;
        Segment cutSegment2Final = null;
        VirtualPoint knotPoint1Final = null;
        VirtualPoint knotPoint2Final = null;
        CutMatchList internalCuts = null;
        for (int a = 0; a < knot.knotPoints.size(); a++) {

            VirtualPoint knotPoint21 = knot.knotPoints.get(a);
            VirtualPoint knotPoint22 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
            Segment cutSegment2 = knot.getSegment(knotPoint21, knotPoint22);
            // I think that the continue state instead of being do the ct sgments partially
            // overlapp each other, should instead
            // be like
            if (cutSegment1.partialOverlaps(cutSegment2) && !cutSegment2.equals(kpSegment)) {
                shell.buff.add("Checking: " + cutSegment2);

                boolean leftHasOneOut = CutEngine.marchUntilHasOneKnotPoint(cutSegment1.first, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                boolean rightHasOneOut = CutEngine.marchUntilHasOneKnotPoint(cutSegment1.last, cutSegment1,
                        cutSegment2, kp1, upperKnotPoint, knot);
                shell.buff.add(leftHasOneOut + " " + rightHasOneOut);
                shell.buff.add("!(leftHasOneOut || rightHasOneOut)" + !(leftHasOneOut || rightHasOneOut));
                shell.buff.add("!((cutSegment1.contains(kp1) || cutSegment2.contains(kp1)"
                        + !((cutSegment1.contains(kp1) || cutSegment2.contains(kp1))
                                && (cutSegment1.contains(upperKnotPoint) || cutSegment2.contains(upperKnotPoint))));
                boolean skipFlag = true;
                if (skipFlag || !(leftHasOneOut || rightHasOneOut)
                        || !((cutSegment1.contains(kp1) || cutSegment2.contains(kp1)))) {
                    shell.buff.add("ONE SIDE WOULD BE UNBALANCED " + cutSegment2);

                    continue;
                }

            }
            if (cutSegment1.equals(cutSegment2)) {
                if (needTwoNeighborMatches) {
                    shell.buff.add("Skipping: " + cutSegment2);
                    continue;
                }

                Segment s11 = kp1.getClosestSegment(external1, null);
                Segment s12 = cp1.getClosestSegment(external2, s11);
                double d1 = s12.distance;

                double delta = d1;

                boolean cutPointsAcross = false;
                for (Segment s : innerNeighborSegments) {
                    if (s.contains(cp1) && s.contains(kp1)) {
                        cutPointsAcross = true;
                    }
                }

                boolean hasSegment = cutPointsAcross;
                if (delta < minDelta && !hasSegment) {
                    matchSegment2Final = s12;
                    knotPoint1Final = kp1;
                    knotPoint2Final = cp1;
                    minDelta = delta;
                    overlapping = 1;
                    cutSegmentFinal = cutSegment1;
                }
            } else {
                double delta = Double.MAX_VALUE;
                VirtualPoint cp2 = knotPoint22;
                VirtualPoint kp2 = knotPoint21;

                // boolean orphanFlag = wouldOrphan(cp1, kp1, cp2, kp2,
                // knot.knotPointsFlattened);

                Segment s11 = kp1.getClosestSegment(external1, null);
                Segment s12 = kp2.getClosestSegment(external2, s11);
                boolean innerNeighbor = false;
                for (Segment s : innerNeighborSegments) {
                    if (s.contains(kp2)) {
                        innerNeighbor = true;
                    }
                }

                boolean replicatesNeighbor = false;
                for (Segment s : neighborSegments) {
                    if (s.equals(s12)) {
                        replicatesNeighbor = false;
                    }
                }

                boolean outerNeighbor = false;
                for (Segment s : neighborSegments) {
                    if (s.contains(kp2)) {
                        outerNeighbor = true;
                    }
                }

                boolean cutPointsAcross = false;
                for (Segment s : innerNeighborSegments) {
                    if (s.contains(cp1) && s.contains(cp2)) {
                        cutPointsAcross = true;
                    }
                }
                boolean neighborIntersect = false;
                if (innerNeighborSegmentsFlattened.contains(cp1) && innerNeighborSegmentsFlattened.contains(kp2)) {
                    neighborIntersect = true;
                }
                boolean hasSegment = replicatesNeighbor
                        || (innerNeighbor && outerNeighbor) || neighborIntersect || s12.equals(upperCutSegment);

                if (hasSegment) {
                    shell.buff.add("REEE: cutSeg1: " + cutSegment1 + " cutSeg2: " + cutSegment2 + " s12: " + s12
                            + " kp2 :" + kp2 + " kpSegment " + kpSegment);

                    shell.buff.add("hasSegment: " + hasSegment + " " + replicatesNeighbor + " " + innerNeighbor
                            + " " + outerNeighbor + " " + " " + neighborIntersect + " "
                            + s12.equals(upperCutSegment));
                }

                CutMatchList internalCuts1 = null;
                double d1 = Double.MAX_VALUE;
                if (!hasSegment) {
                    shell.buff.currentDepth++;
                    internalCuts1 = cutEngine.internalPathEngine.calculateInternalPathLength(kp1, cp1, external1, kp2,
                            cp2,
                            external2, knot);
                    shell.buff.currentDepth--;
                    d1 = s12.distance + internalCuts1.delta - cutSegment2.distance;
                    delta = d1 < delta ? d1 : delta;
                }

                // boolean orphanFlag2 = wouldOrphan(cp1, kp1, kp2, cp2,
                // knot.knotPointsFlattened);

                Segment s21 = kp1.getClosestSegment(external1, null);
                Segment s22 = cp2.getClosestSegment(external2, s21);

                boolean innerNeighbor2 = false;
                for (Segment s : innerNeighborSegments) {
                    if (s.contains(cp2)) {
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
                    if (s.contains(cp2)) {
                        outerNeighbor2 = true;
                    }
                }

                boolean cutPointsAcross2 = false;
                for (Segment s : innerNeighborSegments) {
                    if (s.contains(cp1) && s.contains(kp2)) {
                        cutPointsAcross2 = true;
                    }
                }
                boolean neighborIntersect2 = false;
                if (innerNeighborSegmentsFlattened.contains(cp1) && innerNeighborSegmentsFlattened.contains(kp2)) {
                    neighborIntersect2 = true;
                }
                boolean hasSegment2 = replicatesNeighbor2
                        || (innerNeighbor2 && outerNeighbor2) || neighborIntersect2 || s22.equals(upperCutSegment);
                // false;//
                // superKnot.hasSegment(s22)
                // ||
                // kpSegment.contains(cp2);

                if (hasSegment2) {
                    shell.buff.add("REEE cutSeg1: " + cutSegment1 + " cutSeg2: " + cutSegment2 + " s22: " + s22
                            + " cp2 :" + cp2 + " kpSegment " + kpSegment);

                    shell.buff.add("hasSegment2: " + hasSegment2 + " " + replicatesNeighbor2 + " " + innerNeighbor2
                            + " " + outerNeighbor2 + " " + " " + neighborIntersect2 + " "
                            + s22.equals(upperCutSegment));
                }

                CutMatchList internalCuts2 = null;
                double d2 = Double.MAX_VALUE;
                if (!hasSegment2) {
                    shell.buff.currentDepth++;
                    internalCuts2 = cutEngine.internalPathEngine.calculateInternalPathLength(kp1, cp1, external1, cp2,
                            kp2,
                            external2, knot);
                    shell.buff.currentDepth--;
                    d2 = s22.distance + internalCuts2.delta - cutSegment2.distance;
                    delta = d2 < delta ? d2 : delta;

                }

                if (delta < minDelta) {
                    if (!hasSegment) {
                        matchSegment2Final = s12;
                        knotPoint1Final = kp1;
                        knotPoint2Final = kp2;
                        internalCuts = internalCuts1;
                        cutSegmentFinal = cutSegment1;
                        cutSegment2Final = cutSegment2;
                    } else {
                        matchSegment2Final = s22;
                        knotPoint1Final = kp1;
                        knotPoint2Final = cp2;
                        internalCuts = internalCuts2;
                        cutSegmentFinal = cutSegment1;
                        cutSegment2Final = cutSegment2;

                    }

                    minDelta = delta;
                    overlapping = 2;
                }

            }
        }
        if (overlapping == 1) {
            CutMatchList result = new CutMatchList(shell, sbe);
            shell.buff.add("Im gonna pre: " + neighborSegments);
            result.addCut(cutSegmentFinal, kp1.getClosestSegment(external1, null), matchSegment2Final,
                    knotPoint1Final, knotPoint2Final, c, false, false);
            cutEngine.cutLookup.put(knot.id, external2.id, kp1.id, cp1.id, superKnot.id, result);
            return result;
        } else if (overlapping == 2) {
            CutMatchList result = new CutMatchList(shell, sbe);
            result.addTwoCut(cutSegmentFinal, cutSegment2Final, kp1.getClosestSegment(external1, null),
                    matchSegment2Final, knotPoint1Final,
                    knotPoint2Final, internalCuts, c, false);
            cutEngine.cutLookup.put(knot.id, external2.id, kp1.id, cp1.id, superKnot.id, result);
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            CutMatchList cml = new CutMatchList(shell, sbe);
            cml.addDumbCutMatch(knot, superKnot);
            throw new SegmentBalanceException(sbe);
        }
    }

}