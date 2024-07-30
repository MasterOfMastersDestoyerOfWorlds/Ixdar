package shell;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Random;

import javax.swing.JComponent;

/**
 * This class represents a list of some points in the point set. Initially each
 * shell is a convex hull, but they are eventually combined together to form the
 * optimal tsp path and they lose their convex property
 */

public class Shell extends LinkedList<PointND> {
	private static final long serialVersionUID = -5904334592585016845L;
	public static int failed = 0;
	private Shell child;
	ArrayList<VirtualPoint> visited;
	ArrayList<VirtualPoint> unvisited;
	public HashMap<Integer, VirtualPoint> pointMap = new HashMap<Integer, VirtualPoint>();
	public DistanceMatrix distanceMatrix;
	String knotName;

	public CutEngine cutEngine = new CutEngine(this);

	StringBuff buff = new StringBuff();

	int breakCount = 0;
	int runCount = 0;

	boolean skipHalfKnotFlag = true;

	public Shell (){
		pointMap = new HashMap<>();
		unvisited = new ArrayList<VirtualPoint>();
		visited = new ArrayList<VirtualPoint>();
	}

	public ArrayList<VirtualPoint> createKnots() {
		ArrayList<VirtualPoint> knots = new ArrayList<>();
		@SuppressWarnings("unchecked")
		ArrayList<VirtualPoint> toVisit = (ArrayList<VirtualPoint>) unvisited.clone();
		ArrayList<VirtualPoint> runList = new ArrayList<>();
		buff.add("runList: " + runList);
		VirtualPoint mainPoint = toVisit.get(0);
		buff.add("startPoint: " + mainPoint);
		boolean endpointReached = false;
		VirtualPoint endPoint1 = null;
		VirtualPoint endPoint2 = null;
		while (toVisit.size() > 0 || runList.size() > 0) {
			toVisit.remove(mainPoint);
			buff.add("Main Point is now:" + mainPoint);
			buff.add("runList:" + runList);
			buff.add("toVisit:" + toVisit);
			buff.add("knots:" + knots);
			Segment potentialSegment1 = mainPoint.getPointer(1);
			Point pointer1 = (Point) potentialSegment1.getOtherKnot(mainPoint.topGroup);
			buff.add("Point " + mainPoint + " points to: " + pointer1 + " (" + pointer1.topGroup + "):"
					+ potentialSegment1);

			Segment potentialSegment11 = pointer1.topGroup.getPointer(1);
			Point pointer11 = (Point) potentialSegment11.getOtherKnot(pointer1.topGroup);

			Segment potentialSegment12 = pointer1.topGroup.getPointer(2);
			Point pointer12 = (Point) potentialSegment12.getOtherKnot(pointer1.topGroup);
			buff.add("Point " + pointer1 + " points to: " + pointer11 + " (" + pointer11.topGroup + ") : "
					+ potentialSegment11 + " and "
					+ pointer12 + " (" + pointer12.topGroup + ") :" + potentialSegment12);

			Segment potentialSegment2 = mainPoint.getPointer(2);
			Point pointer2 = (Point) potentialSegment2.getOtherKnot(mainPoint.topGroup);
			buff.add("Point " + mainPoint + " points to: " + pointer2 + " (" + pointer2.topGroup + ") : "
					+ potentialSegment2);

			Segment potentialSegment21 = pointer2.topGroup.getPointer(1);
			Point pointer21 = (Point) potentialSegment21.getOtherKnot(pointer2.topGroup);

			Segment potentialSegment22 = pointer2.topGroup.getPointer(2);
			Point pointer22 = (Point) potentialSegment22.getOtherKnot(pointer2.topGroup);
			buff.add(
					"Point " + pointer2.topGroup + " points to: " + pointer21 + " (" + pointer21.topGroup + ") : "
							+ potentialSegment21 + " and "
							+ pointer22 + " (" + pointer22.topGroup + ") : " + potentialSegment22);

			VirtualPoint vp1 = pointer1.topGroup;
			VirtualPoint vp11 = pointer11.topGroup;
			VirtualPoint vp12 = pointer12.topGroup;
			VirtualPoint vp2 = pointer2.topGroup;
			VirtualPoint vp21 = pointer21.topGroup;
			VirtualPoint vp22 = pointer22.topGroup;
			Point matchEndPoint = null;
			Point matchBasePoint = null;
			VirtualPoint matchPoint = null;

			Segment matchSegment = null;
			boolean inKnots1 = knots.contains(vp1);
			boolean inKnots2 = knots.contains(vp2);
			// need to check that we haven't already match the end of the run in run case
			if (mainPoint.equals(vp11) && potentialSegment1.equals(potentialSegment11) && !inKnots1
					&& vp1.numMatches < 2
					&& (mainPoint.numMatches == 0 || !mainPoint.s1.contains(pointer1))) {
				matchPoint = vp1;
				matchEndPoint = pointer1;
				matchBasePoint = pointer11;
				matchSegment = potentialSegment11;
			} else if (mainPoint.equals(vp12) && potentialSegment1.equals(potentialSegment12) && !inKnots1
					&& vp1.numMatches < 2
					&& (mainPoint.numMatches == 0 || !mainPoint.s1.contains(pointer1))) {
				matchPoint = vp1;
				matchEndPoint = pointer1;
				matchBasePoint = pointer12;
				matchSegment = potentialSegment12;
			} else if ((mainPoint.equals(vp21)) && potentialSegment2.equals(potentialSegment21) && !inKnots2
					&& vp2.numMatches < 2
					&& (mainPoint.numMatches == 0 || !mainPoint.s1.contains(pointer2))) {
				matchPoint = vp2;
				matchEndPoint = pointer2;
				matchBasePoint = pointer21;
				matchSegment = potentialSegment21;
			} else if (mainPoint.equals(vp22) && potentialSegment2.equals(potentialSegment22) && !inKnots2
					&& vp2.numMatches < 2
					&& (mainPoint.numMatches == 0 || !mainPoint.s1.contains(pointer2))) {
				matchPoint = vp2;
				matchEndPoint = pointer2;
				matchBasePoint = pointer22;
				matchSegment = potentialSegment22;
			}
			if (matchPoint != null) {
				buff.add("Point " + matchPoint + " points back" + " | basepoint: " + matchBasePoint
						+ " endpoint: " + matchEndPoint);
				if (mainPoint.numMatches == 0) {
					mainPoint.setMatch1(matchPoint, matchEndPoint, matchBasePoint, matchSegment);
				} else {
					mainPoint.checkAndSwap2(matchPoint, matchEndPoint, matchBasePoint, matchSegment);
				}
				if (matchPoint.numMatches == 0) {
					matchPoint.setMatch1(mainPoint, matchBasePoint, matchEndPoint, matchSegment);
				} else {
					matchPoint.checkAndSwap2(mainPoint, matchBasePoint, matchEndPoint, matchSegment);
				}
				mainPoint.numMatches++;
				matchPoint.numMatches++;
				if (!runList.contains(mainPoint)) {
					if (endpointReached) {
						runList.add(0, mainPoint);
					} else {
						runList.add(mainPoint);
					}
				}
				if (endpointReached) {
					runList.add(0, matchPoint);
				} else {
					runList.add(matchPoint);
				}
				boolean mainIsFull = false;
				buff.add("Setting Match: " + mainPoint + " : " + matchPoint);
				buff.add(mainPoint + " has " + mainPoint.numMatches + " matches : " + matchPoint + " has "
						+ matchPoint.numMatches + " matches");
				if (mainPoint.numMatches == 2) {
					unvisited.remove(mainPoint);
					visited.add(mainPoint);
					mainIsFull = true;
				}
				if (matchPoint.numMatches == 2) {
					unvisited.remove(matchPoint);
					visited.add(matchPoint);
					if (mainIsFull) {
						// Knot Found
						// TODO check if the all of the segments in the runlist have 2 matches to
						// determine knot? actually maybe just run through knot?
						buff.add("Double match. Checking if there is a knot");
						VirtualPoint first = runList.get(0);
						VirtualPoint last = runList.get(runList.size() - 1);

						if (runList.contains(first.match1) && runList.contains(first.match2)
								&& runList.contains(last.match1) && runList.contains(last.match2)) {
							buff.add("KNOT FOUND!!!: " + runList);
							if (runList.get(0).equals(runList.get(runList.size() - 1))) {
								runList.remove(runList.size() - 1);
							}
							Knot k = new Knot(runList, this);
							knots.add(k);
							runList = new ArrayList<>();
							if (toVisit.size() == 0) {
								return knots;
							}
							mainPoint = toVisit.get(0);
							endpointReached = false;
							continue;
						}
						// should instead update the loop point to be runlist.get(0) match not in
						// runlist
						buff.add("No Knot Found,  runlist: " + runList);

					}
				}

				mainPoint = matchPoint;
			} else {
				if (endpointReached) {
					endPoint2 = mainPoint;
					buff.add("Found both end of the run, adding to knot");
					// I think if we are pointing on either end to ourselves we need to make a knot,
					// not a run
					// Above is only partially true, I think the real answer is we need to make a
					// knot out of the shorter route
					// so if we have e.g. Djbouti: 3 <- 0 <-> 1 <-> 3 <-> 2 -> 1 we could either
					// have Knot[0,1,3] or Knot[1,3,2] but not both
					// But likely it'd be better to configure it as Knot[0, Knot[1, 2, 3]], may just
					// want to have one of the shorter (in number of points not length) ones
					// and reset the matches for the left out point?
					// e.g. Djbouti: 22 <- 20 <-> 21 <-> 22 <-> 23 <-> 21 We could have
					// Knot[Knot[23, 22, 21] 20] or Knot[Knot[20, 21, 22] 23]

					// ----------------------------------------------------------------------
					// here is the new plan, we walk through the entire run and check this:
					// does the current point's next unmatched segment match with where it points
					// if it does add a knot into the run list with the points in between
					// set the knot's matches to the two external facing matches.
					// set i = i-1 and the knot flag to true, continue to the end of the list
					// if we get to the end of the list without setting the knot flag add all points
					// to a run
					// else add all points in the runlist to the knot list and reset them.

					boolean knotFlag = false;
					buff.add(runList);
					runList = RunListUtils.flattenRunPoints(runList, false);
					RunListUtils.fixRunList(runList, runList.size() - 1);
					for (VirtualPoint vp : runList) {
						vp.topGroup = vp;
						vp.group = vp;
						for (VirtualPoint kp : vp.knotPointsFlattened) {
							kp.topGroup = vp;
						}
					}
					if (runList.size() > 2) {
						for (int i = 0; i < runList.size() && runList.size() > 1; i++) {
							VirtualPoint vp = runList.get(i);
							Segment s1 = vp.getFirstUnmatched(runList);
							buff.add(s1.getOtherKnot(vp));
							VirtualPoint other = s1.getOtherKnot(vp).topGroup;
							Segment s2 = other.getFirstUnmatched(runList);
							buff.add(vp.fullString());
							buff.add("Checking: " + vp + " and : " + other + " s1: " + s1 + " s2: " + s2);
							if (s1.equals(s2) && runList.contains(other)) {
								if (other.match1.isKnot && other.shouldJoinKnot((Knot) other.match1)) {
									knotFlag = true;
									buff.add("Should JOIN JNOT");
									makeHalfKnot(runList, other, other.match1);
									buff.add(runList);
									i = -1;
									continue;
								} else if (vp.match1.isKnot && vp.shouldJoinKnot((Knot) vp.match1)) {

									knotFlag = true;
									buff.add("Should JOIN JNOT");
									buff.add(runList);
									makeHalfKnot(runList, vp, vp.match1);
									buff.add(runList);
									i = -1;
									continue;
								}
								knotFlag = true;
								buff.add("Half-Knot ends:" + s1);
								buff.add(runList);
								makeHalfKnot(runList, vp, other);
								buff.add(runList);
								i = -1;
							} else if (vp.match1.isKnot && vp.shouldJoinKnot((Knot) vp.match1)) {

								knotFlag = true;
								buff.add("Should JOIN JNOT");
								buff.add(runList);
								makeHalfKnot(runList, vp, vp.match1);
								buff.add(runList);
								i = -1;

							} else if (vp.isKnot && vp.match1.shouldKnotConsume((Knot) vp)) {

								knotFlag = true;
								buff.add("Should JOIN JNOT");
								buff.add(runList);
								makeHalfKnot(runList, vp.match1, vp);
								buff.add(runList);
								i = -1;

							} else if (vp.isKnot && vp.match2 != null && vp.match2.shouldKnotConsume((Knot) vp)) {

								knotFlag = true;
								buff.add("Should JOIN JNOT");
								buff.add(runList);
								makeHalfKnot(runList, vp.match2, vp);
								buff.add(runList);
								i = -1;

							}
						}
					}

					if (knotFlag) {
						for (VirtualPoint vp : runList) {
							vp.reset();
							knots.add(vp);
						}

						runList = new ArrayList<>();
						if (toVisit.size() == 0) {
							return knots;
						}
						mainPoint = toVisit.get(0);
						endpointReached = false;
						halfKnotCount++;

						continue;
					}

					visited.add(endPoint1);
					unvisited.remove(endPoint1);

					visited.add(endPoint2);
					unvisited.remove(endPoint2);
					// should be able to find knots within the run based on where the endpoints are
					// pointing to
					// e.g. if without a dummy point we should have ... <- 20 <- 0 <- knot[1,2,3] ->
					// 4 ->... but we have a dummy point:
					// D(21) with endpoints 3 and 4 such that 3 <-> 21 <-> 4 and knot [1,2,3]
					// becomes run[1,2,3,21,4,...], there should still be
					// the vestigial 1 <-> 3 connection order 3 so 3's match list would be 2<->3,
					// 3<->21, 3<->1, so instead of making run[1,2,3,21,4,...]
					// we should make run[knot[1,2,3],21,4,...] and do this for both endpoints in
					// the run.
					// the way we find if the vestigial match exists, is we check if 1's failed
					// connection (2 is the sucessful one) points into the
					// run list, then we check the point inside of the runlist where 1 points to,
					// and see if its 3'rd potential match is 1, if so make a knot
					// from the endpoint to where it points and make the run with the new knot
					Run k = new Run(runList, this);
					knots.add(k);
					runList = new ArrayList<>();
					if (toVisit.size() == 0) {
						return knots;
					}
					mainPoint = toVisit.get(0);
					endpointReached = false;
				} else {

					endPoint1 = mainPoint;
					endpointReached = true;
					if (runList.size() == 0) {
						knots.add(mainPoint);
						runList = new ArrayList<>();
						if (toVisit.size() == 0) {
							return knots;
						}
						mainPoint = toVisit.get(0);
						endpointReached = false;
						continue;
					}
					buff.add("nothing points back so go to other end of the runlist");
					buff.add("potential match 1's " + vp1 + " (" + pointer1 + ") matches: " + vp11 + "("
							+ pointer11 + ")" + " " + vp12 + "(" + pointer12 + ")");
					buff.add("potential match 2's " + vp2 + " (" + pointer2 + ") matches: " + vp21 + "("
							+ pointer21 + ")" + " " + vp22 + "(" + pointer22 + ")");
					mainPoint = runList.get(0);
				}
			}
		}
		return knots;
	}

