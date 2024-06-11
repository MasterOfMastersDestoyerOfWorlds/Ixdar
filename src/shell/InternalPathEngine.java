package shell;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.commons.math3.util.Pair;

public class InternalPathEngine {
    Shell shell;
    CutEngine cutEngine;

    public InternalPathEngine(Shell shell, CutEngine cutEngine) {
        this.shell = shell;
        this.cutEngine = cutEngine;
    }

    public CutMatchList calculateInternalPathLength(
            VirtualPoint knotPoint1, VirtualPoint cutPointA, VirtualPoint external1,
            VirtualPoint knotPoint2, VirtualPoint cutPointB, VirtualPoint external2,
            Knot knot, BalanceMap balanceMap) throws SegmentBalanceException, BalancerException {

        SegmentBalanceException sbe = new SegmentBalanceException(shell, null,
                new CutInfo(shell, knotPoint1, cutPointA, knotPoint1.getClosestSegment(cutPointA, null), external1,
                        knotPoint2, cutPointB, knotPoint2.getClosestSegment(cutPointB, null), external2, knot, balanceMap));

        shell.buff.add("recutting knot: " + knot);
        shell.buff.add(
                "knotPoint1: " + knotPoint1 + " external1: " + external1);
        shell.buff.add(
                "knotPoint2: " + knotPoint2 + " external2: " + external2);
        shell.buff.add(
                "cutPointA: " + cutPointA + " cutPointB: " + cutPointB);
        shell.buff.add(
                "flatKnots: " + cutEngine.flatKnots);

        int smallestKnotIdA = shell.smallestKnotLookup[cutPointA.id];
        int smallestKnotIdB = shell.smallestKnotLookup[cutPointB.id];

        Knot topKnot = cutEngine.flatKnots.get(smallestKnotIdA);
        VirtualPoint topPoint = cutPointA;
        VirtualPoint topKnotPoint = knotPoint1;

        Knot botKnot = cutEngine.flatKnots.get(smallestKnotIdB);
        VirtualPoint botPoint = cutPointB;
        VirtualPoint botKnotPoint = knotPoint2;

        if (topKnot.size() < botKnot.size()) {
            topPoint = cutPointB;
            topKnotPoint = knotPoint2;
            botPoint = cutPointA;
            botKnotPoint = knotPoint1;
        }
        shell.buff.add("topPoint: " + topPoint);
        shell.buff.add("botPoint: " + botPoint);
        shell.buff.add("topKnotPoint: " + topKnotPoint);
        shell.buff.add("botKNotPoint: " + botKnotPoint);

        Knot minKnot = findMinKnot(topKnotPoint, topPoint, botKnotPoint, botPoint, knot, sbe);

        if (minKnot.equals(knot)) {
            Segment connector = cutPointA.getClosestSegment(cutPointB, null);
            CutMatchList cutMatchList = new CutMatchList(shell, sbe, knot);
            cutMatchList.addSimpleMatch(connector, knot, "InternalPathEngineMinKnotIsKnot");
            return cutMatchList;
        }
        // if neither orphan is on the top level, find their minimal knot in common and
        // recut it with the external that matched to the knot and its still matched
        // neighbor

        CutMatchList reCut;
        if (topPoint.equals(cutPointA)) {
            reCut = recutWithInternalNeighbor(knotPoint1, cutPointA, external1, knotPoint2, cutPointB, external2,
                    minKnot,
                    knot, sbe, balanceMap);
        } else {
            if (topPoint.id == 15 && botPoint.id == 13 && (knotPoint1.id == 11 || knotPoint2.id == 11)) {
                // float z = 1 / 0;
            }
            reCut = recutWithInternalNeighbor(knotPoint2, cutPointB, external2, knotPoint1, cutPointA, external1,
                    minKnot,
                    knot, sbe, balanceMap);

        }
        return reCut;

    }

