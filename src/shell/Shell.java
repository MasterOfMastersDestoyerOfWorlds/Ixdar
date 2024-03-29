package shell;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.swing.JComponent;

import org.apache.commons.math3.util.Pair;

/**
 * This class represents a list of some points in the point set. Initially each
 * shell is a convex hull, but they are eventually combined together to form the
 * optimal tsp path and they lose their convex property
 */
public class Shell extends LinkedList<PointND> {
	private static final long serialVersionUID = -5904334592585016845L;
	private int ORDER = 1;
	public static int failed = 0;
	private boolean maximal, minimal;
	private Shell parent, child;
	ArrayList<VirtualPoint> visited;
	ArrayList<VirtualPoint> unvisited;
	HashMap<Integer, VirtualPoint> pointMap = new HashMap<Integer, VirtualPoint>();
	DistanceMatrix distanceMatrix;
	int numKnots;

	class Segment implements Comparable {
		VirtualPoint first;
		VirtualPoint last;
		double distance;

		public Segment(VirtualPoint first,
				VirtualPoint last,
				double distance) {
			this.first = first;
			this.last = last;
			this.distance = distance;

		}

		VirtualPoint getOther(VirtualPoint p) {
			if (p.equals(first)) {
				return last;
			}
			if (p.equals(last)) {
				return first;
			}
			return null;
		}

		VirtualPoint getKnotPoint(ArrayList<VirtualPoint> knotPointsFlattened) {
			if (knotPointsFlattened.contains(first)) {
				return first;
			}
			if (knotPointsFlattened.contains(last)) {
				return last;
			}
			return null;
		}

		@Override
		public int compareTo(Object o) {
			if (o.getClass() == Segment.class) {
				Segment s = (Segment) o;
				if (s.distance < this.distance) {
					return 1;
				} else if (s.distance > this.distance) {
					return -1;
				} else {
					return 0;
				}
			}
			System.out.println("not a segment");
			return -1;
		}

		@Override
		public String toString() {
			return "Segment[" + first.id + ":" + last.id + "]";
		}
	}

	abstract class VirtualPoint {
		int numMatches;
		Point match1endpoint;
		VirtualPoint match1;
		Point basePoint1;
		Point match2endpoint;
		VirtualPoint match2;
		Point basePoint2;
		int id;
		boolean isKnot;
		VirtualPoint group;

		public Point getPointer(int idx) {
			throw new UnsupportedOperationException("Unimplemented method 'getPointer'");
		}

		public String fullString() {
			// TODO Auto-generated method stub
			throw new UnsupportedOperationException("Unimplemented method 'fullString'");
		}
	}

	class Point extends VirtualPoint {
		public ArrayList<Segment> sortedSegments = new ArrayList<Segment>();
		public PointND p;

		public Point(PointND p) {
			this.p = p;
			this.id = p.getID();
			unvisited.add(this);
			isKnot = false;
			group = this;
			basePoint1 = this;
			basePoint2 = this;
		}

		public Point getPointer(int idx) {
			ArrayList<VirtualPoint> seenGroups = new ArrayList<VirtualPoint>();
			int count = idx;
			for (int i = 0; i < sortedSegments.size(); i++) {
				Segment s = sortedSegments.get(i);
				VirtualPoint basePoint = s.getOther(this);
				VirtualPoint vp = basePoint;
				if (vp.group != null) {
					vp = vp.group;
				}
				if ((vp.numMatches != 2 || vp.equals(match1)) && !seenGroups.contains(vp)) {
					count--;
					if (count == 0) {
						return (Point) basePoint;
					}
					seenGroups.add(vp);
				}
			}
			return null;
		}

		@Override
		public String toString() {
			return "" + this.p.getID();
		}

