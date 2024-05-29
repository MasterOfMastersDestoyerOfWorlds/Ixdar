package shell;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.collections4.map.MultiKeyMap;

public class CutEngine {

    	// TODO: Need to overhaul cut knots here is the idea:
	// we get the two external points and loop through a double nested for loop
	// across the knot's segments to cut
	// store the info for each cut segment in a list or just store the min length
	// change, with some minimum set of variables and whether we need to
	// join across or not
	// if the inner segment ""xor'ed"" with the outer segment is partially
	// overlapping,
	// then we do not evaluate it, if it is fully overlapping or not overlapping
	// then evaluate
	// should be roughly N^3 operation N^2 to cut a Knot Times M knots M ~= N/3
	// worst case M = N-3

	public HashMap<Integer, Knot> flatKnots = new HashMap<>();
	int cutKnotNum = 0;

    Shell shell;
    InternalPathEngine internalPathEngine;

    public CutEngine(Shell shell) {
        this.shell = shell;
        this.internalPathEngine = new InternalPathEngine(shell, this);
    }

    public CutMatchList findCutMatchList(Knot knot, VirtualPoint external1, VirtualPoint external2, Knot superKnot,
            Segment kpSegment) throws SegmentBalanceException {
        double minDelta = Double.MAX_VALUE;
        boolean overlapping = true;
        Segment matchSegment1Final = null;
        Segment matchSegment2Final = null;
        Segment cutSegmentFinal = null;
        Segment cutSegment2Final = null;
        VirtualPoint knotPoint1Final = null;
        VirtualPoint knotPoint2Final = null;
        CutMatchList internalCuts = null;
        String segmentName = "";
        for (int a = 0; a < knot.knotPoints.size(); a++) {
            for (int b = 0; b < knot.knotPoints.size(); b++) {
                VirtualPoint knotPoint11 = knot.knotPoints.get(a);
                VirtualPoint knotPoint12 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
                Segment cutSegment1 = knot.getSegment(knotPoint11, knotPoint12);

                VirtualPoint knotPoint21 = knot.knotPoints.get(b);
                VirtualPoint knotPoint22 = knot.knotPoints.get(b + 1 >= knot.knotPoints.size() ? 0 : b + 1);
                Segment cutSegment2 = knot.getSegment(knotPoint21, knotPoint22);
                if (cutSegment1.partialOverlaps(cutSegment2)) {
                    continue;
                }
                if (cutSegment1.equals(cutSegment2)) {

                    Segment s11 = knotPoint11.getClosestSegment(external1, null);
                    Segment s12 = knotPoint12.getClosestSegment(external2, s11);
                    double d1 = s11.distance + s12.distance - cutSegment1.distance;

                    Segment s21 = knotPoint12.getClosestSegment(external1, null);
                    Segment s22 = knotPoint11.getClosestSegment(external2, s21);
                    double d2 = s21.distance + s22.distance - cutSegment1.distance;

                    double delta = d2;
                    if (d1 < d2) {
                        delta = d1;
                    }
                    if (delta < minDelta) {
                        if (d1 < d2) {
                            matchSegment1Final = s11;
                            matchSegment2Final = s12;
                            knotPoint1Final = knotPoint11;
                            knotPoint2Final = knotPoint12;
                        } else {
                            matchSegment1Final = s21;
                            matchSegment2Final = s22;
                            knotPoint1Final = knotPoint12;
                            knotPoint2Final = knotPoint11;
                        }
                        minDelta = delta;
                        overlapping = true;
                        cutSegmentFinal = cutSegment1;
                    }
                } else {
                    double delta = Double.MAX_VALUE;

                    Segment s11 = knotPoint11.getClosestSegment(external1, null);
                    Segment s12 = knotPoint21.getClosestSegment(external2, s11);
                    shell.buff.add("12 -------------------------------------------");
                    CutMatchList internalCuts12 = internalPathEngine.calculateInternalPathLength(
                            knotPoint11, knotPoint12, external1,
                            knotPoint21, knotPoint22, external2, knot);
                    double d1 = s11.distance + s12.distance + internalCuts12.delta - cutSegment1.distance
                            - cutSegment2.distance;
                    delta = d1 < delta ? d1 : delta;
                    if (!internalCuts12.checkCutMatchBalance(s11, s12, cutSegment1, cutSegment2, external1,
                            external2, knot, new ArrayList<>(), knot, false)) {
                        shell.buff.add(knot);
                        shell.buff.add("Cut Info: Cut1: knotPoint1: " + knotPoint11 + " cutpointA: " + knotPoint12
                                + " ex1:" + external1 + " knotPoint2: " + knotPoint21 + " cutPointB: " + knotPoint22
                                + " ex2: " + external2);
                        shell.buff.add("%complete this knot: " + 100.0 * (((double) a) * ((double) a - 1) + b)
                                / (((double) knot.knotPoints.size()) * ((double) knot.knotPoints.size())));
                        shell.buff.add(shell.knotName + "_cut" + knotPoint11 + "-" + knotPoint12 + "and" + knotPoint21
                                + "-" + knotPoint22);
                        throw new SegmentBalanceException(internalCuts12, knot,
                                knot.getSegment(knotPoint12, knotPoint11), s11,
                                knot.getSegment(knotPoint21, knotPoint22), s12);
                    } else {
                        shell.buff.flush();
                    }

                    Segment s21 = knotPoint21.getClosestSegment(external1, null);
                    Segment s22 = knotPoint11.getClosestSegment(external2, s21);
                    double d2 = s21.distance + s22.distance + internalCuts12.delta - cutSegment1.distance
                            - cutSegment2.distance;
                    delta = d2 < delta ? d2 : delta;

                    shell.buff.add("34 -------------------------------------------");
                    Segment s31 = knotPoint12.getClosestSegment(external1, null);
                    Segment s32 = knotPoint22.getClosestSegment(external2, s31);
                    CutMatchList internalCuts34 = internalPathEngine.calculateInternalPathLength(
                            knotPoint12, knotPoint11, external1,
                            knotPoint22, knotPoint21, external2, knot);
                    double d3 = s31.distance + s32.distance + internalCuts34.delta - cutSegment1.distance
                            - cutSegment2.distance;
                    delta = d3 < delta ? d3 : delta;
                    if (!internalCuts34.checkCutMatchBalance(s31, s32, cutSegment1, cutSegment2, external1,
                            external2, knot, new ArrayList<>(), knot, false)) {
                        shell.buff.add(knot);
                        shell.buff.add("Cut Info: Cut1: knotPoint1: " + knotPoint12 + " cutpointA: " + knotPoint11
                                + " ex1:" + external1 + " knotPoint2: " + knotPoint22 + " cutPointB: " + knotPoint21
                                + " ex2: " + external2);
                        shell.buff.add("%complete this knot: " + 100.0 * (((double) a) * ((double) a - 1) + b)
                                / (((double) knot.knotPoints.size()) * ((double) knot.knotPoints.size())));
                        shell.buff.add(shell.knotName + "_cut" + knotPoint12 + "-" + knotPoint11 + "and" + knotPoint22
                                + "-" + knotPoint21);
                        throw new SegmentBalanceException(internalCuts34, knot,
                                knot.getSegment(knotPoint12, knotPoint11), s31,
                                knot.getSegment(knotPoint21, knotPoint22), s32);
                    } else {
                        shell.buff.flush();
                    }

                    shell.buff.add(" 56 -------------------------------------------");
                    // shell.buff.add(
                    // "s31: " + s31 + " s32: " + s32 + " cut1: " + cutSegment1 + " cut2: " +
                    // cutSegment2);
                    // shell.buff.add("d3: " + d3);
                    // if (knotPoint12.id == 11 && knotPoint22.id == 14 && knotPoint11.id == 12 &&
                    // knotPoint21.id == 0) {
                    // shell.buff.add(
                    // internalCuts3.cutMatches);
                    // // float z = 1 / 0;
                    // }

                    Segment s41 = knotPoint22.getClosestSegment(external1, null);
                    Segment s42 = knotPoint12.getClosestSegment(external2, s41);

                    double d4 = s41.distance + s42.distance + internalCuts34.delta - cutSegment1.distance
                            - cutSegment2.distance;
                    delta = d4 < delta ? d4 : delta;

                    // TODO: We aren't considering when this is the best cut and we'd need to
                    // recisviely cut down from the orphaned points

                    Segment s51 = knotPoint11.getClosestSegment(external1, null);
                    Segment s52 = knotPoint22.getClosestSegment(external2, s51);
                    Segment s53 = knotPoint12.getClosestSegment(knotPoint21, null);
                    double d5 = s51.distance + s52.distance;

                    Segment s61 = knotPoint12.getClosestSegment(external1, null);
                    Segment s62 = knotPoint21.getClosestSegment(external2, s61);
                    Segment s63 = knotPoint11.getClosestSegment(knotPoint22, null);
                    double d6 = s61.distance + s62.distance;

                    if (delta < minDelta) {
                        if (delta == d1) {
                            matchSegment1Final = s11;
                            matchSegment2Final = s12;
                            knotPoint1Final = knotPoint11;
                            knotPoint2Final = knotPoint21;
                            internalCuts = internalCuts12;
                            cutSegmentFinal = cutSegment1;
                            cutSegment2Final = cutSegment2;
                            segmentName = "d1";
                        } else if (delta == d2) {
                            matchSegment1Final = s21;
                            matchSegment2Final = s22;
                            knotPoint1Final = knotPoint21;
                            knotPoint2Final = knotPoint11;
                            internalCuts = internalCuts12;
                            cutSegmentFinal = cutSegment2;
                            cutSegment2Final = cutSegment1;
                            segmentName = "d2";
                        } else if (delta == d3) {
                            matchSegment1Final = s31;
                            matchSegment2Final = s32;
                            knotPoint1Final = knotPoint12;
                            knotPoint2Final = knotPoint22;
                            internalCuts = internalCuts34;
                            cutSegmentFinal = cutSegment1;
                            cutSegment2Final = cutSegment2;
                            segmentName = "d3";
                        } else {
                            matchSegment1Final = s41;
                            matchSegment2Final = s42;
                            knotPoint1Final = knotPoint22;
                            knotPoint2Final = knotPoint12;
                            internalCuts = internalCuts34;
                            cutSegmentFinal = cutSegment2;
                            cutSegment2Final = cutSegment1;
                            segmentName = "d4";
                        }
                        if (internalCuts == null) {
                            float zero = 1 / 0;
                        }
                        /*
                         * else if (delta == d5) {
                         * matchSegment1Final = s51;
                         * matchSegment2Final = s52;
                         * attachSegmentFinal = s53;
                         * knotPoint1Final = knotPoint11;
                         * knotPoint2Final = knotPoint22;
                         * attachPoint1Final = knotPoint12;
                         * attachPoint2Final = knotPoint21;
                         * cutSegmentFinal = cutSegment1;
                         * cutSegment2Final = cutSegment2;
                         * segmentName = "d5";
                         * } else {
                         * matchSegment1Final = s61;
                         * matchSegment2Final = s62;
                         * attachSegmentFinal = s63;
                         * knotPoint1Final = knotPoint22;
                         * knotPoint2Final = knotPoint11;
                         * attachPoint1Final = knotPoint21;
                         * attachPoint2Final = knotPoint12;
                         * cutSegmentFinal = cutSegment2;
                         * cutSegment2Final = cutSegment1;
                         * segmentName = "d6";
                         * }
                         */
                        minDelta = delta;
                        overlapping = false;
                    }

                }
            }
        }
        if (overlapping) {
            CutMatchList result = new CutMatchList(shell);
            if (superKnot != null) {
                result.addCut(cutSegmentFinal, matchSegment1Final, matchSegment2Final, knot, knotPoint1Final,
                        knotPoint2Final, superKnot, kpSegment, new ArrayList<>(), new ArrayList<>(), null, null, null,
                        true);
            } else {
                result.addCut(cutSegmentFinal, matchSegment1Final, matchSegment2Final, knot, knotPoint1Final,
                        knotPoint2Final);
            }
            return result;
        } else {
            CutMatchList result = new CutMatchList(shell);
            if (superKnot != null) {
                result.addTwoCut(cutSegmentFinal, cutSegment2Final, matchSegment1Final, matchSegment2Final, knot,
                        knotPoint1Final, knotPoint2Final, internalCuts, superKnot, kpSegment, new ArrayList<>(),
                        new ArrayList<>(), null, null,
                        null, true);
            } else {
                result.addTwoCut(cutSegmentFinal, cutSegment2Final, matchSegment1Final, matchSegment2Final, knot,
                        knotPoint1Final, knotPoint2Final,
                        internalCuts);
            }

            return result;

        }

    }

