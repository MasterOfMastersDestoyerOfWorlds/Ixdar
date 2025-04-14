package shell.cuts.engines;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.math3.util.Pair;

import shell.BalanceMap;
import shell.Toggle;
import shell.cuts.CutInfo;
import shell.cuts.CutMatchDistanceMatrix;
import shell.cuts.CutMatchList;
import shell.cuts.SortedCutMatchInfo;
import shell.cuts.enums.RouteType;
import shell.cuts.route.Route;
import shell.cuts.route.RouteInfo;
import shell.cuts.route.RouteMap;
import shell.exceptions.BalancerException;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.shell.Shell;

public class ManifoldEngine {

    public static int countSkipped = 0;
    public static int countCalculated = 0;

    public static int routeMapsCopied = 0;
    public static int totalRoutes = 0;
    public static double cutMatchListTime = 0;
    public static double routeMapCopyTime = 0;

    public static void resetMetrics() {
        countSkipped = 0;
        countCalculated = 0;
        routeMapsCopied = 0;
        totalRoutes = 0;
        cutMatchListTime = 0;
        routeMapCopyTime = 0;
    }

    public static SortedCutMatchInfo findCutMatchList(Knot knot,
            VirtualPoint external1, VirtualPoint external2, Shell shell)
            throws SegmentBalanceException, BalancerException {
        long startTimeCutMatchList = System.currentTimeMillis();
        double minDelta = Double.MAX_VALUE;
        SortedCutMatchInfo sortedCutMatchInfo = new SortedCutMatchInfo();
        CutMatchDistanceMatrix d = new CutMatchDistanceMatrix(knot);
        for (int a = 0; a < knot.knotPoints.size(); a++) {

            VirtualPoint knotPoint11 = knot.knotPoints.get(a);
            VirtualPoint knotPoint12 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
            Segment cutSegment1 = knotPoint11.getClosestSegment(knotPoint12, null);
            Segment s11 = knotPoint11.getClosestSegment(external1, null);
            Segment s12 = knotPoint12.getClosestSegment(external2, s11);

            CutInfo c1 = new CutInfo(shell, knotPoint11, knotPoint12, cutSegment1, external1, knotPoint12, knotPoint11,
                    cutSegment1, external2, knot, null, true);
            SegmentBalanceException sbe = new SegmentBalanceException(shell, null, c1);
            BalanceMap balanceMap1 = new BalanceMap(knot, sbe);
            balanceMap1.addCut(knotPoint11, knotPoint12);
            balanceMap1.addExternalMatch(knotPoint11, external1, null);
            balanceMap1.addExternalMatch(knotPoint12, external2, null);
            c1.balanceMap = balanceMap1;

            CutMatchList cutMatch1 = new CutMatchList(shell, c1, c1.superKnot);
            cutMatch1.addCutMatch(new Segment[] { cutSegment1 }, new Segment[] { s11, s12 }, c1,
                    "CutEngineSegmentsFullyOverlap1");
            double d1 = cutMatch1.delta;

            Segment s21 = knotPoint12.getClosestSegment(external1, null);
            Segment s22 = knotPoint11.getClosestSegment(external2, s21);

            CutInfo c2 = new CutInfo(shell, knotPoint12, knotPoint11, cutSegment1, external1, knotPoint11, knotPoint12,
                    cutSegment1, external2, knot, balanceMap1, true);

            CutMatchList cutMatch2 = new CutMatchList(shell, c2, c2.superKnot);
            cutMatch2.addCutMatch(new Segment[] { cutSegment1 }, new Segment[] { s21, s22 }, c2,
                    "CutEngineSegmentsFullyOverlap2");
            double d2 = cutMatch2.delta;

            double delta = d2;
            if (d1 < d2) {
                delta = d1;
            }
            sortedCutMatchInfo.add(cutMatch1, cutSegment1, knotPoint11);
            sortedCutMatchInfo.add(cutMatch2, cutSegment1, knotPoint12);
            if (delta < minDelta) {
                minDelta = delta;
            }
        }
        boolean ixdarSkip = Toggle.IxdarSkip.value;
        boolean answerSharing = Toggle.IxdarMirrorAnswerSharing.value;
        boolean checkAnswer = Toggle.IxdarCheckMirroredAnswerSharing.value;
        double minInternalDistance = Double.MAX_VALUE;
        for (int a = 0; a < knot.knotPoints.size(); a++) {
            for (int b = a; b < knot.knotPoints.size(); b++) {

                // 0.28%
                VirtualPoint knotPoint11 = knot.knotPoints.get(a);
                VirtualPoint knotPoint12 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
                Segment cutSegment1 = knotPoint11.getClosestSegment(knotPoint12, null);

                VirtualPoint knotPoint21 = knot.knotPoints.get(b);
                VirtualPoint knotPoint22 = knot.knotPoints.get(b + 1 >= knot.knotPoints.size() ? 0 : b + 1);
                Segment cutSegment2 = knotPoint21.getClosestSegment(knotPoint22, null);

                if (cutSegment1.partialOverlaps(cutSegment2)) {
                    if (knot.size() <= 4) {
                        // provable impossible to make any series of cutmatches that will return to the
                        // singular cutpoint when the cutsegments overlap in a knot of size <= 4
                        continue;
                    }
                    // goal is to throw away any overlapped segments where kp2==cp1, kp1 == cp2, or
                    // kp1 == kp2, the remaining combination where cp1 == cp2 is the one we are
                    // interested in and is only valid for knots of size > 4.
                }
                if (cutSegment1.equals(cutSegment2)) {
                    continue;
                } else {
                    double delta = Double.MAX_VALUE;
                    double d1 = Double.MAX_VALUE;
                    CutMatchList cutMatch1 = null;

                    CutInfo c1 = new CutInfo(shell, knotPoint11, knotPoint12, cutSegment1, external1, knotPoint21,
                            knotPoint22, cutSegment2, external2, knot, null, true);
                    double mind1 = ixdarCutoff(c1, minInternalDistance);
                    Pair<CutMatchList, RouteMap> internalCuts12 = null;
                    if ((mind1 < minDelta || !ixdarSkip) && c1.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap>> temp = answerSharing(null,
                                null, c1, sortedCutMatchInfo, false, false, true, d);
                        internalCuts12 = temp.getSecond();
                        cutMatch1 = temp.getFirst();
                        d1 = cutMatch1.delta;
                        minInternalDistance = cutMatch1.internalDelta < minInternalDistance ? cutMatch1.internalDelta
                                : minInternalDistance;
                        delta = d1 < delta ? d1 : delta;
                        countCalculated++;
                    } else {
                        countSkipped++;
                    }

                    double d2 = Double.MAX_VALUE;
                    CutMatchList cutMatch2 = null;
                    CutInfo c2 = c1.copyAndSwapExternals();
                    double mind2 = ixdarCutoff(c2, minInternalDistance);
                    if ((mind2 < minDelta || !ixdarSkip) && c2.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap>> temp = answerSharing(null,
                                internalCuts12, c2, sortedCutMatchInfo, false, false, true, d);
                        internalCuts12 = temp.getSecond();
                        cutMatch2 = temp.getFirst();
                        d2 = cutMatch2.delta;
                        minInternalDistance = cutMatch2.internalDelta < minInternalDistance ? cutMatch2.internalDelta
                                : minInternalDistance;
                        delta = d2 < delta ? d2 : delta;
                        countCalculated++;
                    } else {
                        countSkipped++;
                    }

                    CutInfo c3 = new CutInfo(shell, knotPoint12, knotPoint11, cutSegment1, external1, knotPoint22,
                            knotPoint21, cutSegment2, external2, knot, null, true);
                    Pair<CutMatchList, RouteMap> internalCuts34 = null;
                    CutMatchList cutMatch3 = null;
                    double d3 = Double.MAX_VALUE;
                    double mind3 = ixdarCutoff(c3, minInternalDistance);
                    if ((mind3 < minDelta || !ixdarSkip) && c3.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap>> temp = answerSharing(null,
                                null, c3, sortedCutMatchInfo, false, false, true, d);
                        internalCuts34 = temp.getSecond();
                        cutMatch3 = temp.getFirst();
                        d3 = cutMatch3.delta;
                        minInternalDistance = cutMatch3.internalDelta < minInternalDistance ? cutMatch3.internalDelta
                                : minInternalDistance;
                        delta = d3 < delta ? d3 : delta;
                        countCalculated++;
                    } else {
                        countSkipped++;
                    }

                    CutInfo c4 = c3.copyAndSwapExternals();
                    CutMatchList cutMatch4 = null;
                    double d4 = Double.MAX_VALUE;
                    double mind4 = ixdarCutoff(c4, minInternalDistance);
                    if ((mind4 < minDelta || !ixdarSkip) && c4.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap>> temp = answerSharing(null,
                                internalCuts34, c4, sortedCutMatchInfo, false, false, true, d);
                        internalCuts34 = temp.getSecond();
                        cutMatch4 = temp.getFirst();
                        d4 = cutMatch4.delta;
                        minInternalDistance = cutMatch4.internalDelta < minInternalDistance ? cutMatch4.internalDelta
                                : minInternalDistance;
                        delta = d4 < delta ? d4 : delta;
                        countCalculated++;
                    } else {
                        countSkipped++;
                    }

                    CutInfo c5 = new CutInfo(shell, knotPoint11, knotPoint12, cutSegment1, external1, knotPoint22,
                            knotPoint21, cutSegment2, external2, knot, null, false);
                    double d5 = Double.MAX_VALUE, d7 = Double.MAX_VALUE, d6 = Double.MAX_VALUE, d8 = Double.MAX_VALUE;
                    CutMatchList cutMatch7 = null, cutMatch8 = null, cutMatch5 = null, cutMatch6 = null;
                    Pair<CutMatchList, RouteMap> internalCuts56 = null;
                    double mind5 = ixdarCutoff(c5, minInternalDistance);
                    if ((mind5 < minDelta || !ixdarSkip) && c5.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap>> temp = answerSharing(
                                internalCuts12, null, c5, sortedCutMatchInfo, answerSharing, checkAnswer, false, d);
                        internalCuts56 = temp.getSecond();
                        cutMatch5 = temp.getFirst();
                        d5 = cutMatch5.delta;
                        minInternalDistance = cutMatch5.internalDelta < minInternalDistance ? cutMatch5.internalDelta
                                : minInternalDistance;
                        delta = d5 < delta ? d5 : delta;
                    }
                    CutInfo c6 = c5.copyAndSwapExternals();
                    double mind6 = ixdarCutoff(c6, minInternalDistance);
                    if ((mind6 < minDelta || !ixdarSkip) && c6.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap>> temp = answerSharing(
                                internalCuts12, internalCuts56, c6, sortedCutMatchInfo, answerSharing,
                                checkAnswer, false, d);
                        internalCuts56 = temp.getSecond();
                        cutMatch6 = temp.getFirst();
                        d6 = cutMatch6.delta;
                        minInternalDistance = cutMatch6.internalDelta < minInternalDistance ? cutMatch6.internalDelta
                                : minInternalDistance;
                        delta = d6 < delta ? d6 : delta;
                    }

                    CutInfo c7 = new CutInfo(shell, knotPoint12, knotPoint11, cutSegment1, external1, knotPoint21,
                            knotPoint22, cutSegment2, external2, knot, null, false);
                    double mind7 = ixdarCutoff(c7, minInternalDistance);
                    Pair<CutMatchList, RouteMap> internalCuts78 = null;
                    if ((mind7 < minDelta || !ixdarSkip) && c7.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap>> temp = answerSharing(
                                internalCuts34, null, c7, sortedCutMatchInfo, answerSharing, checkAnswer, false, d);
                        internalCuts78 = temp.getSecond();
                        cutMatch7 = temp.getFirst();
                        d7 = cutMatch7.delta;
                        minInternalDistance = cutMatch7.internalDelta < minInternalDistance ? cutMatch7.internalDelta
                                : minInternalDistance;
                        delta = d7 < delta ? d7 : delta;
                    }
                    CutInfo c8 = c7.copyAndSwapExternals();

                    double mind8 = ixdarCutoff(c8, minInternalDistance);
                    if ((mind8 < minDelta || !ixdarSkip) && c8.overlapOrientationCorrect) {
                        Pair<CutMatchList, Pair<CutMatchList, RouteMap>> temp = answerSharing(
                                internalCuts34, internalCuts78, c8, sortedCutMatchInfo, answerSharing,
                                checkAnswer, false, d);
                        internalCuts78 = temp.getSecond();
                        cutMatch8 = temp.getFirst();
                        d8 = cutMatch8.delta;
                        minInternalDistance = cutMatch8.internalDelta < minInternalDistance ? cutMatch8.internalDelta
                                : minInternalDistance;
                        delta = d8 < delta ? d8 : delta;
                    }

                    if (delta < minDelta) {
                        // minDelta = delta;
                    }

                }
            }
        }
        sortedCutMatchInfo.sort();

        long endTimeCutMatchList = System.currentTimeMillis() - startTimeCutMatchList;
        ManifoldEngine.cutMatchListTime += ((double) endTimeCutMatchList) / 1000.0;
        return sortedCutMatchInfo;

    }