		public String fullString() {
			return "" + this.p.getID()
					+ " match1: " + (match1 == null ? " none " : "" + match1.id)
					+ " match1endpoint: " + (match1endpoint == null ? " none " : "" + match1endpoint.id)
					+ " basepoint1: " + (basePoint1 == null ? " none " : "" + basePoint1.id)
					+ " match2: " + (match2 == null ? " none " : "" + match2.id)
					+ " match2endpoint: " + (match2endpoint == null ? " none " : "" + match2endpoint.id)
					+ " basepoint2: " + (basePoint2 == null ? " none " : "" + basePoint2.id);
		}
	}

	class Knot extends VirtualPoint {
		public int size;
		public ArrayList<VirtualPoint> knotPoints; // [ vp1, vp2, ... vpm];
		public ArrayList<VirtualPoint> knotPointsFlattened;
		public Set<Segment> internalKnotSegments; // [s1,s2,...,sm]
		public ArrayList<Segment> externalKnotSegments; // [[s1, ..., sn-1], [s1, ..., sn-1], ... m]; sorted and remove
														// vp1, vp2, ... vpm

		public Knot(ArrayList<VirtualPoint> knotPoints) {
			this.knotPoints = knotPoints;
			isKnot = true;
			size = knotPoints.size();
			knotPointsFlattened = new ArrayList<VirtualPoint>();
			for (VirtualPoint vp : knotPoints) {
				if (vp.isKnot) {
					knotPointsFlattened.addAll(((Knot) vp).knotPointsFlattened);
				} else {
					knotPointsFlattened.add(vp);
				}
			}
			int numPoints = unvisited.size() + visited.size();

			// get the segments that form the knot
			boolean odd = true;
			internalKnotSegments = new HashSet<Segment>();
			for (VirtualPoint vp : knotPoints) {
				System.out.println(vp.fullString());
				if (odd) {
					internalKnotSegments.add(new Segment(vp, vp.match1,
							distanceMatrix.getDistance(vp.basePoint1.p, vp.match1endpoint.p)));
				} else {
					internalKnotSegments.add(new Segment(vp, vp.match2,
							distanceMatrix.getDistance(vp.basePoint2.p, vp.match2endpoint.p)));
				}
				odd = !odd;
			}

			// store the segment lists of each point contained in the knot, recursive
			externalKnotSegments = new ArrayList<Segment>();
			for (VirtualPoint vp : knotPoints) {
				if (vp.isKnot) {
					ArrayList<Segment> segments = new ArrayList<Segment>();
					ArrayList<Segment> vpExternal = ((Knot) vp).externalKnotSegments;
					for (Segment s : vpExternal) {
						if (!(knotPointsFlattened.contains(s.first) && knotPointsFlattened.contains(s.last))) {
							externalKnotSegments.add(s);
						}
					}
				} else {
					System.out.println(vp.isKnot);
					ArrayList<Segment> sortedSegments = ((Point) vp).sortedSegments;
					for (Segment s : sortedSegments) {
						if (!(knotPointsFlattened.contains(s.first) && knotPointsFlattened.contains(s.last))) {
							externalKnotSegments.add(s);
						}
					}
				}
				vp.group = this;
			}
			externalKnotSegments.sort(null);
			System.out.println("knotPoints Flattened:" + knotPointsFlattened);
			System.out.println(externalKnotSegments);
			this.id = numPoints;
			pointMap.put(id, this);
			unvisited.add(this);
			numKnots++;
		}

		public Point getPointer(int idx) {
			int count = idx;
			ArrayList<VirtualPoint> seenGroups = new ArrayList<VirtualPoint>();
			for (int i = 0; i < externalKnotSegments.size(); i++) {
				Segment s = externalKnotSegments.get(i);
				VirtualPoint knotPoint = s.getKnotPoint(knotPointsFlattened);
				VirtualPoint basePoint = s.getOther(knotPoint);
				VirtualPoint vp = basePoint;
				if (vp.group != null) {
					vp = vp.group;
				}
				if ((vp.numMatches != 2 && !seenGroups.contains(vp))) {
					count--;
					if (count == 0) {
						return (Point) basePoint;
					}
					if (unvisited.size() > 2) {
						seenGroups.add(vp);
					}
					seenGroups.add(knotPoint);
				}
			}
			return null;
		}

