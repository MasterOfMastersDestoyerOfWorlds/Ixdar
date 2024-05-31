package shell;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.collections4.map.MultiKeyMap;

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
            Knot knot) throws SegmentBalanceException {

        shell.buff.add("recutting knot: " + knot);
        shell.buff.add(
                "knotPoint1: " + knotPoint1 + " external1: " + external1);
        shell.buff.add(
                "knotPoint2: " + knotPoint2 + " external2: " + external2);
        shell.buff.add(
                "cutPointA: " + cutPointA + " cutPointB: " + cutPointB);
        shell.buff.add(
                "flatKnots: " + cutEngine.flatKnots);

        // shell.buff.add(cutPointA.id);
        if (external1.contains(knotPoint1)) {
            float zero = 1 / 0;
        }
        if (external2.contains(knotPoint2)) {
            float zero = 1 / 0;
        }
        if (knotPoint1.equals(cutPointA) || knotPoint2.equals(cutPointA) || knotPoint1.equals(cutPointB)
                || knotPoint2.equals(cutPointB) || cutPointB.equals(cutPointA) || knotPoint1.equals(knotPoint2)) {
            float zero = 1 / 0;
        }
        int smallestKnotIdA = shell.smallestKnotLookup[cutPointA.id];
        int smallestKnotIdB = shell.smallestKnotLookup[cutPointB.id];

        Knot topKnot = cutEngine.flatKnots.get(smallestKnotIdA);
        VirtualPoint topPoint = cutPointA;
        VirtualPoint topKnotPoint = knotPoint1;

        Knot botKnot = cutEngine.flatKnots.get(smallestKnotIdB);
        VirtualPoint botPoint = cutPointB;
        VirtualPoint botKnotPoint = knotPoint2;

        if (topKnot.knotPointsFlattened.size() < botKnot.knotPointsFlattened.size()) {
            topPoint = cutPointB;
            topKnotPoint = knotPoint2;
            botPoint = cutPointA;
            botKnotPoint = knotPoint1;
        }

        shell.buff.add("topKnot: " + topKnot);
        shell.buff.add("botKnot: " + botKnot);
        shell.buff.add("topPoint: " + topPoint);
        shell.buff.add("botPoint: " + botPoint);
        
        Knot minKnot = findMinKnot(topKnotPoint, topPoint, botKnotPoint, botPoint, knot);

        if (topKnot.equals(knot)) {
            shell.buff.add("fully connected");
            Segment connector = cutPointA.getClosestSegment(cutPointB, null);
            CutMatchList cutMatchList = new CutMatchList(shell);
            cutMatchList.addSimpleMatch(connector, knot);
            return cutMatchList;
        }
        // if both orphans are on the top level, then we can simply match across not
        // TRUE : (

        shell.buff.add("in structure");
        // if neither orphan is on the top level, find their minimal knot in common and
        // recut it with the external that matched to the knot and its still matched
        // neighbor
        
        CutMatchList reCut;
        if (topPoint.equals(cutPointA)) {
            shell.buff.add("A");
            reCut = recutWithInternalNeighbor(knotPoint1, cutPointA, external1, knotPoint2, cutPointB, external2, minKnot,
                    knot);
        } else {
            shell.buff.add("B");
            if (topPoint.id == 15 && botPoint.id == 13 && (knotPoint1.id == 11 || knotPoint2.id == 11)) {
                // float z = 1 / 0;
            }
            reCut = recutWithInternalNeighbor(knotPoint2, cutPointB, external2, knotPoint1, cutPointA, external1, minKnot,
                    knot);

        }
        return reCut;

    }

    public CutMatchList recutWithInternalNeighbor(VirtualPoint topKnotPoint,
            VirtualPoint topPoint, VirtualPoint topExternal, VirtualPoint botKnotPoint, VirtualPoint botPoint,
            VirtualPoint botExternal, Knot minKnot,
            Knot knot) throws SegmentBalanceException {

        Segment kpSegment = knot.getSegment(topKnotPoint, botKnotPoint);

        // what we want is the minimum knot that contains one cut segment and not the
        // other
        // if there is no such segment can we simple connect?
        VirtualPoint kp = botKnotPoint;
        VirtualPoint kp2 = topKnotPoint;
        VirtualPoint vp = botPoint;
        VirtualPoint vp2 = topPoint;
        VirtualPoint ex = botExternal;
        if (!minKnot.contains(botKnotPoint) || !minKnot.contains(botPoint)) {
            kp = topKnotPoint;
            kp2 = botKnotPoint;
            vp = topPoint;
            vp2 = botPoint;
            ex = topExternal;

        }
        shell.buff.add("MINKNOT:::::::::::::::::::: " + minKnot);

        //TODO: If the cutPoint is the neighbor, we need to find two matches for the neighbor instead of doing the simple match, the below code should not run/should be deleted
        
        if (minKnot.contains(kp) && minKnot.contains(kp2)) {
            Segment connector = topPoint.getClosestSegment(botPoint, null);
            CutMatchList cutMatchList = new CutMatchList(shell);
            cutMatchList.addSimpleMatch(connector, knot);
            return cutMatchList;
        }

        // this should actually be more like if minknot doesn't contain vp2 and neighbor
        // doesn't then go up a level
        Segment upperCutSegment = null;

        if (!minKnot.contains(botKnotPoint)) {
            upperCutSegment = knot.getSegment(botKnotPoint, botPoint);
        } else {
            upperCutSegment = knot.getSegment(topKnotPoint, topPoint);
        }

        Segment cut = minKnot.getSegment(vp, kp);

        if (upperCutSegment.equals(cut)) {
            shell.buff.add("upper cut equals lower cut");
            shell.buff.add(upperCutSegment);
            shell.buff.add(cut);
            float z = 1 / 0;
        }

        boolean outsideUpperCutPoint = !minKnot.contains(vp2);

        VirtualPoint n1 = null;
        VirtualPoint n2 = null;
        ArrayList<Segment> neighborSegments = new ArrayList<Segment>();
        ArrayList<VirtualPoint> potentialNeighbors = new ArrayList<VirtualPoint>();
        ArrayList<VirtualPoint> innerPotentialNeighbors = new ArrayList<VirtualPoint>();
        MultiKeyMap<Integer, Segment> neighborSegmentLookup = new MultiKeyMap<>();
        HashMap<Integer, Segment> singleNeighborSegmentLookup = new HashMap<>();
        int startIdx = knot.knotPoints.indexOf(minKnot.knotPoints.get(0));
        int endIdx = startIdx - 1 < 0 ? knot.knotPoints.size() - 1 : startIdx - 1;
        VirtualPoint firstInnerNeighbor = null;
        Segment firstInnerNeighborSegment = null;
        ArrayList<Segment> innerNeighborSegments2 = new ArrayList<>();
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
                firstInnerNeighborSegment = neighborSegment;
            }
            if (minKnot.contains(k2) && !minKnot.contains(k1)) {
                Segment neighborSegment = knot.getSegment(k1, k2);
                neighborSegments.add(neighborSegment);
                if (minKnot.hasSegment(knot.getSegment(firstInnerNeighbor, k2))) {
                    int first = firstInnerNeighbor.id < k2.id ? firstInnerNeighbor.id : k2.id;
                    int last = firstInnerNeighbor.id < k2.id ? k2.id : firstInnerNeighbor.id;
                    neighborSegmentLookup.put(first, last, neighborSegment);
                } else {
                    singleNeighborSegmentLookup.put(k2.id, neighborSegment);
                    singleNeighborSegmentLookup.put(firstInnerNeighbor.id, firstInnerNeighborSegment);
                }
                potentialNeighbors.add(k1);
                innerPotentialNeighbors.add(k2);
                innerNeighborSegments2.add(minKnot.getSegment(firstInnerNeighbor, k2));
            }
            if (k == endIdx) {
                break;
            }
            k = k + 1 >= knot.knotPoints.size() ? 0 : k + 1;
        }
        shell.buff.add("the splooge list : " + neighborSegmentLookup);
        shell.buff.add("the dreges list : " + singleNeighborSegmentLookup);
        // need to find internal segments here
        ArrayList<Segment> innerNeighborSegments = new ArrayList<>();
        for (int j = 0; j < minKnot.knotPointsFlattened.size(); j++) {
            VirtualPoint k3 = minKnot.knotPoints.get(j - 1 < 0 ? minKnot.knotPoints.size() - 1 : j - 1);
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

                shell.buff.add("Checking Segment: " + candidate);
                int idx = knot.knotPoints.indexOf(k1);
                int idx2 = knot.knotPoints.indexOf(k2);
                VirtualPoint endPoint = k2;
                VirtualPoint nextPoint = knot.getNext(idx);
                VirtualPoint prevPoint = knot.getPrev(idx);
                VirtualPoint edgePoint = k1;
                if (minKnot.contains(prevPoint) && minKnot.contains(nextPoint)) {
                    shell.buff.add("Switching edge point");
                    int tmp = idx;
                    idx = idx2;
                    idx2 = tmp;
                    endPoint = k1;
                    edgePoint = k2;
                }

                shell.buff.add("edge point: " + edgePoint);
                int first = endPoint.id < edgePoint.id ? endPoint.id : edgePoint.id;
                int last = endPoint.id < edgePoint.id ? edgePoint.id : endPoint.id;
                if (neighborSegmentLookup.containsKey(first, last)) {
                    Segment neighborSegment = neighborSegmentLookup.get(first, last);
                    shell.buff.add("segment leading out" + neighborSegment);
                    if (neighborSegment.contains(endPoint)) {
                        VirtualPoint tmp = endPoint;
                        endPoint = edgePoint;
                        edgePoint = tmp;
                    }
                    VirtualPoint neighborPoint = neighborSegment.getOther(edgePoint);
                    idx = knot.knotPoints.indexOf(edgePoint);
                    idx2 = knot.knotPoints.indexOf(neighborPoint);
                } else {
                    Segment neighborSegment = null;
                    if (singleNeighborSegmentLookup.containsKey(edgePoint.id)) {
                        neighborSegment = singleNeighborSegmentLookup.get(edgePoint.id);
                    } else if (singleNeighborSegmentLookup.containsKey(endPoint.id)) {
                        neighborSegment = singleNeighborSegmentLookup.get(endPoint.id);
                    }
                    shell.buff.add("segment leading out" + neighborSegment);
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

                shell.buff.add(idx);
                shell.buff.add(next);
                shell.buff.add(marchDirection);
                shell.buff.add("next: " + knot.knotPoints.get(next));
                if (minKnot.contains(knot.knotPoints.get(next))) {
                    marchDirection = -marchDirection;
                }

                shell.buff.add(knot);
                shell.buff.add(idx);
                shell.buff.add(idx2);
                shell.buff.add(marchDirection);
                VirtualPoint curr = knot.knotPoints.get(idx);
                if (!outsideUpperCutPoint) {
                    while (!curr.equals(endPoint)) {
                        curr = knot.knotPoints.get(idx);
                        next = idx + marchDirection;
                        if (marchDirection < 0 && next < 0) {
                            next = knot.knotPoints.size() - 1;
                        } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                            next = 0;
                        }
                        VirtualPoint nextp = knot.knotPoints.get(next);
                        shell.buff.add(curr + " " + nextp);
                        if (curr.equals(kp2)) {
                            intersect = false;
                        }
                        if (minKnot.contains(nextp)) {
                            break;
                        }
                        idx = next;
                    }
                }

                if (intersect) {
                    innerNeighborSegments.add(candidate);
                }
            }
        }
        shell.buff.add("*************" + innerNeighborSegments);
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
        shell.buff.add("minknot contains: " + minKnot.contains(vp2));
        shell.buff.add("neihgbor before: ");
        shell.buff.add("NeI ++++++++++++++++: " + neighbor);
        shell.buff.add(minKnot);
        shell.buff.add(innerPotentialNeighbors);
        shell.buff.add(potentialNeighbors);
        shell.buff.add(innerNeighborSegments);
        shell.buff.add(upperCutSegment);
        shell.buff.add(neighborSegments);
        if (!minKnot.contains(vp2)) {
            neighbor = vp2;
        } else {
            int idx = knot.knotPoints.indexOf(vp2);
            int idx2 = knot.knotPoints.indexOf(kp2);
            int marchDirection = idx2 - idx < 0 ? -1 : 1;
            if (idx == 0 && idx2 == knot.knotPoints.size() - 1) {
                marchDirection = -1;
            }
            if (idx2 == 0 && idx == knot.knotPoints.size() - 1) {
                marchDirection = 1;
            }
            shell.buff.add(knot);
            shell.buff.add(idx);
            shell.buff.add(idx2);
            shell.buff.add(marchDirection);
            int totalIter = 0;
            while (neighbor == null) {
                VirtualPoint k1 = knot.knotPoints.get(idx);
                int next = idx + marchDirection;
                if (marchDirection < 0 && next < 0) {
                    next = knot.knotPoints.size() - 1;
                } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                    next = 0;
                }
                VirtualPoint k2 = knot.knotPoints.get(next);
                shell.buff.add(k1 + " " + k2);
                if (potentialNeighbors.contains(k2)) {
                    neighbor = k2;
                }
                idx = next;
                totalIter++;
                if (totalIter > knot.knotPoints.size()) {
                    shell.buff.add(potentialNeighbors);
                    shell.buff.printLayer(0);
                    float z = 1 / 0;

                }
            }
        }

        if (innerNeighborSegments.size() % 2 == 0 && innerNeighborSegments.size() != 0 && !neighbor.equals(vp2)) {
            // float z = 1 / 0;
        }

        shell.buff.add("+++++++++++++++++++++bor: " + neighbor);

        Segment neighborCut = null;

        for (Segment s : neighborSegments) {
            if (s.contains(neighbor)) {
                neighborCut = s;
                break;
            }
        }

        shell.buff.add(cut);
        boolean containsFlag = false;
        for (Segment s : neighborSegments) {
            if (s.contains(neighbor)) {
                containsFlag = true;
            }
        }
        if (!containsFlag && !neighbor.equals(vp2)) {
            shell.buff.add("niehbgor not in neighborSegments: " + neighbor);
            shell.buff.add("REEEEEEEEEEEEEEEEEEEEEEEEEEE" + minKnot);
            shell.buff.add("REEEEEEEEEEEEEEEEEEEEEEEEEEE" + neighborSegments);
            // float ze = 1 / 0;
        }
        if (upperCutSegment.contains(neighbor) && upperCutSegment.contains(vp)) {
            shell.buff.add("rematching cut segment");
            float ze = 1 / 0;
        }

        // TODO: djbouti_8-26_finalCut_cut9-10and0-2
        // Problem, we are picking the wrong neighbor for the following reason, the
        // niebor should be in the
        // same knot as the upper cut point

        if (topPoint.id == 15 && botPoint.id == 13 && (botKnotPoint.id == 11 || topKnotPoint.id == 11)) {
            float z = 1 / 0;
        }
        if (neighborCut != null && neighborCut.equals(upperCutSegment)) {
            float z = 1 / 0;
        }
        CutMatchList reCut = null;
        if (!minKnot.hasSegment(cut)) {
            int idx = minKnot.knotPoints.indexOf(kp);
            VirtualPoint rightPoint = minKnot.knotPoints.get(idx + 1 > minKnot.knotPoints.size() - 1 ? 0 : idx + 1);
            Segment rightCut = minKnot.getSegment(kp, rightPoint);
            VirtualPoint leftPoint = minKnot.knotPoints.get(idx - 1 < 0 ? minKnot.knotPoints.size() - 1 : idx - 1);
            Segment leftCut = minKnot.getSegment(kp, leftPoint);

            shell.buff.add(leftCut);
            shell.buff.add(rightCut);

            ArrayList<Segment> rightInnerNeighborSegments = new ArrayList<>();
            if (outsideUpperCutPoint) {
                for (Segment s : innerNeighborSegments) {
                    if (!s.contains(rightPoint) && (singleNeighborSegmentLookup.containsKey(rightPoint.id)
                            || singleNeighborSegmentLookup.containsKey(s.getOther(rightPoint).id))) {
                        rightInnerNeighborSegments.add(s);
                    }
                }

            } else {
                rightInnerNeighborSegments = innerNeighborSegments;
            }

            ArrayList<Segment> leftInnerNeighborSegments = new ArrayList<>();
            if (outsideUpperCutPoint) {
                for (Segment s : innerNeighborSegments) {
                    if (!s.contains(leftPoint) && (singleNeighborSegmentLookup.containsKey(leftPoint.id)
                            || singleNeighborSegmentLookup.containsKey(s.getOther(leftPoint).id))) {
                        leftInnerNeighborSegments.add(s);
                    }
                }

            } else {
                leftInnerNeighborSegments = innerNeighborSegments;
            }
            shell.buff.add(outsideUpperCutPoint);
            shell.buff.add("cutting left");
            boolean canCutLeft = !leftInnerNeighborSegments.contains(leftCut);
            CutMatchList leftCutMatch = null;
            if (canCutLeft) {
                leftCutMatch = cutEngine.findCutMatchListFixedCut(minKnot, ex, neighbor, leftCut, kp, leftPoint, knot,
                        kpSegment,
                        leftInnerNeighborSegments, neighborSegments, upperCutSegment, neighborCut, vp2);

                leftCutMatch.removeCut(cut);
            }
            shell.buff.add("cutting right");

            boolean canCutRight = !rightInnerNeighborSegments.contains(rightCut);
            CutMatchList rightCutMatch = null;
            if (canCutRight) {
                rightCutMatch = cutEngine.findCutMatchListFixedCut(minKnot, ex, neighbor, rightCut, kp, rightPoint,
                        knot,
                        kpSegment,
                        rightInnerNeighborSegments, neighborSegments, upperCutSegment, neighborCut, vp2);
                rightCutMatch.removeCut(cut);
            }

            if (!canCutLeft && !canCutRight) {
                System.out.println("Can't make either cut");
                float z = 1 / 0;
            }
            if ((!canCutLeft || rightCutMatch.delta < leftCutMatch.delta) && canCutRight) {
                reCut = rightCutMatch;
            } else {
                reCut = leftCutMatch;
            }
            shell.buff.add("cut Left: " + leftCut + "cut Right: " + rightCut);
            shell.buff.add(
                    "chose right? : " + ((!canCutLeft || rightCutMatch.delta < leftCutMatch.delta) && canCutRight));
            shell.buff.add("neighbor : " + neighbor);

            shell.buff.add("LEFTCUT : " + minKnot + " " + " " + ex + " " + " " + neighbor + " " + " " + leftCut
                    + " " + " " + kp + " " + " " + leftPoint + " " + " " + knot + " " + " " + kpSegment + " "
                    + leftInnerNeighborSegments + " " + neighborSegments + " " + upperCutSegment + " " + neighborCut);

            shell.buff.add("RightCUT : " + minKnot + " " + " " + ex + " " + " " + neighbor + " " + " " + rightCut
                    + " " + " " + kp + " " + " " + rightPoint + " " + " " + knot + " " + " " + kpSegment + " "
                    + rightInnerNeighborSegments + " " + neighborSegments + " " + upperCutSegment + " " + neighborCut);

        } else {
            ArrayList<Segment> removeList = new ArrayList<>();
            if (outsideUpperCutPoint) {
                for (Segment s : innerNeighborSegments) {
                    if (s.contains(vp) && (singleNeighborSegmentLookup.containsKey(vp.id)
                            || singleNeighborSegmentLookup.containsKey(s.getOther(vp).id))) {
                        removeList.add(s);
                    }
                }
                innerNeighborSegments.removeAll(removeList);

            }
            shell.buff.add(vp.id);
            shell.buff.add(innerNeighborSegments);
            shell.buff.add(singleNeighborSegmentLookup);
            shell.buff.add(minKnot + " " + " " + ex + " " + " " + neighbor + " " + " " + cut + " " + " " + kp
                    + " " + " " + vp + " " + " " + knot + " " + " " + kpSegment + " " + innerNeighborSegments + " "
                    + neighborSegments + " " + upperCutSegment + " " + neighborCut);
            reCut = cutEngine.findCutMatchListFixedCut(minKnot, ex, neighbor, cut, kp, vp, knot, kpSegment,
                    innerNeighborSegments, neighborSegments, upperCutSegment, neighborCut, vp2);

        }

        if (reCut.delta == 0.0) {
            Segment connector = topPoint.getClosestSegment(botPoint, null);
            CutMatchList cutMatchList = new CutMatchList(shell);
            cutMatchList.addSimpleMatch(connector, knot);
            reCut = cutMatchList;
        }
        boolean breakFlag = false;
        int cp1 = 14;
        int kp1 = 0;
        int cp2 = 1;
        int kpp2 = 11;
        if (breakFlag && topPoint.id == cp1 && topKnotPoint.id == kp1 && botPoint.id == cp2
                && botKnotPoint.id == kpp2) {
            float ze = 1 / 0;
        }
        if (breakFlag && topPoint.id == cp2 && topKnotPoint.id == kpp2 && botPoint.id == cp1
                && botKnotPoint.id == kp1) {
            float ze = 1 / 0;
        }
        shell.buff.add("MINKNOT:::::::::::::::::::: " + minKnot);
        return reCut;
    }

    public Knot findMinKnot(VirtualPoint knotPoint1, VirtualPoint cutPointA, VirtualPoint external1,
            VirtualPoint knotPoint2, VirtualPoint cutPointB, VirtualPoint external2,
            Knot knot) {

        int smallestKnotIdA = shell.smallestKnotLookup[cutPointA.id];
        int smallestKnotIdB = shell.smallestKnotLookup[cutPointB.id];

        Knot topKnot = cutEngine.flatKnots.get(smallestKnotIdA);
        VirtualPoint topPoint = cutPointA;
        VirtualPoint topKnotPoint = knotPoint1;

        Knot botKnot = cutEngine.flatKnots.get(smallestKnotIdB);
        VirtualPoint botPoint = cutPointB;
        VirtualPoint botKnotPoint = knotPoint2;

        if (topKnot.knotPointsFlattened.size() < botKnot.knotPointsFlattened.size()) {
            topPoint = cutPointB;
            topKnotPoint = knotPoint2;
            botPoint = cutPointA;
            botKnotPoint = knotPoint1;
        }
        Knot minKnot = findMinKnot(topKnotPoint, topPoint, botKnotPoint, botPoint, knot);
        return minKnot;

    }

    public Knot findMinKnot(VirtualPoint topKnotPoint, VirtualPoint topPoint, VirtualPoint botKnotPoint,
            VirtualPoint botPoint, Knot knot) {

        int matchKnotAId = shell.smallestCommonKnotLookup[topPoint.id][topKnotPoint.id];
        Knot matchKnotA = cutEngine.flatKnots.get(matchKnotAId);

        int matchKnotBId = shell.smallestCommonKnotLookup[botPoint.id][botKnotPoint.id];
        Knot matchKnotB = cutEngine.flatKnots.get(matchKnotBId);

        int botKnotId = shell.smallestKnotLookup[botPoint.id];
        Knot botKnot = cutEngine.flatKnots.get(botKnotId);

        Segment topCut = knot.getSegment(topKnotPoint, topPoint);
        Segment botCut = knot.getSegment(botPoint, botKnotPoint);
        int sizeMinKnot;
        Knot minKnot;
        if (!matchKnotA.contains(botKnotPoint) || !matchKnotA.contains(botPoint)) {
            minKnot = matchKnotA;
        } else if (!matchKnotB.contains(topKnotPoint) || !matchKnotB.contains(topPoint)) {
            minKnot = matchKnotB;
        } else {
            minKnot = botKnot;
        }
        sizeMinKnot = minKnot.knotPointsFlattened.size();
        for (Knot k : cutEngine.flatKnots.values()) {
            int size = k.knotPointsFlattened.size();
            if (size > sizeMinKnot && k.contains(botPoint)
                    && ((!k.contains(topKnotPoint) && k.contains(botKnotPoint))
                            || (k.contains(topKnotPoint) && !k.contains(botKnotPoint)))

                    && !(k.contains(topKnotPoint) && k.contains(topPoint) && !k.hasSegment(topCut))
                    && !(k.contains(botKnotPoint) && k.contains(botPoint) && !k.hasSegment(botCut))) {
                minKnot = k;
                sizeMinKnot = size;
            }
        }
        return minKnot;
    }
}