	public void makeHalfKnot(ArrayList<VirtualPoint> runList, VirtualPoint vp, VirtualPoint other) {
		int vpIdx = runList.indexOf(vp);
		int otherIdx = runList.indexOf(other);
		if (vpIdx > otherIdx) {
			VirtualPoint temp = other;
			other = vp;
			vp = temp;
			int tempi = otherIdx;
			otherIdx = vpIdx;
			vpIdx = tempi;
		}
		if (vpIdx < 0) {
			buff.add(vp.fullString());
		}
		ArrayList<VirtualPoint> subList = new ArrayList<VirtualPoint>(
				runList.subList(vpIdx, otherIdx + 1));
		if (vpIdx == 0 && otherIdx == runList.size() - 1) {
			subList = runList;
		}
		VirtualPoint tempMatch;
		Point tempME;
		Point tempBP;
		Segment tempS;

		if (subList.contains(vp.match1)) {
			tempMatch = vp.match2;
			tempME = vp.match2endpoint;
			tempBP = vp.basePoint2;
			tempS = vp.s2;
			vp.setMatch2(null, null, null, null);
		} else {
			tempMatch = vp.match1;
			tempME = vp.match1endpoint;
			tempBP = vp.basePoint1;
			tempS = vp.s1;
			buff.add(vp);
			buff.add(subList);
			for (VirtualPoint vp1 : runList) {
				buff.add(vp1.fullString());
			}
			vp.swap();
			vp.setMatch2(null, null, null, null);
		}
		VirtualPoint temp2Match;
		Point temp2ME;
		Point temp2BP;
		Segment temp2S;
		if (subList.contains(other.match1)) {
			temp2Match = other.match2;
			temp2ME = other.match2endpoint;
			temp2BP = other.basePoint2;
			temp2S = other.s2;
			other.setMatch2(null, null, null, null);
		} else {
			temp2Match = other.match1;
			temp2ME = other.match1endpoint;
			temp2BP = other.basePoint1;
			temp2S = other.s1;
			buff.add(other);
			buff.add(subList);
			for (VirtualPoint vp1 : runList) {
				buff.add(vp1.fullString());
			}
			other.swap();
			other.setMatch2(null, null, null, null);
		}
		buff.add("reeee: " + subList + " vp: " + vp + " other: " + other + "runList: " + runList + " vpidx: "
				+ vpIdx + " otheridx: " + otherIdx);
		Knot k = new Knot(subList, this);
		k.setMatch1(tempMatch, tempME, tempBP, tempS);
		if (tempMatch != null) {
			buff.add(tempMatch.fullString());
			if (tempMatch.match1endpoint.equals(tempBP)) {
				tempMatch.match1 = k;
			} else {
				tempMatch.match2 = k;
			}
		}

		k.setMatch2(temp2Match, temp2ME, temp2BP, temp2S);
		if (temp2Match != null) {
			if (temp2Match.match1endpoint.equals(temp2BP)) {
				temp2Match.match1 = k;
			} else {
				temp2Match.match2 = k;
			}
		}
		runList.removeAll(subList);
		runList.add(vpIdx, k);
		buff.add(runList);
	}