		@Override
		public String toString() {
			String str = "Knot[";
			for (VirtualPoint vp : knotPoints) {
				str += vp + " ";
			}
			str.stripTrailing();
			str += "]";
			return str;
		}

		public String fullString() {
			return "" + this
					+ " match1: " + (match1 == null ? " none " : "" + match1.id)
					+ " match1endpoint: " + (match1endpoint == null ? " none " : "" + match1endpoint.id)
					+ " basepoint1: " + (basePoint1 == null ? " none " : "" + basePoint1.id)
					+ " match2: " + (match2 == null ? " none " : "" + match2.id)
					+ " match2endpoint: " + (match2endpoint == null ? " none " : "" + match2endpoint.id)
					+ " basepoint2: " + (basePoint2 == null ? " none " : "" + basePoint2.id);
		}
	}

	public Pair<Knot, ArrayList<VirtualPoint>> createKnots(VirtualPoint startPoint) {
		ArrayList<VirtualPoint> runList = new ArrayList<>();
		System.out.println("startPoint: " + startPoint);
		System.out.println("runList: " + runList);
		VirtualPoint mainPoint = startPoint;
		boolean knotFound = false;
		while (!knotFound) {
			System.out.println("Main Point is now:" + mainPoint);
			System.out.println("runList:" + runList);
			System.out.println("visited:" + visited);
			if (mainPoint.numMatches == 2) {
				if (!runList.contains(mainPoint)) {
					runList.add(mainPoint);
				}
				boolean left = false;
				boolean right = false;

				VirtualPoint first = runList.get(0);
				VirtualPoint last = runList.get(runList.size() - 1);
				VirtualPoint prev = mainPoint;
				if (runList.contains(prev.match1)) {
					mainPoint = prev.match2;
					left = true;
				}
				if (runList.contains(prev.match2)) {
					mainPoint = prev.match1;
					right = true;
				}
				if (left && right) {
					System.out.println(runList);
					System.out.println();
					if (runList.contains(first.match1) && runList.contains(first.match2)
							&& runList.contains(last.match1) && runList.contains(last.match2)) {

						knotFound = true;
						System.out.println("KNOT FOUND!!!: " + runList);
						if (runList.get(0).equals(runList.get(runList.size() - 1))) {
							runList.remove(runList.size() - 1);
						}
						Knot k = new Knot(runList);
						return new Pair<Knot, ArrayList<VirtualPoint>>(k, null);
					}
					float zero = 1 / 0;
				} else {
					if (!runList.contains(prev)) {
						runList.add(prev);
					}
				}
				continue;
			}
			Point pointer1 = mainPoint.getPointer(1);
			Point pointer11 = pointer1.getPointer(1);
			Point pointer12 = pointer1.getPointer(2);
			System.out.println("Point " + mainPoint + " points to: " + pointer1);
			Point pointer2 = mainPoint.getPointer(2);
			System.out.println("Point " + mainPoint + " points to: " + pointer2);
			Point pointer21 = pointer2.getPointer(1);
			Point pointer22 = pointer2.getPointer(2);
			VirtualPoint vp1 = pointer1.group;
			VirtualPoint vp11 = pointer11.group;
			VirtualPoint vp12 = pointer12.group;
			VirtualPoint vp2 = pointer2.group;
			VirtualPoint vp21 = pointer21.group;
			VirtualPoint vp22 = pointer22.group;
			Point matchEndPoint = null;
			Point matchBasePoint = null;
			VirtualPoint matchPoint = null;
			Pair<VirtualPoint, Point> matchPair = null;
			System.out.println("Point " + mainPoint + " points to: " + vp1 + " and " + vp2);
			if ((mainPoint.equals(vp11) || mainPoint.equals(vp12))
					&& (mainPoint.numMatches == 0 || !mainPoint.match1.equals(vp1))) {
				matchPoint = vp1;
				matchEndPoint = pointer1;
				if (mainPoint.equals(vp11)) {
					matchBasePoint = pointer11;
				}
				if (mainPoint.equals(vp12)) {
					matchBasePoint = pointer12;
				}
			} else if ((mainPoint.equals(vp21) || mainPoint.equals(vp22))
					&& (mainPoint.numMatches == 0 || !mainPoint.match1.equals(vp2))) {
				matchPoint = vp2;
				matchEndPoint = pointer2;
				if (mainPoint.equals(vp21)) {
					matchBasePoint = pointer21;
				}
				if (mainPoint.equals(vp22)) {
					matchBasePoint = pointer22;
				}
			}
			if (matchPoint != null) {
				System.out.println("Point " + matchPoint + " points back" + " | basepoint: " + matchBasePoint
						+ " endpoint: " + matchEndPoint);
				if (mainPoint.numMatches == 0) {
					mainPoint.match1 = matchPoint;
					mainPoint.match1endpoint = matchEndPoint;
					mainPoint.basePoint1 = matchBasePoint;
				} else {
					double d1 = distanceMatrix.getDistance(mainPoint.basePoint1.p, mainPoint.match1endpoint.p);
					double d2 = distanceMatrix.getDistance(matchEndPoint.p, matchBasePoint.p);
					if (d1 > d2) {
						mainPoint.match2 = mainPoint.match1;
						mainPoint.match2endpoint = mainPoint.match1endpoint;
						mainPoint.basePoint2 = mainPoint.basePoint1;
						mainPoint.match1 = matchPoint;
						mainPoint.match1endpoint = matchEndPoint;
						mainPoint.basePoint1 = matchBasePoint;
					} else {
						mainPoint.match2 = matchPoint;
						mainPoint.match2endpoint = matchEndPoint;
						mainPoint.basePoint2 = matchBasePoint;
					}
				}
				if (matchPoint.numMatches == 0) {
					matchPoint.match1 = mainPoint;
					matchPoint.basePoint1 = matchEndPoint;
					matchPoint.match1endpoint = matchBasePoint;
				} else {
					double d1 = distanceMatrix.getDistance(matchPoint.basePoint1.p, matchPoint.match1endpoint.p);
					double d2 = distanceMatrix.getDistance(matchEndPoint.p, matchBasePoint.p);
					if (d1 > d2) {
						matchPoint.match2 = matchPoint.match1;
						matchPoint.match2endpoint = matchPoint.match1endpoint;
						matchPoint.basePoint2 = matchPoint.basePoint1;
						matchPoint.match1 = mainPoint;
						matchPoint.basePoint1 = matchEndPoint;
						matchPoint.match1endpoint = matchBasePoint;
					} else {
						matchPoint.match2 = mainPoint;
						matchPoint.basePoint2 = matchEndPoint;
						matchPoint.match2endpoint = matchBasePoint;
					}
				}
				mainPoint.numMatches++;
				matchPoint.numMatches++;
				if (!runList.contains(mainPoint)) {
					runList.add(mainPoint);
				}
				runList.add(matchPoint);
				boolean mainIsFull = false;
				boolean matchIsFull = false;
				System.out.println("Setting Match: " + mainPoint + " : " + matchPoint);
				System.out.println(mainPoint + " has " + mainPoint.numMatches + " matches : " + matchPoint + " has "
						+ matchPoint.numMatches + " matches");
				if (mainPoint.numMatches == 2) {
					unvisited.remove(mainPoint);
					visited.add(mainPoint);
					mainIsFull = true;
				}
				if (matchPoint.numMatches == 2) {
					unvisited.remove(matchPoint);
					visited.add(matchPoint);
					matchIsFull = true;
					if (mainIsFull) {
						// Knot Found
						// TODO check if the all of the segments in the runlist have 2 matches to
						// determine knot? actually maybe just run through knot?
						System.out.println("Double match. Checking if there is a knot");
						VirtualPoint first = runList.get(0);
						VirtualPoint last = runList.get(runList.size() - 1);

						if (runList.contains(first.match1) && runList.contains(first.match2)
								&& runList.contains(last.match1) && runList.contains(last.match2)) {
							System.out.println("KNOT FOUND!!!: " + runList);
							if (runList.get(0).equals(runList.get(runList.size() - 1))) {
								runList.remove(runList.size() - 1);
							}
							Knot k = new Knot(runList);
							return new Pair<Knot, ArrayList<VirtualPoint>>(k, null);
						}
						// should instead update the loop point to be runlist.get(0) match not in
						// runlist
						System.out.println("No Knot Found,  runlist: " + runList);

					}
				}

				mainPoint = matchPoint;
			} else {
				System.out.println("nothing points back so recurse");
				System.out.println("potential match 1's " + vp1 + " (" + pointer1 + ") matches: " + vp11 + "("
						+ pointer11 + ")" + " " + vp12 + "(" + pointer12 + ")");
				System.out.println("potential match 2's " + vp2 + " (" + pointer2 + ") matches: " + vp21 + "("
						+ pointer21 + ")" + " " + vp22 + "(" + pointer22 + ")");
				// possible dead end, recurse
				runList = new ArrayList<>();
				Pair<Knot, ArrayList<VirtualPoint>> pair = null;
				if ((mainPoint.numMatches == 1 && !mainPoint.match1.equals(vp1)) || mainPoint.numMatches == 0) {
					mainPoint = vp1;
				} else if (mainPoint.numMatches == 1 && !mainPoint.match1.equals(vp2)) {
					mainPoint = vp2;
				}
			}
		}
		return new Pair<Knot, ArrayList<VirtualPoint>>(null, runList);
	}