    private static double ixdarCutoff(CutInfo c, double minInternalDistance) {
        if (minInternalDistance == Double.MAX_VALUE) {
            return Double.MIN_VALUE;
        }
        return c.lowerMatchSegment.distance + c.upperMatchSegment.distance - c.lowerCutSegment.distance
                - c.upperCutSegment.distance;
    }

    public final static Pair<CutMatchList, Pair<CutMatchList, RouteMap>> answerSharing(
            Pair<CutMatchList, RouteMap> cutsOld,
            Pair<CutMatchList, RouteMap> cutsNew,
            CutInfo c, SortedCutMatchInfo sortedCutMatchInfo,
            boolean answerSharing, boolean checkAnswer, boolean knotPointsConnected, CutMatchDistanceMatrix d)
            throws SegmentBalanceException {

        CutMatchList cutMatch = null;
        boolean isNull = true;
        if (cutsOld != null && answerSharing) {
            RouteMap routeMap12 = cutsOld.getSecond();
            RouteInfo curr = routeMap12.get(c.upperKnotPoint.id);
            RouteType prevCutSide = RouteType.None;
            if (curr.prevC.neighbor.id == c.upperCutPoint.id) {
                prevCutSide = RouteType.prevDC;
            } else {
                prevCutSide = RouteType.nextDC;
            }

            Route route = curr.getRoute(prevCutSide);
            Route connectedRoute = curr.getRoute(prevCutSide.oppositeConnectionRoute);
            if (connectedRoute.ancestorRouteType != RouteType.None) {
                if (!connectedRoute.ancestorRouteType.isConnected) {
                    route = connectedRoute;
                }
            }
            if (route.ancestorRouteType == RouteType.None) {
                isNull = true;
            } else {
                ArrayList<Segment> cutSegments = route.cuts;
                ArrayList<Segment> matchSegments = route.matches;
                cutSegments.remove(c.lowerCutSegment);
                cutSegments.remove(c.upperCutSegment);

                CutMatchList cutMatchList = new CutMatchList(c.shell, c, c.knot);
                try {
                    cutMatchList.addLists(cutSegments, matchSegments, c.knot, "CutEngine5Cheating");
                } catch (SegmentBalanceException be) {
                    throw be;
                }
                cutMatch = new CutMatchList(c.shell, c, c.superKnot);
                try {
                    cutMatch.addCutMatch(new Segment[] { c.lowerCutSegment, c.upperCutSegment },
                            new Segment[] { c.lowerMatchSegment, c.upperMatchSegment }, cutMatchList, c, "CutEngine5");
                } catch (SegmentBalanceException be) {
                    throw be;
                }
                isNull = false;
                countSkipped++;
            }
        }

        /*
         * Plan for answerSharing for rotationally similar cutSegments:
         * 
         * So our idea is that we see if there is a route starting from the
         * lowerCutSegment and who has an uppercut Segment that is one over, in either
         * direction (counter or clockwise) from the other routes ending cut segment. in
         * order to do this we should store some way to easily look up the neighbor cut
         * segments. Also we need to keep in mind that flip cutSegments will not work
         * for this answer sharing scheme. so really we are looking for the same lower
         * cutSegment in the same orientation (knotpoint id and cutpoint id are the
         * same) as well as a 1 (upperCutSegment that has its upperKnotPoint as our
         * upperCutPoint its upperCutPoint that is not in our cutSegment) or 2
         * (upperCutSegment that has its upperCutPoint as our upperKnotpoint and its
         * upperKnotPoint is not in our cutSegment). We then Copy this route and do a
         * few things. First we reset all of the half segments that touch the two chosen
         * upperCutSegments. Second we copy over all of the routing information except
         * we change the groups such that the incorrect cut/knotpoint at the end of the
         * group is moved over to the other group. Finally we need to determine which of
         * the routeInfo Vertices in the RouteMap Graph are settled, the ones that we
         * reset are surely not settled anymore but we may need to unsettle all points
         * anyway in order to get inputs for the reset routes.
         * 
         * Data Storage:
         * 
         * We could store our data in a few ways, we know the size of the routeMap
         * instantly so we could store them in an array. We could also store them in a
         * Hashmap by lowerCutSegment id and then upper cutSegment id where the id is
         * order dependent. We could also store only the last routeMap that was used,
         * this would be fine for cuts where that are in order and all have the same
         * lowerCutSegments, but if we want to transfer across cutSegments this maybe
         * difficult. Could alternatively store the last routeMap and the routeMap that
         * has the next lowerCutSegment as its upperCutSegment and invert that routeMap
         * when the time comes. I think for now its probably better to not worry about
         * the inversion and just store the last routeMap and recalculate the full
         * routeMap on a new lowerCutSegment and then expand our needs when the time
         * comes.
         */

        if (isNull) {
            RouteMap copyRouteMap = null;
            HashMap<Long, RouteMap> lowerSegmentMap = null;
            RouteMap neighborRouteMap = null;
            if (Toggle.IxdarRotationalAnswerSharing.value) {
                long routeMapCopyTimeStart = System.currentTimeMillis();
                Long lowerSegIdOrdered = Segment.idTransformOrdered(c.lowerCutPoint.id, c.lowerKnotPoint.id);
                if (!sortedCutMatchInfo.routeMapBySegmentId.containsKey(lowerSegIdOrdered)) {
                    sortedCutMatchInfo.routeMapBySegmentId.put(lowerSegIdOrdered, new HashMap<>());
                }
                lowerSegmentMap = sortedCutMatchInfo.routeMapBySegmentId
                        .get(lowerSegIdOrdered);

                // (upperCutSegment that has its upperKnotPoint as our
                // * upperCutPoint its upperCutPoint that is not in our cutSegment)
                VirtualPoint upperCutPointNeighbor = c.knot.getOtherNeighbor(c.upperCutPoint, c.upperKnotPoint);
                Long upperCutNeighborSegIdOrdered = Segment.idTransformOrdered(upperCutPointNeighbor.id,
                        c.upperCutPoint.id);
                // * (upperCutSegment that has its upperCutPoint as our upperKnotPoint and its
                // * upperKnotPoint is not in our cutSegment)
                VirtualPoint upperKnotPointNeighbor = c.knot.getOtherNeighbor(c.upperKnotPoint, c.upperCutPoint);
                Long upperKnotNeighborSegIdOrdered = Segment.idTransformOrdered(c.upperKnotPoint.id,
                        upperKnotPointNeighbor.id);
                if (lowerSegmentMap.containsKey(upperCutNeighborSegIdOrdered)) {
                    neighborRouteMap = lowerSegmentMap.get(upperCutNeighborSegIdOrdered);
                } else if (lowerSegmentMap.containsKey(upperKnotNeighborSegIdOrdered)) {
                    neighborRouteMap = lowerSegmentMap.get(upperKnotNeighborSegIdOrdered);
                }
                if (neighborRouteMap != null) {
                    if (c.lowerKnotPoint.id != neighborRouteMap.c.lowerKnotPoint.id
                            || c.lowerCutPoint.id != neighborRouteMap.c.lowerCutPoint.id) {
                        throw new SegmentBalanceException(c);
                    }
                    copyRouteMap = new RouteMap(neighborRouteMap, c.upperCutPoint, c.upperKnotPoint, c);
                    routeMapsCopied++;
                }
                totalRoutes++;

                long routeMapCopyTimeEnd = System.currentTimeMillis() - routeMapCopyTimeStart;
                routeMapCopyTime += ((double) routeMapCopyTimeEnd) / 1000.0;

            }
            // problem is that we have the exact same route twice in a row? WRONG
            if (cutsNew == null) {
                if (neighborRouteMap == null) {
                    // 75%
                    cutsNew = InternalPathEngine.calculateInternalPathLength(c, copyRouteMap, d);
                } else {
                    // 39% copy becomes 24% run time
                    cutsNew = InternalPathEngine.calculateInternalPathLength(c, copyRouteMap, d);
                }
            }
            cutMatch = new CutMatchList(c.shell, c, c.superKnot);
            cutMatch.addCutMatch(new Segment[] { c.lowerCutSegment, c.upperCutSegment },
                    new Segment[] { c.lowerMatchSegment, c.upperMatchSegment }, cutsNew.getFirst(), c, "CutEngine5");
            if (Toggle.IxdarRotationalAnswerSharing.value) {
                lowerSegmentMap.put(Segment.idTransformOrdered(c.upperCutPoint.id, c.upperKnotPoint.id),
                        cutsNew.getSecond());
            }
            countCalculated++;
        }
        if (answerSharing && checkAnswer) {
            if (cutsNew == null) {
                cutsNew = InternalPathEngine.calculateInternalPathLength(c, null, d);
                CutMatchList cutMatchCheck = new CutMatchList(c.shell, c, c.superKnot);
                cutMatchCheck.addCutMatch(new Segment[] { c.lowerCutSegment, c.upperCutSegment },
                        new Segment[] { c.lowerMatchSegment, c.upperMatchSegment }, cutsNew.getFirst(), c,
                        "CutEngine5");

                if (cutMatchCheck.delta != cutMatch.delta) {
                    cutMatch = cutMatchCheck;
                }
            }
        }
        sortedCutMatchInfo.add(cutMatch, c.lowerCutSegment, c.lowerKnotPoint);
        sortedCutMatchInfo.add(cutMatch, c.upperCutSegment, c.upperKnotPoint);
        return new Pair<CutMatchList, Pair<CutMatchList, RouteMap>>(cutMatch, cutsNew);

    }

}