	int halfKnotCount = 0;
	int sameKnotPointCount = 0;

	public void initPoints(DistanceMatrix distanceMatrix){
		this.distanceMatrix = distanceMatrix;
		int numPoints = distanceMatrix.size();
		buff.add(numPoints);
		// create all of the points
		for (int i = 0; i < numPoints; i++) {
			PointND pnd = distanceMatrix.getPoints().get(i);
			Point p = new Point(pnd, this);
			pointMap.put(pnd.getID(), p);
		}
		// create and sort the segment lists
		String sortedString = "";
		for (int i = 0; i < numPoints; i++) {
			Point p1 = (Point) pointMap.get(i);
			for (int j = 0; j < numPoints; j++) {
				if (i != j) {
					Point p2 = (Point) pointMap.get(j);
					Segment s = new Segment(p1, p2, distanceMatrix.getDistance(p1.p, p2.p));
					p1.sortedSegments.add(s);
				}
			}
			p1.sortedSegments.sort(null);
			sortedString += p1.id + " " + p1.sortedSegments.toString() + "~";
		}
		buff.add(sortedString);
	}

	@SuppressWarnings("unused")
	public Shell tspSolve(Shell A, DistanceMatrix distanceMatrix) throws SegmentBalanceException, BalancerException {
		
		Shell result = new Shell();
		visited = new ArrayList<VirtualPoint>();
		pointMap = new HashMap<>();
		unvisited = new ArrayList<VirtualPoint>();
		initPoints(distanceMatrix);
		int idx = 0;
		while (unvisited.size() > 1) {
			ArrayList<VirtualPoint> knots = createKnots();
			buff.add("\n================= - Layer: " + idx + " - =================");
			unvisited = knots;
			buff.add("unvisited:" + unvisited);
			buff.add("visited:" + visited);
			buff.add("================= - Layer: " + idx + " - =================\n");
			if (idx == 30) {
				float zero = 1 / 0;
			}
			idx++;
		}
		buff.add("\n================= - WARNING - =================");
		buff.add("警告:ゴーディアスノットを切断します");
		buff.add("システムロックが解除されました");
		buff.add("ナイフが噛み合った");
		buff.add("カット開始");
		buff.add("================= - WARNING - =================\n");
		int knotsCleared = 0;
		if (unvisited.size() == 1) {
			VirtualPoint gp1 = unvisited.get(0);
			if (gp1.isKnot) {
				result = cutKnot((Knot) gp1);
			}
		}
		return result;
	}