    public CutMatchList recutWithInternalNeighbor(VirtualPoint topKnotPoint,
            VirtualPoint topPoint, VirtualPoint topExternal, VirtualPoint botKnotPoint, VirtualPoint botPoint,
            VirtualPoint botExternal, Knot minKnot,
            Knot knot, SegmentBalanceException sbe, BalanceMap balanceMap) throws SegmentBalanceException, BalancerException {

        Segment kpSegment = knot.getSegment(topKnotPoint, botKnotPoint);

        // what we want is the minimum knot that contains one cut segment and not the
        // other
        // if there is no such segment can we simple connect?

        VirtualPoint kp = botKnotPoint;
        VirtualPoint kp2 = topKnotPoint;
        VirtualPoint vp = botPoint;
        VirtualPoint vp2 = topPoint;
        VirtualPoint ex = botExternal;
        VirtualPoint ex2 = topExternal;
        if (!minKnot.contains(botKnotPoint) || !minKnot.contains(botPoint)) {
            kp = topKnotPoint;
            kp2 = botKnotPoint;
            vp = topPoint;
            vp2 = botPoint;
            ex = topExternal;
            ex2 = botExternal;

        }

        shell.buff.add("MINKNOT:::::::::::::::::::: " + minKnot);
        boolean bothCutPointsOutside = !minKnot.contains(vp) && !minKnot.contains(vp2);
        boolean bothKnotPointsInside = minKnot.contains(kp) && minKnot.contains(kp2);
        boolean bothKnotPointsOutside = !minKnot.contains(kp) && !minKnot.contains(kp2);
        boolean upperCutPointIsOutside = !minKnot.contains(vp2);

        // TODO: If the cutPoint is the neighbor, we need to find two matches for the
        // neighbor instead of doing the simple match, the below code should not
        // run/should be deleted

        // need a new funciton with the following idea, should only be called when the
        // top cut point is outside the minKnot and should find the maximum knot that
        // contains the top cut point
        // but does not contain the top Knot Point, or any points in the minKnot. should
        // be different from the minKnot finding function in that it could also produce
        // a single point (the top cut point) as its output and should not contain any
        // of the cut segments.

        Segment upperCutSegment = null;
        Segment lowerCutSegment = null;

        upperCutSegment = knot.getSegment(kp2, vp2);
        lowerCutSegment = knot.getSegment(kp, vp);
        shell.buff.add("lower cut Segmnet " + lowerCutSegment);

        Segment upperMatchSegment = kp2.getClosestSegment(ex2, null);
        Segment lowerMatchSegment = kp.getClosestSegment(ex, null);

        Segment cut = minKnot.getSegment(vp, kp);
        if (bothCutPointsOutside) {
            cut = minKnot.getSegment(kp, vp);
            lowerCutSegment = knot.getSegment(kp, vp);
            upperCutSegment = knot.getSegment(kp2, vp2);
        }

        if (upperCutSegment.equals(cut)) {
            shell.buff.add("upper cut equals lower cut");
            shell.buff.add(upperCutSegment);
            shell.buff.add(cut);
            new CutMatchList(shell, sbe, knot);
            throw new SegmentBalanceException(sbe);
        }
        VirtualPoint outsideUpperCutPoint = kp2;
        if (upperCutPointIsOutside && minKnot.contains(kp2)) {
            outsideUpperCutPoint = vp2;
        }
        // boolean bothKnotPointsOutside = !minKnot.contains(kp) &&
        // !minKnot.contains(kp2);
        Knot prevMinknot = minKnot;

        bothCutPointsOutside = !minKnot.contains(vp) && !minKnot.contains(vp2);
        bothKnotPointsInside = minKnot.contains(kp) && minKnot.contains(kp2);
        bothKnotPointsOutside = !minKnot.contains(kp) && !minKnot.contains(kp2);
        upperCutPointIsOutside = !minKnot.contains(vp2);

        // if (minKnot.contains(kp) && minKnot.contains(kp2)) {
        // Segment connector = topPoint.getClosestSegment(botPoint, null);
        // CutMatchList cutMatchList = new CutMatchList(shell, sbe, knot);
        // cutMatchList.addSimpleMatch(connector, knot);
        // return cutMatchList;
        // }
        // this should actually be more like if minknot doesn't contain vp2 and neighbor
        // doesn't then go up a level

        ArrayList<Segment> neighborSegments = new ArrayList<Segment>();
        ArrayList<VirtualPoint> potentialNeighbors = new ArrayList<VirtualPoint>();
        ArrayList<VirtualPoint> innerPotentialNeighbors = new ArrayList<VirtualPoint>();
        MultiKeyMap<Integer, Segment> neighborSegmentLookup = new MultiKeyMap<>();
        MultiKeyMap<Integer, Segment> innerNeighborSegmentLookup = new MultiKeyMap<>();
        HashMap<Integer, Segment> singleNeighborSegmentLookup = new HashMap<>();
        int startIdx = knot.knotPoints.indexOf(minKnot.knotPoints.get(0));
        int endIdx = startIdx - 1 < 0 ? knot.knotPoints.size() - 1 : startIdx - 1;
        VirtualPoint firstInnerNeighbor = null;
        Segment firstNeighborSegment = null;
        int k = startIdx;

        while (true) {
            VirtualPoint k1 = knot.knotPoints.get(k);
            VirtualPoint k2 = knot.getNext(k);
            if (minKnot.contains(k1) && !minKnot.contains(k2)) {
                Segment neighborSegment = knot.getSegment(k1, k2);
                neighborSegments.add(neighborSegment);
                potentialNeighbors.add(k2);
                innerPotentialNeighbors.add(k1);
                firstInnerNeighbor = k1;
                firstNeighborSegment = neighborSegment;
            }
            if (minKnot.contains(k2) && !minKnot.contains(k1)) {
                Segment neighborSegment = knot.getSegment(k1, k2);
                neighborSegments.add(neighborSegment);
                if (minKnot.hasSegment(knot.getSegment(firstInnerNeighbor, k2))) {
                    int first = Segment.getFirstOrderId(firstInnerNeighbor, k2);
                    int last = Segment.getLastOrderId(firstInnerNeighbor, k2);
                    neighborSegmentLookup.put(first, last, neighborSegment);
                    innerNeighborSegmentLookup.put(Segment.getFirstOrderId(neighborSegment.first, neighborSegment.last),
                            Segment.getLastOrderId(neighborSegment.first, neighborSegment.last),
                            minKnot.getSegment(firstInnerNeighbor, k2));
                    innerNeighborSegmentLookup.put(
                            Segment.getFirstOrderId(firstNeighborSegment.first, firstNeighborSegment.last),
                            Segment.getLastOrderId(firstNeighborSegment.first, firstNeighborSegment.last),
                            minKnot.getSegment(firstInnerNeighbor, k2));
                } else {
                    singleNeighborSegmentLookup.put(k2.id, neighborSegment);
                    singleNeighborSegmentLookup.put(firstInnerNeighbor.id, firstNeighborSegment);
                }
                potentialNeighbors.add(k1);
                innerPotentialNeighbors.add(k2);
            }
            if (k == endIdx) {
                break;
            }
            k = k + 1 >= knot.knotPoints.size() ? 0 : k + 1;
        }
        shell.buff.add("neighborSegmentLookup : " + neighborSegmentLookup);
        shell.buff.add("singleNeighborSegmentLookup : " + singleNeighborSegmentLookup);
        shell.buff.add("innerNeighborSegmentLookup: " + innerNeighborSegmentLookup);

        ArrayList<Segment> innerNeighborSegments = new ArrayList<>();
        for (int j = 0; j < minKnot.size(); j++) {
            VirtualPoint k1 = minKnot.knotPoints.get(j);
            VirtualPoint k2 = minKnot.knotPoints.get(j + 1 >= minKnot.knotPoints.size() ? 0 : j + 1);
            Segment candidate = knot.getSegment(k1, k2);
            if (!knot.hasSegment(candidate)) {
                boolean intersect = false;
                for (Segment s : neighborSegments) {
                    if (s.intersects(candidate)) {
                        intersect = true;
                    }
                }
                int idx = knot.knotPoints.indexOf(k1);
                int idx2 = knot.knotPoints.indexOf(k2);
                VirtualPoint endPoint = k2;
                VirtualPoint nextPoint = knot.getNext(idx);
                VirtualPoint prevPoint = knot.getPrev(idx);
                VirtualPoint edgePoint = k1;
                if (minKnot.contains(prevPoint) && minKnot.contains(nextPoint)) {
                    int tmp = idx;
                    idx = idx2;
                    idx2 = tmp;
                    endPoint = k1;
                    edgePoint = k2;
                }

                int first = endPoint.id < edgePoint.id ? endPoint.id : edgePoint.id;
                int last = Segment.getLastOrderId(endPoint, edgePoint);
                Segment neighborSegment = null;
                if (neighborSegmentLookup.containsKey(first, last)) {
                    neighborSegment = neighborSegmentLookup.get(first, last);
                    if (neighborSegment.contains(endPoint)) {
                        VirtualPoint tmp = endPoint;
                        endPoint = edgePoint;
                        edgePoint = tmp;
                    }
                    VirtualPoint neighborPoint = neighborSegment.getOther(edgePoint);
                    idx = knot.knotPoints.indexOf(edgePoint);
                    idx2 = knot.knotPoints.indexOf(neighborPoint);
                } else {
                    if (singleNeighborSegmentLookup.containsKey(edgePoint.id)) {
                        neighborSegment = singleNeighborSegmentLookup.get(edgePoint.id);
                    } else if (singleNeighborSegmentLookup.containsKey(endPoint.id)) {
                        neighborSegment = singleNeighborSegmentLookup.get(endPoint.id);
                    }
                    if (neighborSegment == null) {
                        new CutMatchList(shell, sbe, knot);
                        throw new SegmentBalanceException(sbe);
                    }
                    if (neighborSegment.contains(endPoint)) {
                        VirtualPoint tmp = endPoint;
                        endPoint = edgePoint;
                        edgePoint = tmp;
                    }
                    VirtualPoint neighborPoint = neighborSegment.getOther(edgePoint);
                    idx = knot.knotPoints.indexOf(edgePoint);
                    idx2 = knot.knotPoints.indexOf(neighborPoint);

                }

                int marchDirection = idx2 - idx < 0 ? -1 : 1;
                if (idx == 0 && idx2 == knot.knotPoints.size() - 1) {
                    marchDirection = -1;
                }
                if (idx2 == 0 && idx == knot.knotPoints.size() - 1) {
                    marchDirection = 1;
                }
                int next = idx + marchDirection;
                if (marchDirection < 0 && next < 0) {
                    next = knot.knotPoints.size() - 1;
                } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                    next = 0;
                }

                if (minKnot.contains(knot.knotPoints.get(next))) {
                    marchDirection = -marchDirection;
                }
                VirtualPoint curr = knot.knotPoints.get(idx);
                while (!curr.equals(endPoint)) {
                    curr = knot.knotPoints.get(idx);
                    next = idx + marchDirection;
                    if (marchDirection < 0 && next < 0) {
                        next = knot.knotPoints.size() - 1;
                    } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                        next = 0;
                    }
                    VirtualPoint nextp = knot.knotPoints.get(next);
                    if (curr.equals(outsideUpperCutPoint)) {
                        intersect = false;
                    }
                    if (minKnot.contains(nextp)) {
                        break;
                    }
                    idx = next;
                }
                Segment check = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(neighborSegment),
                        Segment.getLastOrderId(neighborSegment));
                if (intersect) {
                    innerNeighborSegments.add(candidate);
                } else if (check == null) {
                    innerNeighborSegmentLookup.put(Segment.getFirstOrderId(neighborSegment),
                            Segment.getLastOrderId(neighborSegment), candidate);

                }
            }
        }
        neighborSegments.remove(upperCutSegment);
        potentialNeighbors.remove(kp2);
        innerPotentialNeighbors.remove(upperCutSegment.getOther(kp2));
        if (!minKnot.hasSegment(cut)) {
            neighborSegments.remove(cut);
            innerPotentialNeighbors.remove(kp);
        }

        /*
         * neighbor should satisfy the following conditions:
         * - be a point in the potential neighbors list that is not in minKnot,
         * - is from the same knot as the upper knot point, or is the cut point
         * - is of the lowest order knot (I think with the above condition that this is
         * not necessary)
         * - is not one of the knot points
         * - if one of the cut points isn't in the minKnot the upper cut point is the
         * neighbor
         * 
         * should be able to test whether its in the same knot as the upper knot point
         * by checking
         * their smallest common knot does not contain the minKnot
         * Do we need a contains list for each flat knot?
         */

        VirtualPoint neighbor = null;
        if (upperCutPointIsOutside) {
            neighbor = vp2;
        } else {
            if (potentialNeighbors.size() == 0) {
                float z = 1;
            }
            neighbor = Utils.marchLookup(knot, kp2, vp2, potentialNeighbors).getSecond();
        }
        ArrayList<Pair<Segment, VirtualPoint>> neighborCuts = new ArrayList<>();
        // TODO: when both knotpoints are on the inside We need to include in
        // neighborCuts the segments which
        // are connected to the knotPoints after the cuts are done.
        for (Segment s : neighborSegments) {
            if (s.contains(neighbor)) {
                neighborCuts.add(new Pair<>(s, neighbor));
                continue;
            }
            if (upperCutPointIsOutside) {
                VirtualPoint candidate = s.getOtherKnot(minKnot);
                boolean isNeighbor = Utils.marchContains(candidate, s, neighbor, knot, minKnot);
                if (isNeighbor) {
                    neighborCuts.add(new Pair<>(s, candidate));
                    continue;
                }
            }
            if (bothKnotPointsInside) {
                boolean innerNeighborSegmentHasKnotPoint = false;
                Segment innerNeighborSegment = innerNeighborSegmentLookup.get(Segment.getFirstOrderId(s.first, s.last),
                        Segment.getLastOrderId(s.first, s.last));
                if (innerNeighborSegment != null) {

                    if (innerNeighborSegment.contains(kp) || innerNeighborSegment.contains(kp2)
                            || innerNeighborSegment.contains(vp)) {
                        innerNeighborSegmentHasKnotPoint = true;
                    }
                }
                if (s.contains(kp) || s.contains(kp2) || s.contains(vp) || innerNeighborSegmentHasKnotPoint) {
                    VirtualPoint candidate = s.getOtherKnot(minKnot);
                    neighborCuts.add(new Pair<>(s, candidate));
                }
            }
        }

        if (upperCutSegment.contains(neighbor) && upperCutSegment.contains(vp)) {
            shell.buff.add("rematching cut segment");
            new CutMatchList(shell, sbe, knot);
            throw new SegmentBalanceException(sbe);
        }

        // TODO: djbouti_8-26_finalCut_cut9-10and0-2
        // Problem, we are picking the wrong neighbor for the following reason, the
        // niebor should be in the
        // same knot as the upper cut point

        CutMatchList reCut = null;
        if (!minKnot.hasSegment(cut) && !bothKnotPointsOutside) {
            int idx = minKnot.knotPoints.indexOf(kp);
            VirtualPoint rotationPoint = kp;
            if (idx == -1) {
                idx = minKnot.knotPoints.indexOf(kp2);
                rotationPoint = kp2;
            }
            if (idx == -1) {
                idx = minKnot.knotPoints.indexOf(vp);
                rotationPoint = vp;
            }
            if (idx == -1) {
                idx = minKnot.knotPoints.indexOf(vp2);
                rotationPoint = vp2;
            }
            if (idx == -1) {

                shell.buff.add("MINKNOT: " + minKnot);

                new CutMatchList(shell, sbe, knot);
                throw new SegmentBalanceException(sbe);
            }
            VirtualPoint rightPoint = minKnot.knotPoints.get(idx + 1 > minKnot.knotPoints.size() - 1 ? 0 : idx + 1);
            Segment rightCut = minKnot.getSegment(rotationPoint, rightPoint);
            VirtualPoint leftPoint = minKnot.knotPoints.get(idx - 1 < 0 ? minKnot.knotPoints.size() - 1 : idx - 1);
            Segment leftCut = minKnot.getSegment(rotationPoint, leftPoint);

            shell.buff.add(leftCut);
            shell.buff.add(rightCut);

            ArrayList<Segment> rightInnerNeighborSegments = new ArrayList<>();
            if (upperCutPointIsOutside) {
                for (Segment s : innerNeighborSegments) {
                    if (!s.contains(rightPoint) && (singleNeighborSegmentLookup.containsKey(rightPoint.id)
                            || s.contains(rightPoint)
                                    && singleNeighborSegmentLookup.containsKey(s.getOther(rightPoint).id))) {
                        rightInnerNeighborSegments.add(s);
                    }
                }

            } else {
                rightInnerNeighborSegments = innerNeighborSegments;
            }

            ArrayList<Segment> leftInnerNeighborSegments = new ArrayList<>();
            if (upperCutPointIsOutside) {
                for (Segment s : innerNeighborSegments) {
                    if (!s.contains(leftPoint) && (singleNeighborSegmentLookup.containsKey(leftPoint.id)
                            || s.contains(leftPoint)
                                    && singleNeighborSegmentLookup.containsKey(s.getOther(leftPoint).id))) {
                        leftInnerNeighborSegments.add(s);
                    }
                }

            } else {
                leftInnerNeighborSegments = innerNeighborSegments;
            }

            boolean canCutLeft = !leftInnerNeighborSegments.contains(leftCut) && !rightCut.equals(kpSegment);
            CutMatchList leftCutMatch = null;
            CutInfo lc = new CutInfo(shell, minKnot, ex, neighbor, leftCut, kp, leftPoint, knot,
                    kpSegment,
                    leftInnerNeighborSegments, innerNeighborSegmentLookup, neighborSegments,
                    neighborCuts, vp2,
                    upperCutPointIsOutside, bothKnotPointsInside, bothKnotPointsOutside, bothCutPointsOutside, kp2,
                    upperMatchSegment,
                    upperCutSegment, kp,
                    lowerMatchSegment, lowerCutSegment, balanceMap);
            if (lc.cutID == 852) {
                float z = 1;
            }
            shell.buff.add("cutting left");

            shell.buff.add("LEFTCUT : " + lc);
            if (canCutLeft) {
                leftCutMatch = new FixedCut(lc).findCutMatchListFixedCut();

                leftCutMatch.addCutDiff(leftCut, knot, "InternalPathEngineLeft");
                leftCutMatch.removeCut(cut);
            }
            shell.buff.add("cutting right");

            boolean canCutRight = !rightInnerNeighborSegments.contains(rightCut) && !leftCut.equals(kpSegment);
            CutMatchList rightCutMatch = null;
            CutInfo rc = new CutInfo(shell, minKnot, ex, neighbor, rightCut, kp, rightPoint,
                    knot,
                    kpSegment,
                    rightInnerNeighborSegments, innerNeighborSegmentLookup, neighborSegments,
                    neighborCuts, vp2,
                    upperCutPointIsOutside, bothKnotPointsInside, bothKnotPointsOutside, bothCutPointsOutside, kp2,
                    upperMatchSegment,
                    upperCutSegment, kp, lowerMatchSegment,
                    lowerCutSegment, balanceMap);
            if (canCutRight) {
                rightCutMatch = new FixedCut(rc).findCutMatchListFixedCut();
                rightCutMatch.addCutDiff(rightCut, knot, "InternalPathEngineRight");
                rightCutMatch.removeCut(cut);
            }

            if (!canCutLeft && !canCutRight) {
                new CutMatchList(shell, sbe, knot);
                throw new SegmentBalanceException(sbe);
            }

            if (canCutRight && (!canCutLeft || rightCutMatch.delta < leftCutMatch.delta)) {
                reCut = rightCutMatch;
            } else {
                reCut = leftCutMatch;
            }
            shell.buff.add("cut Left: " + leftCut + "cut Right: " + rightCut);
            shell.buff.add(
                    "chose right? : " + (canCutRight && (!canCutLeft || rightCutMatch.delta < leftCutMatch.delta)));
            shell.buff.add("RightCUT : " + rc);

        } else {
            ArrayList<Segment> removeList = new ArrayList<>();
            if (upperCutPointIsOutside) {
                for (Segment s : innerNeighborSegments) {
                    if (s.contains(vp) && (singleNeighborSegmentLookup.containsKey(vp.id)
                            || singleNeighborSegmentLookup.containsKey(s.getOther(vp).id))) {
                        removeList.add(s);
                    }
                }
                innerNeighborSegments.removeAll(removeList);

            }
            CutInfo c = new CutInfo(shell, minKnot, ex, neighbor, cut, kp, vp, knot, kpSegment,
                    innerNeighborSegments, innerNeighborSegmentLookup, neighborSegments, neighborCuts,
                    vp2, upperCutPointIsOutside,
                    bothKnotPointsInside, bothKnotPointsOutside, bothCutPointsOutside, kp2, upperMatchSegment,
                    upperCutSegment, kp, lowerMatchSegment,
                    lowerCutSegment, balanceMap);
            shell.buff.add(c);

            reCut = new FixedCut(c).findCutMatchListFixedCut();

        }
        if (reCut.delta == 0.0 && upperCutPointIsOutside) {
            new CutMatchList(shell, sbe, knot);
            throw new SegmentBalanceException(sbe);
        }

        if (reCut.delta == 0.0) {
            Segment connector = vp2.getClosestSegment(vp, null);
            CutMatchList cutMatchList = new CutMatchList(shell, sbe, knot);
            cutMatchList.addSimpleMatch(connector, knot, "InternalPathEngineDeltaZero");
            reCut = cutMatchList;
        }
        return reCut;
    }

    Knot findExpandedKnot(Knot knot, Knot minKnot, VirtualPoint innerPoint, VirtualPoint knotPoint,
            Segment upperCutSegment,
            SegmentBalanceException sbe) throws SegmentBalanceException {
        if (minKnot.hasPoint(11) && !knot.hasPoint(11) && knotPoint.id == 5 && knot.size() == 5) {
            float z = 1;

        }
        int startIdx = knot.knotPoints.indexOf(innerPoint);
        int endIdx = startIdx - 1 < 0 ? knot.knotPoints.size() - 1 : startIdx - 1;
        VirtualPoint firstInnerNeighbor = null;
        Segment firstNeighborSegment = null;
        int k = startIdx;
        ArrayList<Segment> loopSegments = new ArrayList<>();
        ArrayList<Segment> expandedSegments = new ArrayList<>();
        boolean hasCutSegment = false;
        if (k == -1) {
            new CutMatchList(shell, sbe, knot);
            throw new SegmentBalanceException(sbe);
        }
        while (true) {
            VirtualPoint k1 = knot.knotPoints.get(k);
            VirtualPoint k2 = knot.getNext(k);
            Segment neighborSegment = knot.getSegment(k1, k2);
            if (minKnot.contains(k1) && !minKnot.contains(k2)) {
                firstInnerNeighbor = k1;
                firstNeighborSegment = neighborSegment;
                loopSegments = new ArrayList<>();
                hasCutSegment = false;
            }
            loopSegments.add(neighborSegment);
            if (neighborSegment.equals(upperCutSegment)) {
                hasCutSegment = true;
            }
            if (minKnot.contains(k2) && !minKnot.contains(k1)) {
                if (!hasCutSegment) {
                    expandedSegments.addAll(loopSegments);
                } else {
                    expandedSegments.add(knot.getSegment(firstInnerNeighbor, k2));
                }
            } else if (minKnot.contains(k2) && minKnot.contains(k1)) {
                expandedSegments.add(neighborSegment);
            }
            if (k == endIdx) {
                break;
            }
            k = k + 1 >= knot.knotPoints.size() ? 0 : k + 1;
        }
        boolean sameSegs = true;
        boolean fFlag = true;
        for (Segment s : expandedSegments) {
            if (!minKnot.contains(s.first) || !minKnot.contains(s.last)) {
                sameSegs = false;
            }
            if (!knot.contains(s.first) || !knot.contains(s.last)) {
                fFlag = false;
            }
        }
        if ((expandedSegments.size() != minKnot.size() || !sameSegs)
                && (expandedSegments.size() != knot.knotPoints.size() || !fFlag)) {
            ArrayList<VirtualPoint> points = Utils.segmentListToPath(expandedSegments);
            Knot result = new Knot(points, shell, false);
            return result;
        }
        
        for(VirtualPoint vp : minKnot.knotPointsFlattened){
            if(!knot.contains(vp)){
                float z = 0;
            }
        }
        return minKnot;
    }

    private VirtualPoint getMaxKnotExclude(VirtualPoint vp2, VirtualPoint kp2, Knot minKnot) {

        int sizeMinKnot = 1;
        VirtualPoint result = vp2;
        for (Knot k : cutEngine.flatKnots.values()) {
            int size = k.size();
            if (size > sizeMinKnot && !k.contains(kp2) && !k.overlaps(minKnot)) {
                result = k;
                sizeMinKnot = size;
            }
        }
        return result;

    }

    public Knot findMinKnot(VirtualPoint knotPoint1, VirtualPoint cutPointA, VirtualPoint external1,
            VirtualPoint knotPoint2, VirtualPoint cutPointB, VirtualPoint external2,
            Knot knot, BalanceMap balanceMap) throws SegmentBalanceException {

        int smallestKnotIdA = shell.smallestKnotLookup[cutPointA.id];
        int smallestKnotIdB = shell.smallestKnotLookup[cutPointB.id];

        Knot topKnot = cutEngine.flatKnots.get(smallestKnotIdA);
        VirtualPoint topPoint = cutPointA;
        VirtualPoint topKnotPoint = knotPoint1;

        Knot botKnot = cutEngine.flatKnots.get(smallestKnotIdB);
        VirtualPoint botPoint = cutPointB;
        VirtualPoint botKnotPoint = knotPoint2;

        if (topKnot.size() < botKnot.size()) {
            topPoint = cutPointB;
            topKnotPoint = knotPoint2;
            botPoint = cutPointA;
            botKnotPoint = knotPoint1;
        }
        CutInfo c = new CutInfo(shell, knotPoint1, cutPointA, knotPoint1.getClosestSegment(cutPointA, null), external1,
                knotPoint2, cutPointB, knotPoint2.getClosestSegment(cutPointB, null), external2, knot, balanceMap);
        SegmentBalanceException sbe = new SegmentBalanceException(shell, null, c);
        Knot minKnot = findMinKnot(topKnotPoint, topPoint, botKnotPoint, botPoint, knot, sbe);
        return minKnot;

    }

    public Knot findMinKnot(VirtualPoint topKnotPoint, VirtualPoint topPoint, VirtualPoint botKnotPoint,
            VirtualPoint botPoint, Knot knot, SegmentBalanceException sbe) throws SegmentBalanceException {

        if (topKnotPoint.id == 0 && topPoint.id == 10 && botKnotPoint.id == 1 && botPoint.id == 2 && knot.hasPoint(5)) {
            float z = 0;
        }

        int matchKnotAId = shell.smallestCommonKnotLookup[topPoint.id][topKnotPoint.id];
        Knot matchKnotA = cutEngine.flatKnots.get(matchKnotAId);
        int aSize = matchKnotA == null ? Integer.MAX_VALUE : matchKnotA.knotPoints.size();

        int matchKnotBId = shell.smallestCommonKnotLookup[botPoint.id][botKnotPoint.id];
        Knot matchKnotB = cutEngine.flatKnots.get(matchKnotBId);
        int bSize = matchKnotB == null ? Integer.MAX_VALUE : matchKnotB.knotPoints.size();

        int knotPointKnotId = shell.smallestCommonKnotLookup[topKnotPoint.id][botKnotPoint.id];
        Knot knotPointKnot = cutEngine.flatKnots.get(knotPointKnotId);
        int kpSize = knotPointKnot == null ? Integer.MAX_VALUE : knotPointKnot.knotPoints.size();

        int crossTopKnotId = shell.smallestCommonKnotLookup[topPoint.id][botKnotPoint.id];
        Knot crossTopKnot = cutEngine.flatKnots.get(crossTopKnotId);
        int ctSize = crossTopKnot == null ? Integer.MAX_VALUE : crossTopKnot.knotPoints.size();

        int crossBotKnotId = shell.smallestCommonKnotLookup[topKnotPoint.id][botPoint.id];
        Knot crossBotKnot = cutEngine.flatKnots.get(crossBotKnotId);
        int cbSize = crossBotKnot == null ? Integer.MAX_VALUE : crossBotKnot.knotPoints.size();

        int cutPointKnotId = shell.smallestCommonKnotLookup[topPoint.id][botPoint.id];
        Knot cutPointKnot = cutEngine.flatKnots.get(cutPointKnotId);
        int cpSize = cutPointKnot == null ? Integer.MAX_VALUE : cutPointKnot.knotPoints.size();

        int sizeMinKnot;
        Knot minKnot;
        if (aSize < bSize && aSize < kpSize && aSize < ctSize && aSize < cbSize && aSize < cpSize) {
            minKnot = matchKnotA;
        } else if (bSize < kpSize && bSize < ctSize && bSize < cbSize && bSize < cpSize) {
            minKnot = matchKnotB;
        } else if (kpSize < ctSize && kpSize < cbSize && kpSize < cpSize) {
            minKnot = knotPointKnot;
        } else if (ctSize < cbSize && ctSize < cpSize) {
            minKnot = crossTopKnot;
        } else if (cbSize < cpSize) {
            minKnot = crossBotKnot;
        } else {
            minKnot = cutPointKnot;
        }
        sizeMinKnot = minKnot.size();
        for (Knot k : cutEngine.flatKnots.values()) {
            shell.buff.add(k + " :  "
                    + (((k.contains(topKnotPoint) && k.contains(topPoint))
                            ^ (k.contains(botKnotPoint) && k.contains(botPoint)))
                            || (k.contains(botKnotPoint) && k.contains(topPoint) && !k.contains(botPoint)
                                    && !k.contains(topKnotPoint))
                            || (k.contains(topKnotPoint) && k.contains(botPoint) && !k.contains(topPoint)
                                    && !k.contains(botKnotPoint))
                            || (k.contains(topPoint) && k.contains(botPoint) && !k.contains(topKnotPoint)
                                    && !k.contains(botKnotPoint))
                            || (k.contains(topKnotPoint) && k.contains(botKnotPoint) && !k.contains(topPoint)
                                    && !k.contains(botPoint))));

            int size = k.size();
            if (size > sizeMinKnot && (((k.contains(topKnotPoint) && k.contains(topPoint))
                    ^ (k.contains(botKnotPoint) && k.contains(botPoint)))
                    || (k.contains(botKnotPoint) && k.contains(topPoint) && !k.contains(botPoint)
                            && !k.contains(topKnotPoint))
                    || (k.contains(topKnotPoint) && k.contains(botPoint) && !k.contains(topPoint)
                            && !k.contains(botKnotPoint))
                    || (k.contains(topPoint) && k.contains(botPoint) && !k.contains(topKnotPoint)
                            && !k.contains(botKnotPoint))
                    || (k.contains(topKnotPoint) && k.contains(botKnotPoint) && !k.contains(topPoint)
                            && !k.contains(botPoint)))) {
                minKnot = k;
                sizeMinKnot = size;
            }
        }

        VirtualPoint innerPoint = topPoint;
        VirtualPoint outerPoint = null;
        if (!minKnot.contains(topPoint)) {
            outerPoint = topPoint;
        } else {
            innerPoint = topPoint;
        }
        if (!minKnot.contains(botPoint)) {
            outerPoint = botPoint;
        } else {
            innerPoint = botPoint;
        }
        if (!minKnot.contains(topKnotPoint)) {
            outerPoint = topKnotPoint;
        } else {
            innerPoint = topKnotPoint;
        }
        if (!minKnot.contains(botKnotPoint)) {
            outerPoint = botKnotPoint;
        } else {
            innerPoint = botKnotPoint;
        }

        Segment upperCutSegment = topKnotPoint.getClosestSegment(topPoint, null);
        if(minKnot.hasPoint(topPoint.id) && minKnot.hasPoint(topKnotPoint.id)){
            upperCutSegment = botKnotPoint.getClosestSegment(botPoint, null);
        }
        if (outerPoint == null || innerPoint == null) {
        } else {
            minKnot = findExpandedKnot(knot, minKnot, innerPoint, outerPoint, upperCutSegment, sbe);
        }
        for(VirtualPoint vp : minKnot.knotPointsFlattened){
            if(!knot.contains(vp)){
                float z = 0;
            }
        }
        return minKnot;
    }
}