    MultiKeyMap<Integer, CutMatchList> cutLookup = new MultiKeyMap<>();
    double resolved = 0;
    double totalCalls = 0;

    public CutMatchList findCutMatchListFixedCut(Knot knot, VirtualPoint external1,
            VirtualPoint external2, Segment cutSegment1, VirtualPoint kp1, VirtualPoint cp1, Knot superKnot,
            Segment kpSegment, ArrayList<Segment> innerNeighborSegments, ArrayList<Segment> neighborSegments,
            Segment upperCutSegment, Segment neighborCutSegment, VirtualPoint topCutPoint)
            throws SegmentBalanceException {

        totalCalls++;
        if (cutLookup.containsKey(knot.id, external2.id, kp1.id, cp1.id, superKnot.id)) {
            resolved++;
            // return cutLookup.get(knot.id, external2.id, kp1.id, cp1.id,
            // superKnot.id).copy();
        }

        if (!(knot.contains(cutSegment1.first) && knot.contains(cutSegment1.last))) {
            shell.buff.add(knot);
            shell.buff.add(cutSegment1);
            float z = 1 / 0;
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
            if (cutSegment1.partialOverlaps(cutSegment2)) {
                continue;
            }
            if (cutSegment1.equals(cutSegment2)) {

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

                boolean orphanFlag = wouldOrphan(cp1, kp1, cp2, kp2, knot.knotPointsFlattened);

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
                        replicatesNeighbor = true;
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
                shell.buff.add("b: " + cp1 + " " + cp2 + " " + cutPointsAcross);
                boolean hasSegment = replicatesNeighbor
                        || (innerNeighbor && outerNeighbor) || neighborIntersect || s12.equals(upperCutSegment);

                if (hasSegment) {
                    shell.buff.add("REEE" + " s12: " + s12 + " kp2 :" + kp2 + " kpSegment " + kpSegment);
                }

                CutMatchList internalCuts1 = null;
                double d1 = Double.MAX_VALUE;
                if (!orphanFlag && !hasSegment) {
                    shell.buff.currentDepth++;
                    internalCuts1 = internalPathEngine.calculateInternalPathLength(kp1, cp1, external1, kp2, cp2, external2, knot);
                    shell.buff.currentDepth--;
                    d1 = s12.distance + internalCuts1.delta - cutSegment2.distance;
                    delta = d1 < delta ? d1 : delta;
                }

                boolean orphanFlag2 = wouldOrphan(cp1, kp1, kp2, cp2, knot.knotPointsFlattened);

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
                        replicatesNeighbor2 = true;
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
                shell.buff.add("a: " + cp1 + " " + kp2 + " " + cutPointsAcross2);
                boolean hasSegment2 = replicatesNeighbor2
                        || (innerNeighbor2 && outerNeighbor2) || neighborIntersect2 || s22.equals(upperCutSegment);
                // false;//
                // superKnot.hasSegment(s22)
                // ||
                // kpSegment.contains(cp2);

                if (hasSegment2) {
                    shell.buff.add("REEE" + " s22: " + s22 + " cp2 :" + cp2 + " kpSegment " + kpSegment);
                }

                CutMatchList internalCuts2 = null;
                double d2 = Double.MAX_VALUE;
                if (!orphanFlag2 && !hasSegment2) {
                    shell.buff.currentDepth++;
                    internalCuts2 = internalPathEngine.calculateInternalPathLength(kp1, cp1, external1, cp2, kp2, external2, knot);
                    shell.buff.currentDepth--;
                    d2 = s22.distance + internalCuts2.delta - cutSegment2.distance;
                    delta = d2 < delta ? d2 : delta;

                }

                if (delta < minDelta) {
                    if (!orphanFlag && !hasSegment) {
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
            CutMatchList result = new CutMatchList(shell);
            shell.buff.add("Im gonna pre: " + neighborSegments);
            result.addCut(cutSegmentFinal, kp1.getClosestSegment(external1, null), matchSegment2Final, knot,
                    knotPoint1Final, knotPoint2Final, superKnot,
                    kpSegment, innerNeighborSegments, neighborSegments, neighborCutSegment, upperCutSegment,
                    topCutPoint, false);
            cutLookup.put(knot.id, external2.id, kp1.id, cp1.id, superKnot.id, result);
            return result;
        } else if (overlapping == 2) {
            CutMatchList result = new CutMatchList(shell);
            result.addTwoCut(cutSegmentFinal, cutSegment2Final, kp1.getClosestSegment(external1, null),
                    matchSegment2Final, knot, knotPoint1Final,
                    knotPoint2Final, internalCuts, superKnot, kpSegment, innerNeighborSegments, neighborSegments,
                    neighborCutSegment, upperCutSegment, topCutPoint, false);
            cutLookup.put(knot.id, external2.id, kp1.id, cp1.id, superKnot.id, result);
            return result;

        } else {
            shell.buff.add("No Available CUTS!");
            throw new SegmentBalanceException(new CutMatchList(shell), superKnot, cutSegment1,
                    new Segment(kp1, external1, 0.0), upperCutSegment,
                    new Segment(upperCutSegment.getOther(topCutPoint), upperCutSegment.getOther(topCutPoint), 0.0));
        }
    }

    private boolean wouldOrphan(VirtualPoint cutp1, VirtualPoint knotp1, VirtualPoint cutp2, VirtualPoint knotp2,
            ArrayList<VirtualPoint> knotList) {
        int cp1 = knotList.indexOf(cutp1);
        int kp1 = knotList.indexOf(knotp1);

        int cp2 = knotList.indexOf(cutp2);
        int kp2 = knotList.indexOf(knotp2);

        if ((cp1 > kp1 && cp1 < kp2 && cp2 > kp1 && cp2 < kp2)
                ||
                (cp1 > kp2 && cp1 < kp1 && cp2 > kp2 && cp2 < kp1)
                ||
                (kp1 > cp2 && kp1 < cp1 && kp2 > cp2 && kp2 < cp1)
                ||
                (kp1 > cp1 && kp1 < cp2 && kp2 > cp1 && kp2 < cp2)
                ||
                (kp1 > cp1 && kp1 > cp2 && kp2 > cp1 && kp2 > cp2)
                ||
                (kp1 < cp1 && kp1 < cp2 && kp2 < cp1 && kp2 < cp2)) {
            return true;
        }

        return false;
    }

    
	public ArrayList<VirtualPoint> cutKnot(ArrayList<VirtualPoint> knotList) throws SegmentBalanceException {
		knotList = new ArrayList<>(knotList);
		// move on to the cutting phase
		VirtualPoint prevPoint = knotList.get(knotList.size() - 1);
		for (int i = 0; i < knotList.size(); i++) {

			VirtualPoint vp = knotList.get(i);
			shell.buff.add("Checking Point: " + vp);
			if (vp.isKnot) {

				// Cases:
				// 1. cut segments are the same vps and opposite orientation
				// very cool, un tie the knot normally without length checks
				// 2. cut segments are the same vps and same orientation
				// figure out which external point is best to match to first
				// 3. cut segments have the same knot points but different cut points
				// look at knotPoint's matches and figure out which orientation is smallest
				// 4. cut segments have different knot points but the same cut point
				// look at both cuts and figure out which is smaller
				// 5.
				Knot knot = (Knot) vp;
				shell.buff.add("Found Knot!" + knot.fullString());

				VirtualPoint external1 = knot.match1;
				VirtualPoint external2 = knot.match2;

				if ((external1.getHeight() > 1 || knot.getHeight() > 1 || external2.getHeight() > 1)) {
					shell.buff.add("Need to simplify knots internally before matching : knot: " + knot
							+ " external1: " + external1 + " external2: " + external2);
					Knot knotNew = flattenKnots(knot, external1, external2, knotList);
					int prevIdx = knotList.indexOf(knotNew) - 1;
					if (prevIdx < 0) {
						prevIdx = knotList.size() - 1;
					}
					prevPoint = knotList.get(prevIdx);
					i = i - 1;
					continue;
				} else {
					shell.updateSmallestKnot(knot);
					shell.updateSmallestCommonKnot(knot);
					if (!flatKnots.containsKey(knot.id)) {
						flatKnots.put(knot.id, knot);
					}
				}

				CutMatchList cutMatchList = findCutMatchList(knot, external1, external2, null, null);
				external1.reset(knot);
				external2.reset(knot);

				shell.buff.add("===================================================");
				shell.buff.add(knot);
				shell.buff.add(knotList);
				shell.buff.add(cutMatchList);

				shell.buff.add("===================================================");
				ArrayList<CutMatch> cutMatches = cutMatchList.cutMatches;
				for (int j = 0; j < cutMatches.size(); j++) {
					CutMatch cutMatch = cutMatches.get(j);
					for (Segment cutSegment : cutMatch.cutSegments) {

						Point pcut1 = (Point) cutSegment.first;
						Point pcut2 = (Point) cutSegment.last;
						pcut1.reset(pcut2);
						pcut2.reset(pcut1);
					}
					for (Segment matchSegment : cutMatch.matchSegments) {

						Point pMatch1 = (Point) matchSegment.first;
						VirtualPoint match1 = pMatch1;
						if (external1.contains(pMatch1)) {
							match1 = external1;
						} else if (external2.contains(pMatch1)) {
							match1 = external2;
						}
						Point pMatch2 = (Point) matchSegment.last;
						VirtualPoint match2 = pMatch2;
						if (external1.contains(pMatch2)) {
							match2 = external1;
						} else if (external2.contains(pMatch2)) {
							match2 = external2;
						}
						if (!match1.hasMatch(match2, pMatch2, pMatch1, matchSegment)) {
							match1.setMatch2(match2, pMatch2, pMatch1, matchSegment);
						}
						if (!match2.hasMatch(match1, pMatch1, pMatch2, matchSegment)) {
							match2.setMatch2(match1, pMatch1, pMatch2, matchSegment);
						}

					}
				}
				knotList.remove(vp);
				CutMatch finalCut = cutMatchList.cutMatches.get(0);
				VirtualPoint addPoint = finalCut.kp2;
				if (finalCut.kp1.match1.equals(prevPoint) || finalCut.kp1.match2.equals(prevPoint)) {
					addPoint = finalCut.kp1;
				}
				VirtualPoint prevPointTemp = prevPoint;
				for (int j = 0; j < knot.knotPoints.size(); j++) {
					shell.buff.add("adding: " + addPoint.fullString());
					knotList.add(i + j, addPoint);
					if (prevPointTemp.equals(addPoint.match2)) {
						prevPointTemp = addPoint;
						addPoint = addPoint.match1;
					} else {
						prevPointTemp = addPoint;
						addPoint = addPoint.match2;
					}
				}
				shell.buff.add(flatKnots);
				shell.buff.add(knotList);

				cutKnotNum++;
				if (cutKnotNum > 20) {
					float z = 1 / 0;
				}
				i = i - 1;
			}
			if (!vp.isKnot) {
				prevPoint = vp;
			}
		}

		shell.buff.add(" " + resolved / totalCalls * 100 + " %");
		return knotList;
	}

    
	public Knot flattenKnots(Knot knot, VirtualPoint external1, VirtualPoint external2,
			ArrayList<VirtualPoint> knotList) throws SegmentBalanceException {

		ArrayList<VirtualPoint> flattenKnots = cutKnot(knot.knotPoints);
		Knot knotNew = new Knot(flattenKnots, shell);
		knotNew.copyMatches(knot);
		flatKnots.put(knotNew.id, knotNew);
		shell.updateSmallestCommonKnot(knotNew);
		shell.buff.add(flatKnots);

		boolean makeExternal1 = external1.isKnot;

		boolean same = external1.equals(external2);
		boolean makeExternal2 = external2.isKnot && !same;

		Knot external1Knot = null;
		ArrayList<VirtualPoint> flattenKnotsExternal1 = null;
		Knot external1New = null;
		if (makeExternal1) {

			external1Knot = (Knot) external1;
			flattenKnotsExternal1 = cutKnot(external1Knot.knotPoints);
			external1New = new Knot(flattenKnotsExternal1, shell);
			flatKnots.put(external1New.id, external1New);
			shell.updateSmallestCommonKnot(external1New);
			external1New.copyMatches(external1);
		}
		Knot external2Knot = null;
		ArrayList<VirtualPoint> flattenKnotsExternal2 = null;
		Knot external2New = null;
		if (makeExternal2) {

			external2Knot = (Knot) external2;
			flattenKnotsExternal2 = cutKnot(external2Knot.knotPoints);
			external2New = new Knot(flattenKnotsExternal2, shell);
			external2New.copyMatches(external2);
			shell.updateSmallestCommonKnot(external2New);
			flatKnots.put(external2New.id, external2New);
		}

		if (external1.contains(knot.match1endpoint)) {
			if (makeExternal1) {
				knotNew.match1 = external1New;
			} else if (!same || (!makeExternal1 && !makeExternal2)) {
				knotNew.match1 = external1;
			}
		}
		if (external1.contains(knot.match2endpoint)) {
			if (makeExternal1) {
				knotNew.match2 = external1New;
			} else if (!same || (!makeExternal1 && !makeExternal2)) {
				knotNew.match2 = external1;
			}
		}
		if (external2.contains(knot.match1endpoint)) {
			if (makeExternal2) {
				knotNew.match1 = external2New;
			} else if (!same || (!makeExternal1 && !makeExternal2)) {
				knotNew.match1 = external2;
			}
		}
		if (external2.contains(knot.match2endpoint)) {
			if (makeExternal2) {
				knotNew.match2 = external2New;
			} else if (!same || (!makeExternal1 && !makeExternal2)) {
				knotNew.match2 = external2;
			}
		}

		if (knotNew.contains(external1.match1endpoint)) {
			if (makeExternal1) {

				external1New.match1 = knotNew;
			} else {
				external1.match1 = knotNew;
			}
		}
		if (knotNew.contains(external1.match2endpoint)) {
			if (makeExternal1) {
				external1New.match2 = knotNew;
			} else {
				external1.match2 = knotNew;
			}
		}

		if (knotNew.contains(external2.match1endpoint)) {
			if (makeExternal2) {
				external2New.match1 = knotNew;
			} else {
				external2.match1 = knotNew;
			}
		}
		if (knotNew.contains(external2.match2endpoint)) {
			if (makeExternal2) {
				external2New.match2 = knotNew;
			} else {
				external2.match2 = knotNew;
			}
		}
		if (makeExternal1 && external1New.contains(external2.match1endpoint)) {
			if (makeExternal2) {
				external2New.match1 = external1New;
			} else {
				external2.match1 = external1New;
			}
		}
		if (makeExternal1 && external1New.contains(external2.match2endpoint)) {
			if (makeExternal2) {
				external2New.match2 = external1New;
			} else {
				external2.match2 = external1New;
			}
		}
		if (makeExternal2 && external2New.contains(external1.match1endpoint)) {
			if (makeExternal1) {
				external1New.match1 = external2New;
			} else {
				external1.match1 = external2New;
			}
		}
		if (makeExternal2 && external2New.contains(external1.match2endpoint)) {
			if (makeExternal1) {
				external1New.match2 = external2New;
			} else {
				external1.match2 = external2New;
			}
		}
		if (makeExternal1) {
			if (external1New.contains(external1New.match1.match1endpoint)) {
				external1New.match1.match1 = external1New;
			}

			if (external1New.contains(external1New.match1.match2endpoint)) {
				external1New.match1.match2 = external1New;
			}
			shell.buff.add(external1New.fullString());
			shell.buff.add(external2New != null ? external2New.fullString() : "null");
			shell.buff.add(knotNew.fullString());
			if (external1New.contains(external1New.match2.match1endpoint)) {
				external1New.match2.match1 = external1New;
			}

			if (external1New.contains(external1New.match2.match2endpoint)) {
				external1New.match2.match2 = external1New;
			}
		}
		if (makeExternal2) {
			if (external2New.contains(external2New.match1.match1endpoint)) {
				external2New.match1.match1 = external2New;
			}

			if (external2New.contains(external2New.match1.match2endpoint)) {
				external2New.match1.match2 = external2New;
			}
			if (external2New.contains(external2New.match2.match1endpoint)) {
				external2New.match2.match1 = external2New;
			}

			if (external2New.contains(external2New.match2.match2endpoint)) {
				external2New.match2.match2 = external2New;
			}
		}

		if (makeExternal1) {
			int idx = knotList.indexOf(external1);
			knotList.add(idx, external1New);
			knotList.remove(external1);
		}

		if (makeExternal2) {
			int idx = knotList.indexOf(external2);
			knotList.add(idx, external2New);
			knotList.remove(external2);
		}
		int idx2 = knotList.indexOf(knot);
		knotList.add(idx2, knotNew);
		knotList.remove(knot);

		shell.buff.add(external1New);
		shell.buff.add(external1);
		shell.buff.add(external2New);
		shell.buff.add(external2);
		shell.buff.add(knotNew);
		shell.buff.add(knotList);
		shell.buff.add(knotNew.fullString());
		return knotNew;
	}

}