	public ArrayList<VirtualPoint> slowSolve(Shell A, DistanceMatrix distanceMatrix, int layers) {
		this.distanceMatrix = distanceMatrix;
		visited = new ArrayList<VirtualPoint>();
		pointMap = new HashMap<>();
		unvisited = new ArrayList<VirtualPoint>();
		int numPoints = distanceMatrix.size();
		// create all of the points
		for (int i = 0; i < numPoints; i++) {
			Point p = new Point(distanceMatrix.getPoints().get(i), this);
			pointMap.put(i, p);
		}
		// create and sort the segment lists
		String sortedString = "";
		for (int i = 0; i < numPoints; i++) {
			Point p1 = (Point) pointMap.get(i);
			for (int j = 0; j < numPoints; j++) {
				if (i != j) {
					Point p2 = (Point) pointMap.get(j);
					Segment s = new Segment(p1, p2, distanceMatrix.getDistance(p1.p, p2.p));
					p1.sortedSegments.add(s);
				}
			}
			p1.sortedSegments.sort(null);
			sortedString += p1.id + " " + p1.sortedSegments.toString() + "~";
		}
		buff.add(sortedString);
		int idx = 0;
		while (unvisited.size() > 1 && idx != layers) {
			ArrayList<VirtualPoint> knots = createKnots();
			buff.add("\n================= - Layer: " + idx + " - =================");
			unvisited = knots;
			buff.add("unvisited:" + unvisited);
			buff.add("visited:" + visited);
			buff.add("================= - Layer: " + idx + " - =================\n");
			idx++;
		}
		return unvisited;
	}