	public Shell tspSolve(Shell A, DistanceMatrix distanceMatrix) {
		this.distanceMatrix = distanceMatrix;
		Shell result = new Shell();
		visited = new ArrayList<VirtualPoint>();

		unvisited = new ArrayList<VirtualPoint>();
		int numPoints = distanceMatrix.size();
		// create all of the points
		for (int i = 0; i < numPoints; i++) {
			Point p = new Point(distanceMatrix.getPoints().get(i));
			pointMap.put(p.id, p);
		}
		// create and sort the segment lists
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
			System.out.println(p1.sortedSegments);
		}
		Knot k = null;
		int idx = 0;
		while (unvisited.size() > 2) {
			VirtualPoint p = unvisited.get(0);
			Pair<Knot, ArrayList<VirtualPoint>> pair = createKnots(p);
			System.out.println("unvisited:" + unvisited);
			System.out.println("visited:" + visited);
			if (idx > numPoints) {
				break;
			}
			k = pair.getFirst();
			idx++;
		}
		System.out.println("\n================= - WARNING - =================");
		System.out.println("警告:ゴーディアスノットを切断します");
		System.out.println("システムロックが解除されました");
		System.out.println("ナイフが噛み合った");
		System.out.println("カット開始");
		System.out.println("================= - WARNING - =================\n");
		System.out.println(unvisited);
		// seems like there are three cases: combining two knots, (need to remove two
		// segments and add two with the lowest cost increase)
		// pulling apart two knot that has two endpoints and want to cut different
		// segments (need to match the two segments that lost an end)
		// pulling apart two knot that has two endpoints and want to cut the same
		// segment (just remove the segment)

