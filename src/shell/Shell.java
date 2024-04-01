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

		VirtualPoint getOther(VirtualPoint vp) {
			if (vp.equals(first)) {
				return last;
			}
			if (vp.equals(last)) {
				return first;
			}
			return null;
		}

		boolean contains(VirtualPoint vp) {
			return first.equals(vp) || last.equals(vp);
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

		@Override
		public boolean equals(Object obj) {
			if(obj == null){
				return false;
			}
			if (obj.getClass() != Segment.class) {
				return false;
			} else {
				Segment s2 = (Segment) obj;

				return (this.first.id == s2.first.id && this.last.id == s2.last.id)
						|| (this.first.id == s2.last.id && this.last.id == s2.first.id);
			}
		}
	}

	abstract class VirtualPoint {
		int numMatches;
		Point match1endpoint;
		VirtualPoint match1;
		Point basePoint1;
		Segment s1;
		Point match2endpoint;
		VirtualPoint match2;
		Point basePoint2;
		Segment s2;
		int id;
		boolean isKnot;
		VirtualPoint group;
		VirtualPoint topGroup;

		public Point getPointer(int idx) {
			throw new UnsupportedOperationException("Unimplemented method 'getPointer'");
		}

		public Segment getClosestSegment(VirtualPoint vp) {
			throw new UnsupportedOperationException("Unimplemented method 'getPointer'");
		}

		public Point getNearestBasePoint(VirtualPoint vp) {
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
			topGroup = this;
			basePoint1 = this;
			basePoint2 = this;
		}

		public Point getPointer(int idx) {
			ArrayList<Segment> seenGroups = new ArrayList<Segment>();
			int count = idx;
			for (int i = 0; i < sortedSegments.size(); i++) {
				Segment s = sortedSegments.get(i);
				VirtualPoint basePoint = s.getOther(this);
				VirtualPoint vp = basePoint;
				if (vp.group != null) {
					vp = vp.group;
				}
				Segment potentialSegment = new Segment(basePoint, this, 0);
				System.out.println("CheckPointer: vp: " + vp + " seengroup.contains(potentialSegment): "
						+ seenGroups.contains(potentialSegment) + " basepoint: " + basePoint + " topGroupnumMatches: "
						+ vp.topGroup.numMatches + " topGroup: " + vp.topGroup);
				//
				if ((vp.topGroup.numMatches != 2 && !seenGroups.contains(potentialSegment)) || potentialSegment.equals(s1) || potentialSegment.equals(s2)) {
					System.out.println("match!");
					count--;
					if (count == 0) {
						return (Point) basePoint;
					}
					seenGroups.add(potentialSegment);
				}
			}
			return null;
		}

		public Segment getClosestSegment(VirtualPoint vp) {
			for (int i = 0; i < sortedSegments.size(); i++) {
				Segment s = sortedSegments.get(i);
				if(vp.isKnot){
					Knot knot = (Knot)vp;
					if(s.getKnotPoint(knot.knotPointsFlattened) != null){
						return s;
					}
				}else{
					if (s.contains(vp)) {
						return s;
					}
				}
			}
			assert(false);
			return null;
		}

		public Point getNearestBasePoint(VirtualPoint vp) {
			assert(basePoint1 != null);
			return basePoint1;
		}

		@Override
		public String toString() {
			return "" + this.p.getID();
		}

		public String fullString() {
			return "" + this.p.getID()
					+ " match1: " + (match1 == null ? " none " : "" + match1)
					+ " match1endpoint: " + (match1endpoint == null ? " none " : "" + match1endpoint.id)
					+ " basepoint1: " + (basePoint1 == null ? " none " : "" + basePoint1.id)
					+ " match2: " + (match2 == null ? " none " : "" + match2)
					+ " match2endpoint: " + (match2endpoint == null ? " none " : "" + match2endpoint.id)
					+ " basepoint2: " + (basePoint2 == null ? " none " : "" + basePoint2.id);
		}
	}

	class Knot extends VirtualPoint {
		public int size;
		public ArrayList<VirtualPoint> knotPoints; // [ vp1, vp2, ... vpm];
		public ArrayList<VirtualPoint> knotPointsFlattened;
		public HashMap<Integer, VirtualPoint> pointToInternalKnot;
		public ArrayList<Segment> externalKnotSegments; // [[s1, ..., sn-1], [s1, ..., sn-1], ... m]; sorted and remove
														// vp1, vp2, ... vpm

		public Knot(ArrayList<VirtualPoint> knotPoints) {
			this.knotPoints = knotPoints;
			isKnot = true;
			this.topGroup = this;
			size = knotPoints.size();
			knotPointsFlattened = new ArrayList<VirtualPoint>();
			pointToInternalKnot = new HashMap<>();
			for (VirtualPoint vp : knotPoints) {
				if (vp.isKnot) {
					Knot knot = ((Knot) vp);
					for (VirtualPoint p : knot.knotPointsFlattened) {
						knotPointsFlattened.add(p);
						pointToInternalKnot.put(p.id, knot);
					}
				} else {
					pointToInternalKnot.put(vp.id, vp);
					knotPointsFlattened.add(vp);
				}
			}
			int numPoints = unvisited.size() + visited.size();

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
				vp.topGroup = this;
			}
			for (VirtualPoint p : knotPointsFlattened) {
				p.topGroup = this;
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
			ArrayList<Segment> seenGroups = new ArrayList<Segment>();
			for (int i = 0; i < externalKnotSegments.size(); i++) {
				Segment s = externalKnotSegments.get(i);
				VirtualPoint knotPoint = s.getKnotPoint(knotPointsFlattened);
				VirtualPoint basePoint = s.getOther(knotPoint);
				VirtualPoint vp = basePoint.group;
				if (vp.group != null) {
					vp = vp.group;
				}
				Segment potentialSegment = new Segment(basePoint, knotPoint, 0);
				System.out.println(potentialSegment);
				System.out.println(vp);
				System.out.println(vp.numMatches);
				if ((vp.topGroup.numMatches != 2 && !seenGroups.contains(potentialSegment)) || potentialSegment.equals(s1) || potentialSegment.equals(s2)) {
					count--;
					if (count == 0) {
						return (Point) basePoint;
					}
					seenGroups.add(potentialSegment);
				}
			}
			return null;
		}

		public Segment getClosestSegment(VirtualPoint vp) {
			for (int i = 0; i < externalKnotSegments.size(); i++) {
				Segment s = externalKnotSegments.get(i);
				if(vp.isKnot){
					Knot knot = (Knot)vp;
					if(s.getKnotPoint(knot.knotPointsFlattened) != null){
						return s;
					}
				}else{
					if (s.contains(vp)) {
						return s;
					}
				}
			}
			assert(false);
			return null;
		}

		public Point getNearestBasePoint(VirtualPoint vp) {
			for (int i = 0; i < externalKnotSegments.size(); i++) {
				Segment s = externalKnotSegments.get(i);
				if(vp.isKnot){
					Knot knot = (Knot)vp;
					VirtualPoint p = s.getKnotPoint(knot.knotPointsFlattened);
					if( p != null){
						return (Point)s.getOther(p);
					}
				}else{
					if (s.contains(vp)) {
						return (Point)s.getOther(vp);
					}
				}
			}
			assert(false);
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
					+ " match1: " + (match1 == null ? " none " : "" + match1)
					+ " match1endpoint: " + (match1endpoint == null ? " none " : "" + match1endpoint.id)
					+ " basepoint1: " + (basePoint1 == null ? " none " : "" + basePoint1.id)
					+ " match2: " + (match2 == null ? " none " : "" + match2)
					+ " match2endpoint: " + (match2endpoint == null ? " none " : "" + match2endpoint.id)
					+ " basepoint2: " + (basePoint2 == null ? " none " : "" + basePoint2.id);
		}
	}

	public Pair<Knot, ArrayList<VirtualPoint>> createKnots(VirtualPoint startPoint) {
		int recurseCount = 0;
		ArrayList<VirtualPoint> runList = new ArrayList<>();
		System.out.println("startPoint: " + startPoint);
		System.out.println("runList: " + runList);
		VirtualPoint mainPoint = startPoint;
		boolean knotFound = false;
		while (!knotFound) {
			System.out.println("Main Point is now:" + mainPoint);
			System.out.println("runList:" + runList);
			System.out.println("visited:" + visited);
			if (mainPoint.numMatches > 2) {
				float zero = 1 / 0;
			}
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
			Point pointer11 = pointer1.topGroup.getPointer(1);
			Point pointer12 = pointer1.topGroup.getPointer(2);
			System.out.println("Point " + mainPoint + " points to: " + pointer1);
			Point pointer2 = mainPoint.getPointer(2);
			System.out.println("Point " + mainPoint + " points to: " + pointer2);
			Point pointer21 = pointer2.topGroup.getPointer(1);
			Point pointer22 = pointer2.topGroup.getPointer(2);
			VirtualPoint vp1 = pointer1.topGroup;
			VirtualPoint vp11 = pointer11.topGroup;
			VirtualPoint vp12 = pointer12.topGroup;
			VirtualPoint vp2 = pointer2.topGroup;
			VirtualPoint vp21 = pointer21.topGroup;
			VirtualPoint vp22 = pointer22.topGroup;
			Point matchEndPoint = null;
			Point matchBasePoint = null;
			VirtualPoint matchPoint = null;
			Pair<VirtualPoint, Point> matchPair = null;

			Segment potentialSegment11 = new Segment(pointer1, pointer11, 0);
			Segment potentialSegment12 = new Segment(pointer1, pointer12, 0);
			Segment potentialSegment21 = new Segment(pointer2, pointer21, 0);
			Segment potentialSegment22 = new Segment(pointer2, pointer22, 0);
			Segment matchSegment = null;
			System.out.println(mainPoint.s1);
			System.out.println(potentialSegment11);
			System.out.println(potentialSegment12);
			System.out.println("Point " + mainPoint + " points to: " + vp1 + "(" + pointer1
					+ ") " + " and " + vp2 + "("
					+ pointer2 + ") ");
			System.out.println(mainPoint.equals(vp11));
			System.out.println("vp11: " + vp11);
			if (mainPoint.equals(vp11) && (mainPoint.numMatches == 0 || !mainPoint.s1.equals(potentialSegment11))) {
				matchPoint = vp1;
				matchEndPoint = pointer1;
				matchBasePoint = pointer11;
				matchSegment = potentialSegment11;
			} else if (mainPoint.equals(vp12)
					&& (mainPoint.numMatches == 0 || !mainPoint.s1.equals(potentialSegment12))) {
				matchPoint = vp1;
				matchEndPoint = pointer1;
				matchBasePoint = pointer12;
				matchSegment = potentialSegment12;
			} else if ((mainPoint.equals(vp21))
					&& (mainPoint.numMatches == 0 || !mainPoint.s1.equals(potentialSegment21))) {
				matchPoint = vp2;
				matchEndPoint = pointer2;
				matchBasePoint = pointer21;
				matchSegment = potentialSegment21;
			} else if (mainPoint.equals(vp22)
					&& (mainPoint.numMatches == 0 || !mainPoint.s1.equals(potentialSegment22))) {
				matchPoint = vp2;
				matchEndPoint = pointer2;
				matchBasePoint = pointer22;
				matchSegment = potentialSegment22;
			}
			if (matchPoint != null) {
				recurseCount = 0;
				System.out.println("Point " + matchPoint + " points back" + " | basepoint: " + matchBasePoint
						+ " endpoint: " + matchEndPoint);
				if (mainPoint.numMatches == 0) {
					mainPoint.match1 = matchPoint;
					mainPoint.match1endpoint = matchEndPoint;
					mainPoint.basePoint1 = matchBasePoint;
					mainPoint.s1 = matchSegment;

				} else {
					double d1 = distanceMatrix.getDistance(mainPoint.basePoint1.p, mainPoint.match1endpoint.p);
					double d2 = distanceMatrix.getDistance(matchEndPoint.p, matchBasePoint.p);
					if (d1 > d2) {
						mainPoint.match2 = mainPoint.match1;
						mainPoint.match2endpoint = mainPoint.match1endpoint;
						mainPoint.basePoint2 = mainPoint.basePoint1;
						mainPoint.s2 = mainPoint.s1;
						mainPoint.match1 = matchPoint;
						mainPoint.match1endpoint = matchEndPoint;
						mainPoint.basePoint1 = matchBasePoint;
						mainPoint.s1 = matchSegment;
					} else {
						mainPoint.match2 = matchPoint;
						mainPoint.match2endpoint = matchEndPoint;
						mainPoint.basePoint2 = matchBasePoint;
						mainPoint.s2 = matchSegment;
					}
				}
				if (matchPoint.numMatches == 0) {
					matchPoint.match1 = mainPoint;
					matchPoint.basePoint1 = matchEndPoint;
					matchPoint.match1endpoint = matchBasePoint;
					matchPoint.s1 = matchSegment;
				} else {
					double d1 = distanceMatrix.getDistance(matchPoint.basePoint1.p, matchPoint.match1endpoint.p);
					double d2 = distanceMatrix.getDistance(matchEndPoint.p, matchBasePoint.p);
					if (d1 > d2) {
						matchPoint.match2 = matchPoint.match1;
						matchPoint.match2endpoint = matchPoint.match1endpoint;
						matchPoint.basePoint2 = matchPoint.basePoint1;
						matchPoint.s2 = matchPoint.s1;
						matchPoint.match1 = mainPoint;
						matchPoint.basePoint1 = matchEndPoint;
						matchPoint.match1endpoint = matchBasePoint;
						matchPoint.s1 = matchSegment;
					} else {
						matchPoint.match2 = mainPoint;
						matchPoint.basePoint2 = matchEndPoint;
						matchPoint.match2endpoint = matchBasePoint;
						matchPoint.s2 = matchSegment;
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
				recurseCount++;
				System.out.println("nothing points back so recurse");
				if (recurseCount > 4) {
					float zero = 1 / 0;
				}
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
		pointMap = new HashMap<>();
		unvisited = new ArrayList<VirtualPoint>();
		int numPoints = distanceMatrix.size();
		System.out.println(numPoints);
		// create all of the points
		for (int i = 0; i < numPoints; i++) {
			Point p = new Point(distanceMatrix.getPoints().get(i));
			pointMap.put(i, p);
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

		if (unvisited.size() == 1) {
			System.out.println("one Knot, unwrapping");
			VirtualPoint gp1 = unvisited.get(0);
			if (gp1.isKnot) {
				unvisited = ((Knot) gp1).knotPoints;
			}
		}

		if (unvisited.size() == 2) {
			System.out.println("two Knots, merging");
			// need to loop through each segment in each knot and see which is most
			// beneficial
			// (current minus new)
			// to cut
			VirtualPoint gp1 = unvisited.get(0);

			VirtualPoint gp2 = unvisited.get(1);

			Point pointer1 = gp1.getPointer(1);
			// System.out.println(((Knot) gp1).externalKnotSegments);
			VirtualPoint cutPoint1 = pointer1.match2;
			Point pointer2 = pointer1.group.getPointer(1);
			VirtualPoint cutPoint2 = pointer2.match2;
			ArrayList<VirtualPoint> newList = new ArrayList<>();
			if (gp1.isKnot && gp2.isKnot) {
				System.out.println("Both are knots, find the two cut segments and join across");
				Knot knot1 = (Knot) gp1;
				Knot knot2 = (Knot) gp2;
				System.out.println("Knot1: " + knot1);
				System.out.println("Knot2: " + knot2);
				System.out.println(pointer1);
				System.out.println(pointer2);
				System.out.println(cutPoint1);
				System.out.println(cutPoint2);
				// need to make a new list with pointer 1 and pointer 2 in the midd;le and
				// their cutpoints on the ends
				// Should look like
				VirtualPoint addPoint = pointer2.match2;
				VirtualPoint prevPoint = pointer2;
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
				prevPoint = pointer1.match2;
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

				System.out.println(cutPoint1.fullString());
				System.out.println(cutPoint2.fullString());
				System.out.println(pointer1.fullString());
				System.out.println(pointer2.fullString());
				System.out.println(pointMap.get(0).fullString());
				if (cutPoint2.match1.equals(pointer2)) {
					cutPoint2.match1 = cutPoint2.match2;
					cutPoint2.basePoint1 = cutPoint2.basePoint2;
					cutPoint2.match1endpoint = cutPoint2.match2endpoint;
					cutPoint2.match2 = cutPoint1;
					cutPoint2.match2endpoint = cutPoint1.basePoint2;
				} else {
					cutPoint2.match2 = cutPoint1;
					cutPoint2.match2endpoint = cutPoint1.basePoint2;
				}
				if (cutPoint1.match1.equals(pointer1)) {
					cutPoint1.match1 = cutPoint1.match2;
					cutPoint1.basePoint1 = cutPoint1.basePoint2;
					cutPoint1.match1endpoint = cutPoint1.match2endpoint;
					cutPoint1.match2 = cutPoint2;
					cutPoint1.match2endpoint = cutPoint2.basePoint2;
				} else {
					cutPoint1.match2 = cutPoint2;
					cutPoint1.match2endpoint = cutPoint2.basePoint2;
				}
				pointer1.match2 = pointer2;
				pointer2.match2 = pointer1;
				pointer1.match2endpoint = pointer2.basePoint2;
				pointer2.match2endpoint = pointer1.basePoint2;

				System.out.println(cutPoint1.fullString());
				System.out.println(cutPoint2.fullString());
				System.out.println(pointer1.fullString());
				System.out.println(pointer2.fullString());

				numKnots -= 2;
			} else {
				System.out.println("Both are not knots, something is wrong ");
				System.out.println("gp1:" + gp1);
				System.out.println("gp2:" + gp2);
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
				if (!knotPoint1.group.equals(knot)) {
					knotPoint1 = knot.pointToInternalKnot.get(knotPoint1.id);
				}
				VirtualPoint cutPoint1 = knotPoint1.match2endpoint;
				if (!cutPoint1.group.equals(knot)) {
					cutPoint1 = knot.pointToInternalKnot.get(cutPoint1.id);
				}
				VirtualPoint external1 = knot.match1;
				VirtualPoint external2 = knot.match2;
				VirtualPoint knotPoint2 = knot.basePoint2;
				if (!knotPoint2.group.equals(knot)) {
					knotPoint2 = knot.pointToInternalKnot.get(knotPoint2.id);
				}
				VirtualPoint cutPoint2 = knotPoint2.match2endpoint;
				if (!cutPoint2.group.equals(knot)) {
					cutPoint2 = knot.pointToInternalKnot.get(cutPoint2.id);
				}

				System.out.println(knotPoint1.fullString());
				System.out.println(knotPoint2.fullString());
				System.out.println(cutPoint1.fullString());
				System.out.println(cutPoint2.fullString());
				System.out.println("external1: " + external1.fullString());
				System.out.println("external2: " + external2.fullString());
				if (knotPoint2.equals(knotPoint1)) {
					System.out.println("!!!!Both externals : ( " + external1 + " " + external2
							+ " ) point to the same VirtualPoint: " + knotPoint1);
					Segment s11 = knotPoint1.getClosestSegment(external1);
					Segment s12 = cutPoint1.getClosestSegment(external2);
					Segment s21 = knotPoint1.getClosestSegment(external2);
					Segment s22 = cutPoint1.getClosestSegment(external1);
					if (s11.distance + s12.distance < s21.distance + s22.distance) {
						System.out.println("s11 + s12 (" + s11 + ", " + s12 +
						 ") < s21 + s22 (" + s21 + ", " + s22 + ")");
						knotPoint1.match2 = external1;	
						VirtualPoint p11 = external1;
						if(external1.isKnot){
							p11 = s11.getKnotPoint(((Knot)external1).knotPointsFlattened);
						}
						knotPoint1.basePoint2 = (Point) s11.getOther(p11);
						if (external1.match2.equals(knot)) {
							external1.match2 = knotPoint1;
							knotPoint1.match2endpoint = external1.basePoint2;
							external1.match2endpoint = knotPoint1.basePoint2;
						} else {
							external1.match1 = knotPoint1;
							knotPoint1.match2endpoint = external1.basePoint1;
							external1.match1endpoint = knotPoint1.basePoint2;
						}
						cutPoint1.match2 = external2;
						VirtualPoint p12 = external2;
						if(external2.isKnot){
							p12 = s12.getKnotPoint(((Knot)external2).knotPointsFlattened);
						}
						cutPoint1.basePoint2 = (Point) s12.getOther(p12);
						if (external2.match2.equals(knot)) {
							external2.match2 = cutPoint1;
							cutPoint1.match2endpoint = external2.basePoint2;
							external2.match2endpoint = cutPoint1.basePoint2;
						} else {
							external2.match1 = cutPoint1;
							cutPoint1.match2endpoint = external2.basePoint1;
							external2.match1endpoint = cutPoint1.basePoint2;
						}
					} else {
						System.out.println("s11 + s12 (" + s11 + ", " + s12 + 
						") > s21 + s22 (" + s21 + ", " + s22 + ")");
						knotPoint1.match2 = external2;
						VirtualPoint p21 = external2;
						if(external2.isKnot){
							p21 = s21.getKnotPoint(((Knot)external2).knotPointsFlattened);
						}
						knotPoint1.basePoint2 = (Point) s21.getOther(p21);
						if (external2.match2.equals(knot)) {
							external2.match2 = knotPoint1;
							knotPoint1.match2endpoint = external2.basePoint2;
							external2.match2endpoint = knotPoint1.basePoint2;
						} else {
							external2.match1 = knotPoint1;
							knotPoint1.match2endpoint = external2.basePoint1;
							external2.match1endpoint = knotPoint1.basePoint2;
						}
						cutPoint1.match2 = external1;
						VirtualPoint p22 = external1;
						if(external1.isKnot){
							p22 = s22.getKnotPoint(((Knot)external1).knotPointsFlattened);
						}
						cutPoint1.basePoint2 = (Point) s22.getOther(p22);
						System.out.println("external1:" + external1.fullString());
						System.out.println("cutPoint1:" + cutPoint1.fullString());
						if (external1.match2.equals(knot)) {
							external1.match2 = cutPoint1;
							cutPoint1.match2endpoint = external1.basePoint2;
							external1.match2endpoint = cutPoint1.basePoint2;
						} else {
							external1.match1 = cutPoint1;
							cutPoint1.match2endpoint = external1.basePoint1;
							external1.match1endpoint = cutPoint1.basePoint2;
						}
						System.out.println("external1:" + external1.fullString());
					}
				} else {
					if (!cutPoint2.group.equals(knot)) {
						cutPoint2 = cutPoint2.group;
					}
					if (knot.knotPoints.size() == 3) {
						cutPoint1 = knotPoint2;
						cutPoint2 = knotPoint1;
					}
					System.out.println("knotPoint1: " + knotPoint1);
					System.out.println("cutPoint1: " + cutPoint1);
					System.out.println("external1: " + external1);
					System.out.println("knotPoint2: " + knotPoint2);
					System.out.println("cutPoint2: " + cutPoint2);
					System.out.println("external2: " + external2);
					System.out.println("prevPoint: " + prevPoint);
					// TODO: if we have a 3 knot with two different cut segments, we need to test
					// which is better
					Point nearestbp1 = knotPoint1.getNearestBasePoint(external1);
					Point nearestbp2 = knotPoint2.getNearestBasePoint(external2);
					if (cutPoint1.equals(knotPoint1.match2)) {
						knotPoint1.match2 = external1;
						knotPoint1.basePoint2 = nearestbp1;
						if (external1.match1.equals(knot)) {
							external1.match1 = knotPoint1;
							external1.match1endpoint = nearestbp1;
							knotPoint1.match2endpoint = external1.basePoint1;
						} else {
							external1.match2 = knotPoint1;
							external1.match2endpoint = nearestbp1;
							knotPoint1.match2endpoint = external1.basePoint2;
						}

					} else {
						knotPoint1.match1 = external1;
						knotPoint1.basePoint1 = nearestbp1;
						if (external1.match1.equals(knot)) {
							external1.match1 = knotPoint1;
							external1.match1endpoint = nearestbp1;
							knotPoint1.match1endpoint = external1.basePoint1;
						} else {
							external1.match2 = knotPoint1;
							external1.match2endpoint = nearestbp1;
							knotPoint1.match1endpoint = external1.basePoint2;
						}
					}
					if (cutPoint2.equals(knotPoint2.match2)) {
						knotPoint2.match2 = external2;
						knotPoint2.basePoint2 = nearestbp2;
						if (external2.match1.equals(knot)) {
							external2.match1 = knotPoint2;
							external2.match1endpoint = nearestbp2;
							knotPoint2.match2endpoint = external2.basePoint1;
						} else {
							external2.match2 = knotPoint2;
							external2.match2endpoint = nearestbp2;
							knotPoint2.match2endpoint = external2.basePoint2;
						}
					} else {
						knotPoint2.match1 = external2;
						knotPoint2.match1endpoint = external2.basePoint1;
						knotPoint2.basePoint1 = nearestbp2;
						if (external2.match1.equals(knot)) {
							external2.match1 = knotPoint2;
							external2.match1endpoint = nearestbp2;
							knotPoint2.match1endpoint = external2.basePoint1;
						} else {
							external2.match2 = knotPoint2;
							external2.match2endpoint = nearestbp2;
							knotPoint2.match1endpoint = external2.basePoint2;
						}
					}
					if (!cutPoint1.equals(cutPoint2)
							&& !(cutPoint1.equals(knotPoint2) && cutPoint2.equals(knotPoint1))) {
						System.out.println("there are two different cut segments");
						cutPoint1.match2 = cutPoint2;
						cutPoint1.match2endpoint = cutPoint2.basePoint2;
						cutPoint2.match2 = cutPoint1;
						cutPoint2.match2endpoint = cutPoint1.basePoint2;
					} else {
						System.out.println("Ruh ORh");
					}
				}
				System.out.println("knotPoint1 final: " + knotPoint1.fullString());
				System.out.println("knotPoint2 final: " + knotPoint2.fullString());
				System.out.println("cutPoint1 final: " + cutPoint1.fullString());
				System.out.println("cutPoint2 final: " + cutPoint2.fullString());
				System.out.println("external1 final: " + external1.fullString());
				System.out.println("external2 final: " + external2.fullString());
				unvisited.remove(vp);
				System.out.println("prevPoint: " + prevPoint.fullString());
				VirtualPoint addPoint = knotPoint2;
				if (knotPoint1.match1.equals(prevPoint) || knotPoint1.match2.equals(prevPoint)) {
					addPoint = knotPoint1;
				} else if (cutPoint1.match1.equals(prevPoint) || cutPoint1.match2.equals(prevPoint)) {
					addPoint = cutPoint1;
				}
				VirtualPoint prevPointTemp = prevPoint;
				for (int j = 0; j < knot.knotPoints.size(); j++) {
					System.out.println("adding: " + addPoint);
					unvisited.add(i + j, addPoint);
					if (prevPointTemp.equals(addPoint.match2)) {
						prevPointTemp = addPoint;
						addPoint = addPoint.match1;
					} else {
						prevPointTemp = addPoint;
						addPoint = addPoint.match2;
					}
				}
				System.out.println(unvisited);
				i = i - 1;
			}
			if (!vp.isKnot) {
				prevPoint = vp;
			}
		}
		for (VirtualPoint p : unvisited) {
			result.add(((Point) p).p);
		}
		return result;
	}

	public Shell solveBetweenEndpoints(PointND first, PointND last, Shell A, DistanceMatrix d) {
		PointSet ps = new PointSet();

		assert (!first.equals(last));

		ps.add(first);
		if (!first.equals(last)) {
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
		System.out.println(answer.size());
		Shell result = tspSolve(answer, d1);

		assert (d1.getZero() != 0);
		assert (d1.getMaxDist() / 2 <= d1.getZero()) : "Zero: " + d1.getZero() + " MaxDist: " + d1.getMaxDist();

		System.out.println(result);
		ps.remove(dummy);
		result = result.removeRotate(ps);
		if (!result.get(0).equals(first)) {
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