	public Shell cutKnot(Knot mainKnot) throws SegmentBalanceException, BalancerException {
		// seems like there are three cases: combining two knots, (need to remove two
		// segments and add two with the lowest cost increase)
		// pulling apart two knot that has two endpoints and want to cut different
		// segments (need to match the two segments that lost an end)
		// pulling apart two knot that has two endpoints and want to cut the same
		// segment (just remove the segment)
		ArrayList<VirtualPoint> knotList = cutEngine.cutKnot(mainKnot.knotPoints);
		Shell result = new Shell();
		for (VirtualPoint p : knotList) {
			result.add(((Point) p).p);
		}
		return result;

	}

	public Integer[][] smallestCommonKnotLookup;
	public Integer[] smallestKnotLookup;

	public void updateSmallestKnot(Knot knotNew) {

		if (smallestKnotLookup == null) {

			smallestKnotLookup = new Integer[distanceMatrix.size()];
			Arrays.fill(smallestKnotLookup, -1);
		}

		for (VirtualPoint vp : knotNew.knotPointsFlattened) {
			int low = vp.id;
			if (smallestKnotLookup[low] == -1) {
				smallestKnotLookup[low] = knotNew.id;
			}
		}
	}

	public void updateSmallestCommonKnot(Knot knotNew) {

		if (smallestCommonKnotLookup == null) {

			smallestCommonKnotLookup = new Integer[distanceMatrix.size()][distanceMatrix.size()];
			for (int i = 0; i < smallestCommonKnotLookup.length; i++) {
				Arrays.fill(smallestCommonKnotLookup[i], -1);
			}
		}

		for (VirtualPoint vp : knotNew.knotPointsFlattened) {
			int low = vp.id;
			for (VirtualPoint vp2 : knotNew.knotPointsFlattened) {
				if (!vp.equals(vp2)) {
					int high = vp2.id;
					if (smallestCommonKnotLookup[high][low] != -1) {
						continue;
					}
					smallestCommonKnotLookup[high][low] = knotNew.id;
					smallestCommonKnotLookup[low][high] = knotNew.id;
				}
			}
		}
	}