		if (unvisited.size() == 2) {
			// need to loop through each segment in each knot and see which is most
			// beneficial
			// (current minus new)
			// to cut
			VirtualPoint gp1 = unvisited.get(0);

			VirtualPoint gp2 = unvisited.get(1);

			Point pointer1 = gp1.getPointer(1);
			VirtualPoint cutPoint1 = pointer1.match2endpoint;
			Point pointer2 = pointer1.group.getPointer(1);
			VirtualPoint cutPoint2 = pointer2.match2endpoint;
			ArrayList<VirtualPoint> newList = new ArrayList<>();
			if (gp1.isKnot && gp2.isKnot) {
				Knot knot1 = (Knot) gp1;
				Knot knot2 = (Knot) gp2;
				/*
				 * System.out.println(pointer1);
				 * System.out.println(pointer2);
				 * System.out.println(cutPoint1);
				 * System.out.println(cutPoint2);
				 */
				// need to make a new list with pointer 1 and pointer 11 in the midd;le and
				// their matchpoints on the ends
				VirtualPoint addPoint = pointer2.match2endpoint;
				VirtualPoint prevPoint = pointer2;
				boolean odd = true;
				for (int i = 0; i < knot1.knotPoints.size(); i++) {
					newList.add(addPoint);
					if (prevPoint.equals(addPoint.match2)) {
						prevPoint = addPoint;
						addPoint = addPoint.match1;
					} else {
						prevPoint = addPoint;
						addPoint = addPoint.match2;
					}

				}
				addPoint = pointer1;
				prevPoint = pointer1.match2endpoint;
				for (int i = 0; i < knot2.knotPoints.size(); i++) {
					newList.add(addPoint);
					if (prevPoint.equals(addPoint.match2)) {
						prevPoint = addPoint;
						addPoint = addPoint.match1;
					} else {
						prevPoint = addPoint;
						addPoint = addPoint.match2;
					}
				}
				cutPoint2.match2 = cutPoint1;
				cutPoint1.match2 = cutPoint2;
				cutPoint1.match2endpoint = cutPoint2.basePoint2;
				cutPoint2.match2endpoint = cutPoint1.basePoint2;
				pointer1.match2 = pointer2;
				pointer2.match2 = pointer1;
				pointer1.match2endpoint = pointer2.basePoint2;
				pointer2.match2endpoint = pointer1.basePoint2;
				/*
				 * System.out.println(cutPoint1.fullString());
				 * System.out.println(cutPoint2.fullString());
				 * System.out.println(pointer1.fullString());
				 * System.out.println(pointer2.fullString());
				 */
				numKnots -= 2;
			}
			unvisited = newList;

		}