	public Shell solveBetweenEndpoints(PointND first, PointND last, Shell A, DistanceMatrix d)
			throws SegmentBalanceException, BalancerException {
		PointSet ps = new PointSet();

		assert (!first.equals(last));

		ps.add(first);
		if (!first.equals(last)) {
			ps.add(last);
		}
		ps.addAll(A);
		DistanceMatrix d1 = new DistanceMatrix(ps, d);
		PointND dummy = d1.addDummyNode(-1,first, last);
		ps.add(dummy);
		Shell answer = new Shell();
		answer.add(first);
		answer.addAll(A.copyShallow());
		answer.add(last);
		answer.add(dummy);
		buff.add(answer.size());
		Shell result = tspSolve(answer, d1);

		assert (d1.getZero() != 0);
		assert (d1.getMaxDist() / 2 <= d1.getZero()) : "Zero: " + d1.getZero() + " MaxDist: " + d1.getMaxDist();

		buff.add(result);
		ps.remove(dummy);
		result = result.removeRotate(ps);
		if (!result.get(0).equals(first)) {
			result = result.reverse();
		}

		return result;

	}

	/**
	 * Initializes a new shell with no parent or child; a blank slate
	 * 
	 * @param points
	 */

	public Shell(PointND... points) {
		for (int i = 0; i < points.length; i++) {
			this.add(points[i]);
		}
	}

	/**
	 * Get the length of the shell
	 * 
	 * @return the length of the path between all points in the shell
	 */
	public double getLength() {
		PointND first = null, last = null;
		double length = 0.0;
		for (PointND p : this) {
			if (first == null) {
				last = p;
				first = p;
			} else {
				length += last.distance(p);
				last = p;
			}
		}
		length += last.distance(first);
		return length;

	}

	public double getLengthEndpoints() {
		PointND first = null, last = null;
		double length = 0.0;
		for (PointND p : this) {
			if (first == null) {
				last = p;
				first = p;
			} else {
				length += last.distance(p);
				last = p;
			}
		}
		return length;

	}