		System.out.println(unvisited);

		// move on to the cutting phase
		VirtualPoint prevPoint = unvisited.get(unvisited.size() - 1);
		for (int i = 0; i < unvisited.size(); i++) {
			VirtualPoint vp = unvisited.get(i);
			System.out.println("Checking Point: " + vp);
			if (vp.isKnot) {
				Knot knot = (Knot) vp;
				System.out.println("Found Knot!" + " match1: " + knot.match1 + " basepoint 1: " + knot.basePoint1
						+ " match2: " + knot.match2 + " basepoint 2: " + knot.basePoint2);
				VirtualPoint knotPoint1 = knot.basePoint1;
				VirtualPoint cutPoint1 = knotPoint1.match2endpoint;
				if (!cutPoint1.group.equals(knot)) {
					cutPoint1 = cutPoint1.group;
				}
				VirtualPoint external1 = knot.match1;
				VirtualPoint knotPoint2 = knot.basePoint2;
				if (knotPoint2.equals(knotPoint1)) {
					knotPoint2 = cutPoint1;
				}
				VirtualPoint cutPoint2 = knotPoint2.match2endpoint;
				if (!cutPoint2.group.equals(knot)) {
					cutPoint2 = cutPoint2.group;
				}
				VirtualPoint external2 = knot.match2;
				System.out.println("knotPoint1: " + knotPoint1);
				System.out.println("cutPoint1: " + cutPoint1);
				System.out.println("external1: " + external1);
				System.out.println("knotPoint2: " + knotPoint2);
				System.out.println("cutPoint2: " + cutPoint2);
				System.out.println("external2: " + external2);
				System.out.println("prevPoint: " + prevPoint);

				System.out.println(knotPoint1.fullString());
				System.out.println(knotPoint2.fullString());
				System.out.println(cutPoint1.fullString());
				System.out.println(cutPoint2.fullString());
				if (cutPoint1.equals(knotPoint1.match2)) {
					knotPoint1.match2 = external1;
					knotPoint1.match2endpoint = external1.basePoint2;
				} else {
					knotPoint1.match1 = external1;
					knotPoint1.match1endpoint = external1.basePoint1;
				}
				if (cutPoint2.equals(knotPoint2.match2)) {
					knotPoint2.match2 = external2;
					knotPoint2.match2endpoint = external2.basePoint2;
				} else {
					knotPoint2.match1 = external2;
					knotPoint2.match1endpoint = external2.basePoint1;
				}
				if (!cutPoint1.equals(cutPoint2)
						&& !(cutPoint1.equals(knotPoint2) && cutPoint2.equals(knotPoint1))) {
					cutPoint1.match2 = cutPoint2;
					cutPoint1.match2endpoint = cutPoint2.basePoint2;
					cutPoint2.match2 = cutPoint1;
					cutPoint2.match2endpoint = cutPoint1.basePoint2;
				}
				System.out.println(knotPoint1.fullString());
				System.out.println(knotPoint2.fullString());
				System.out.println(cutPoint1.fullString());
				System.out.println(cutPoint2.fullString());
				unvisited.remove(vp);
				VirtualPoint addPoint = knotPoint2;
				if (prevPoint.equals(external1)) {
					addPoint = knotPoint1;
				}
				for (int j = 0; j < knot.knotPoints.size(); j++) {
					System.out.println("adding: " + addPoint);
					unvisited.add(i + j, addPoint);
					if (prevPoint.equals(addPoint.match2)) {
						prevPoint = addPoint;
						addPoint = addPoint.match1;
					} else {
						prevPoint = addPoint;
						addPoint = addPoint.match2;
					}
				}
				System.out.println(unvisited);
				i = i - 1;
			}

			prevPoint = vp;
		}
		for(VirtualPoint p : unvisited){
			result.add(((Point)p).p);
		}
		return result;
	}

	public Shell solveBetweenEndpoints(PointND first, PointND last, Shell A, DistanceMatrix d) {
		PointSet ps = new PointSet();
		
		
		assert(!first.equals(last));

		ps.add(first);
		if(!first.equals(last)) {
			ps.add(last);
		}
		ps.addAll(A);
		DistanceMatrix d1 = new DistanceMatrix(ps, d);
		PointND dummy = d1.addDummyNode(first, last);
		ps.add(dummy);
		Shell answer = new Shell();
		answer.add(first);
		answer.addAll(A.copyShallow());
		answer.add(last);
		answer.add(dummy);
		Shell result = tspSolve(answer, d1);

		assert(d1.getZero() != 0);
		assert(d1.getMaxDist()/2 <= d1.getZero()): "Zero: "+ d1.getZero() + " MaxDist: " + d1.getMaxDist();
				
		System.out.println(result);
		ps.remove(dummy);
		result = result.removeRotate(ps);
		if(!result.get(0).equals(first)) {
			result = result.reverse();
		}
	
		return result;

	}

	public Shell() {
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
	public void drawShell(JComponent frame, Graphics2D g2, boolean drawChildren, Color c, PointSet ps) {
		if (c == null) {
			Random colorSeed = new Random();
			Main.drawPath(frame, g2, toPath(this),
					new Color(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat()), ps,
					true, false, false);
		} else {
			Main.drawPath(frame, g2, toPath(this), c, this.toPointSet(), true, false, false);
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
		assert (!this.contains(e));
		super.add(e);
		return true;

	}

	@Override
	public boolean addAll(Collection<? extends PointND> c) {
		for (PointND p : c) {
			// assert(!this.contains(p)): this.toString() + " " + c.toString();
		}
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