	/**
	 * Draws the Shell and its children if drawChildren is true
	 * 
	 * @param frame        where to draw the shell
	 * @param g2           graphics object for frame
	 * @param drawChildren whether or not to draw child shells
	 * @param c            the color to draw the shell (set to null to get a random
	 *                     color)
	 */
	public void drawShell(JComponent frame, Graphics2D g2, boolean drawChildren, int lineThickness, Color c,
			PointSet ps, Camera camera) {
		if (c == null) {
			Random colorSeed = new Random();
			Drawing.drawPath(frame, g2, toPath(this), lineThickness,
					new Color(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat()), ps,
					true, false, false, false, camera);
		} else {
			Drawing.drawPath(frame, g2, toPath(this), lineThickness, c, ps, true, false, false, false, camera);
		}
	}

	/**
	 * Gets the distance from a point to its neighboring points in the shell
	 * 
	 * @param p
	 * @param maxDist
	 * @return the sum of the distance from p to the prev point in the shell and the
	 *         distance from p to the next point in the shell
	 */
	public double distanceToNeighbors(PointND p, DistanceMatrix d) {
		PointND prevP = prevPoint(p), nextP = nextPoint(p);

		return d.getDistance(p, prevP) + d.getDistance(p, nextP);

	}

	/**
	 * Gets the distance from the point previous to p and the point after p in the
	 * shell
	 * 
	 * @param p
	 * @param maxDist
	 * @return the sum of the distance from the prev point in the shell to the next
	 *         point in the shell
	 */
	public double distanceBetweenNeighbors(PointND p, DistanceMatrix d) {
		PointND prevP = prevPoint(p), nextP = nextPoint(p);

		return d.getDistance(nextP, prevP);

	}

	/**
	 * Finds the previous point in the shell
	 * 
	 * @param p reference point
	 * @return the point that comes before p in the shell
	 */
	public PointND prevPoint(PointND p) {
		int i = this.indexOf(p), before = 0;
		if (i == 0) {
			before = this.size() - 1;
		} else {
			before = i - 1;
		}
		return this.get(before);
	}

	/**
	 * Finds the next point in the shell
	 * 
	 * @param p reference point
	 * @return the point that comes after p in the shell
	 */
	public PointND nextPoint(PointND p) {
		int i = this.indexOf(p), after = 0;
		if (i == this.size() - 1) {
			after = 0;
		} else {
			after = i + 1;
		}
		return this.get(after);
	}

	public static Shell replaceByID(Shell A, PointSet ps) {
		Shell result = new Shell();
		for (PointND p : A) {
			result.add(ps.getByID(p.getID()));
		}
		// TODO Auto-generated method stub
		return result;
	}

	public Shell removeRotate(PointSet ps) {

		Shell before = new Shell(), after = new Shell();

		// find the dummy node and take it out of the Shell unwrapping at the dummy.
		boolean isBeforePoint = true;
		for (PointND p : this) {
			if (!ps.contains(p)) {
				isBeforePoint = false;
			} else {
				if (isBeforePoint) {
					before.add(p);
				} else {
					after.add(p);
				}
			}
		}
		// reverse the set if need be to match the input segment s
		after.addAll(before);

		assert (after.size() == this.size() - 1);

		return after;
	}

	public void rotateTo(PointND p1, PointND p2) {
		Shell before = new Shell(), after = new Shell();

		// find the dummy node and take it out of the Shell unwrapping at the dummy.
		boolean isBeforePoint = true;
		for (PointND p : this) {
			if ((p.equals(p1) && this.nextPoint(p).equals(p2)) || (p.equals(p2) && this.nextPoint(p).equals(p1))) {
				isBeforePoint = false;
				before.add(p);
			} else {
				if (isBeforePoint) {
					before.add(p);
				} else {
					after.add(p);
				}
			}
		}
		this.removeAll(before);
		this.addAll(before);
	}

	/**
	 * Shallow copies a shell so that it does not point to any childern
	 * 
	 * @return a copy of the current shell with no references to its children
	 */
	public Shell copyShallow() {
		Shell copy = new Shell();

		for (PointND q : this) {
			copy.add(q);
		}
		return copy;
	}

	/**
	 * Turns a shell into a path object
	 * 
	 * @param shell
	 * @return a path that represnts the path through all points in the shell
	 */
	public static Path2D toPath(Shell shell) {
		Path2D path = new GeneralPath();
		boolean first = true;
		for (PointND p : shell) {
				Point2D p2d = p.toPoint2D();
				if (first) {
					path.moveTo(p2d.getX(), p2d.getY());
					first = false;
				} else {
					path.lineTo(p2d.getX(), p2d.getY());
				}

		}
		return path;

	}

	/**
	 * Turns a shell into a PointSet object
	 * 
	 * @return all of the points in the Shell and its children
	 */
	public PointSet toPointSet() {
		PointSet ps = new PointSet();
		Shell currShell = this;
		while (currShell != null) {
			for (PointND p : currShell) {
				ps.add(p);

			}
			currShell = currShell.child;
		}
		return ps;

	}

	/**
	 * Determines equality of shells based on if they represent the same tsp path
	 * 
	 * @param o shell to compare to
	 * @return true if the shells are equal and false if they are not
	 */
	@Override
	public boolean equals(Object o) {
		if (o instanceof Shell) {
			Shell other = (Shell) o;
			if (other.size() != this.size()) {
				return false;
			}
			PointND otherFirst = other.getFirst();
			int startIndex = -1;
			for (PointND p : this) {
				if (p.equals(otherFirst)) {
					startIndex = this.indexOf(p);
					break;
				}
			}
			if (startIndex == -1) {
				return false;
			}
			for (int i = 0; i < other.size(); i++) {
				if (!other.get(i).equals(this.get(startIndex))) {
					return false;
				}
				startIndex = (startIndex + 1) % other.size();
			}
			return true;
		}
		return false;

	}

	public Shell reverse() {
		Shell result = new Shell();
		for (PointND p : this) {
			result.addFirst(p);
		}
		return result;
	}

	@Override
	public String toString() {
		String str = "Shell[";
		for (int i = 0; i < this.size(); i++) {
			PointND p = this.get(i);
			if (p.getID() != -1) {
				str += p.getID();
			} else {
				str += p.toString();
			}
			if (i < this.size() - 1) {
				str += ", ";
			}
		}

		return str + "]";
	}

	public static String compareTo(Shell A, Shell B) {
		String str = "Shell A[";
		for (int i = 0; i < A.size() - 1; i++) {
			str += (i) + ", ";
		}
		str += A.size() - 1 + "]";

		str += "\nShell B[";
		for (int i = 0; i < B.size() - 1; i++) {
			str += (A.indexOf(B.get(i))) + ", ";
		}
		str += (A.indexOf(B.get(B.size() - 1))) + "]";

		return str;

	}

	@Override
	public boolean add(PointND e) {
		super.add(e);
		return true;

	}

	@Override
	public boolean addAll(Collection<? extends PointND> c) {
		super.addAll(c);
		return true;
	}

	public boolean addAllFirst(Collection<? extends PointND> c) {
		Object[] points = c.toArray();
		for (int i = points.length - 1; i >= 0; i--) {
			this.addFirst((PointND) points[i]);
		}
		return true;
	}

	public void addAfter(PointND contained, PointND insert) {
		// assert(!this.contains(insert));
		super.add(this.indexOf(contained) + 1, insert);
	}

	public void addOutside(PointND contained, PointND insert) {
		// assert(!this.contains(insert));
		assert (this.getLast().equals(contained) || this.getFirst().equals(contained))
				: insert.getID() + " " + contained.getID() + " " + this.toString();
		super.add(this.indexOf(contained) + 1, insert);
		if (this.getLast().equals(contained)) {
			this.rotateTo(this.getFirst(), insert);
		} else {
			this.rotateTo(this.getLast(), insert);
		}
	}

	public void addAllAtSegment(PointND contained, PointND connector, Shell other) {
		if (this.getLast().equals(contained)) {
			if (other.getLast().equals(connector)) {
				Shell reverse = other.reverse();
				this.addAll(reverse);
			} else {
				this.addAll(other);
			}
		} else {
			if (other.getLast().equals(connector)) {
				this.addAllFirst(other);
			} else {
				Shell reverse = other.reverse();
				this.addAllFirst(reverse);
			}
		}
	}

	public PointND getOppositeOutside(PointND endpoint) {
		// assert(!this.contains(insert));
		assert (this.getLast().equals(endpoint) || this.getFirst().equals(endpoint)) : endpoint.getID();
		if (this.getLast().equals(endpoint)) {
			return this.getFirst();
		} else {
			return this.getLast();
		}
	}

	public boolean isEndpoint(PointND p) {
		return p.equals(this.getLast()) || p.equals(this.getFirst());
	}

	public boolean containsID(int id) {
		for (PointND pointND : this) {
			if (pointND.getID() == id) {
				return true;
			}
		}
		return false;
	}

}
