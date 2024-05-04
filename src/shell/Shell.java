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

		VirtualPoint getOtherKnot(VirtualPoint vp) {
			if (vp.isKnot) {
				Knot knot = (Knot) vp;
				VirtualPoint p = this.getKnotPoint(knot.knotPointsFlattened);
				return this.getOther(p);
			} else if (vp.isRun) {
				Run knot = (Run) vp;
				VirtualPoint p = this.getKnotPoint(knot.knotPointsFlattened);
				return this.getOther(p);
			} else {
				return this.getOther(vp);
			}
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
			if (obj == null) {
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
		ArrayList<VirtualPoint> externalVirtualPoints;
		ArrayList<VirtualPoint> knotPointsFlattened;
		ArrayList<Segment> sortedSegments;
		Segment s2;
		int id;
		boolean isKnot;
		boolean isRun;
		VirtualPoint group;
		VirtualPoint topGroup;
		VirtualPoint topGroupVirtualPoint;

		public Segment getPointer(int idx) {
			int count = idx;
			ArrayList<Segment> seenGroups = new ArrayList<Segment>();
			ArrayList<VirtualPoint> seenPoints = new ArrayList<VirtualPoint>();
			int matchedSegs = 0;
			for (int i = 0; i < sortedSegments.size(); i++) {
				Segment s = sortedSegments.get(i);
				VirtualPoint knotPoint = s.getKnotPoint(knotPointsFlattened);
				VirtualPoint basePoint = s.getOther(knotPoint);
				VirtualPoint vp = basePoint.group;
				if (vp.group != null) {
					vp = vp.group;
				}
				Segment potentialSegment = new Segment(basePoint, knotPoint, 0);
				if ((!vp.isRun || ((Run) vp).endpoint1.contains(basePoint) || ((Run) vp).endpoint2.contains(basePoint))
						&& (!seenGroups.contains(potentialSegment)) && (!seenPoints.contains(knotPoint))
						&& (!seenPoints.contains(basePoint))
						|| potentialSegment.equals(s1) || potentialSegment.equals(s2)) {
					count--;
					matchedSegs++;
					if (count == 0) {
						return s;
					}
					seenGroups.add(potentialSegment);
					if (this.isKnot || this.isRun) {
						seenPoints.add(knotPoint);
					}
					if (vp.isKnot || vp.isRun) {
						seenPoints.add(basePoint);
					}
				}
			}
			return null;
		}

		public Segment getFirstUnmatched() {

			for (Segment s : sortedSegments) {
				VirtualPoint other = s.getOtherKnot(this);
				if ((match1 == null || !s1.contains(other)) && (match2 == null || !s2.contains(other))) {
					return s;
				}
			}
			return s1;
		}

		public boolean shouldJoinKnot(Knot k) {
			System.out.println(k.fullString());
			if (k.match1 == null || !k.match1.contains(this)) {
				System.out.println("not first match");
				return false;
			}
			int desiredCount = 2;
			for (Segment s : this.sortedSegments) {
				VirtualPoint vp = s.getOtherKnot(this);
				if (!k.contains(vp)) {
					System.out.println("broke on this segment: " + s + " desired count: " + desiredCount
							+ " org count: " + k.knotPoints.size() + " sorted segments: " + this.sortedSegments);
					return false;
				}
				desiredCount--;
				if (desiredCount == 0) {
					return true;
				}
			}
			return true;
		}

		public Segment getClosestSegment(VirtualPoint vp, Segment excludeSegment) {
			for (int i = 0; i < sortedSegments.size(); i++) {
				Segment s = sortedSegments.get(i);
				if (vp.isKnot) {
					Knot knot = (Knot) vp;
					if (s.getKnotPoint(knot.knotPointsFlattened) != null) {
						return s;
					}
				}
				if (vp.isRun) {
					Run run = (Run) vp;
					if (s.getKnotPoint(run.externalVirtualPoints) != null) {
						return s;
					}
				} else {
					if (s.contains(vp)) {
						return s;
					}
				}
			}
			assert (false);
			return null;
		}

		public Point getNearestBasePoint(VirtualPoint vp) {
			throw new UnsupportedOperationException("Unimplemented method 'getPointer'");
		}

		public String fullString() {
			// TODO Auto-generated method stub
			throw new UnsupportedOperationException("Unimplemented method 'fullString'");
		}

		public boolean contains(VirtualPoint vp) {
			throw new UnsupportedOperationException("Unimplemented method 'contains'");
		}

		public int getHeight() {
			if (this.isKnot) {
				Knot k = (Knot) this;
				for (VirtualPoint vp : k.knotPoints) {
					if (vp.isKnot) {
						return 2;
					}
				}
			} else {
				return 1;
			}
			return 1;
		}

		public void setMatch1(VirtualPoint matchPoint, Point matchEndPoint, Point matchBasePoint,
				Segment matchSegment) {
			match1 = matchPoint;
			match1endpoint = matchEndPoint;
			basePoint1 = matchBasePoint;
			s1 = matchSegment;
			if (this.contains(match1endpoint)) {
				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
			if (s1 == null && s2 != null) {
				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
			if (match1endpoint != null && match2endpoint != null && match1endpoint.equals(match2endpoint)) {
				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
		}

		public void setMatch1(VirtualPoint matchPoint) {

			match1 = matchPoint;
			if (matchPoint.match1.equals(this)) {
				match1endpoint = matchPoint.basePoint1;
				basePoint1 = matchPoint.match1endpoint;
				s1 = matchPoint.s1;
			} else if (matchPoint.match2 != null && matchPoint.match2.equals(this)) {
				match1endpoint = matchPoint.basePoint2;
				basePoint1 = matchPoint.match2endpoint;
				s1 = matchPoint.s2;
			}
			if (this.contains(match1endpoint)) {
				float zero = 1 / 0;
			}
			if (s1 == null && s2 != null) {
				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
			if (match1endpoint != null && match2endpoint != null && match1endpoint.equals(match2endpoint)) {

				float zero = 1 / 0;
			}
		}

		public void setMatch2(VirtualPoint matchPoint, Point matchEndPoint, Point matchBasePoint,
				Segment matchSegment) {
			if ((matchSegment != null)) {
				if (s1 == null) {
					match1 = matchPoint;
					match1endpoint = matchEndPoint;
					basePoint1 = matchBasePoint;
					s1 = matchSegment;
					return;
				} else if (s1.distance > matchSegment.distance) {
					match2 = match1;
					match2endpoint = match1endpoint;
					basePoint2 = basePoint1;
					s2 = s1;
					match1 = matchPoint;
					match1endpoint = matchEndPoint;
					basePoint1 = matchBasePoint;
					s1 = matchSegment;
					return;
				}
			}
			match2 = matchPoint;
			match2endpoint = matchEndPoint;
			basePoint2 = matchBasePoint;
			s2 = matchSegment;
			if (s2 != null && s1.distance > s2.distance) {
				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
			if (s1 == null && s2 != null) {
				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
			if (match1endpoint != null && match2endpoint != null && match1endpoint.equals(match2endpoint)
					&& basePoint1.equals(basePoint2)) {
				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
		}

		public void setMatch2(VirtualPoint matchPoint) {

			match2 = matchPoint;
			if (matchPoint.match1.equals(this)) {
				match2endpoint = matchPoint.basePoint1;
				basePoint2 = matchPoint.match1endpoint;
				s2 = matchPoint.s1;
			} else if (matchPoint.match2.equals(this)) {
				match2endpoint = matchPoint.basePoint2;
				basePoint2 = matchPoint.match2endpoint;
				s2 = matchPoint.s2;
			}
			if (s1 != null && s2 != null && s1.distance > s2.distance) {
				this.swap();
			}
			if(s1 == null && s2 != null){
				this.swap();
			}
			if (s1 == null && s2 != null) {
				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
			if (s2 != null && s1.distance > s2.distance) {
				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
			if (match1endpoint != null && match2endpoint != null && match1endpoint.equals(match2endpoint)) {

				float zero = 1 / 0;
			}
		}

		public void setMatch(boolean match1, VirtualPoint matchPoint) {
			if (match1) {
				this.setMatch1(matchPoint);
			} else {
				this.setMatch2(matchPoint);
			}
		}

		public void setMatch(boolean match1, VirtualPoint matchPoint, Point matchEndPoint, Point matchBasePoint,
				Segment matchSegment) {
			if (match1) {
				this.setMatch1(matchPoint, matchEndPoint, matchBasePoint, matchSegment);
			} else {
				this.setMatch2(matchPoint, matchEndPoint, matchBasePoint, matchSegment);
			}
		}

		public void matchAcross2(VirtualPoint matchPoint, Segment matchSegment, Segment otherSegment,
				Segment cutSegment) {

			VirtualPoint p1 = this;
			if (this.isKnot) {
				p1 = matchSegment.getKnotPoint(((Knot) this).knotPointsFlattened);
			}

			System.out.println(p1);
			this.setMatch(
					matchSegment.contains(this.match1endpoint)
							|| (this.match1.equals(this.match2) && otherSegment.contains(this.match2endpoint)),
					matchPoint,
					(Point) matchSegment.getOther(p1),
					(Point) p1,
					matchSegment);
			matchPoint.setMatch(
					cutSegment.contains(matchPoint.match1endpoint) && cutSegment.contains(matchPoint.basePoint1), this);

		}

		public void swapMatch2(VirtualPoint matchPoint, Point matchEndPoint, Point matchBasePoint,
				Segment matchSegment) {
			VirtualPoint tmp = match1;
			Point tmpMe = match1endpoint;
			Point tmpBp = basePoint1;
			Segment tmpSeg = s1;
			match1 = matchPoint;
			match1endpoint = matchEndPoint;
			basePoint1 = matchBasePoint;
			s1 = matchSegment;
			match2 = tmp;
			match2endpoint = tmpMe;
			basePoint2 = tmpBp;
			s2 = tmpSeg;
		}

		public void swap() {
			VirtualPoint tmp = match1;
			Point tmpMe = match1endpoint;
			Point tmpBp = basePoint1;
			Segment tmpSeg = s1;
			match1 = match2;
			match1endpoint = match2endpoint;
			basePoint1 = basePoint2;
			s1 = s2;
			match2 = tmp;
			match2endpoint = tmpMe;
			basePoint2 = tmpBp;
			s2 = tmpSeg;
		}

		public void checkAndSwap2(VirtualPoint matchPoint, Point matchEndPoint, Point matchBasePoint,
				Segment matchSegment) {
			double d1 = distanceMatrix.getDistance(this.basePoint1.p, this.match1endpoint.p);
			double d2 = distanceMatrix.getDistance(matchEndPoint.p, matchBasePoint.p);
			if (d1 > d2) {
				this.swapMatch2(matchPoint, matchEndPoint, matchBasePoint, matchSegment);
			} else {
				this.setMatch2(matchPoint, matchEndPoint, matchBasePoint, matchSegment);
			}
		}

		public void reset() {
			numMatches = 0;
			match1 = null;
			match1endpoint = null;
			basePoint1 = null;
			match2 = null;
			match2endpoint = null;
			basePoint2 = null;
			group = this;
			topGroup = this;
		}

	}

	class Point extends VirtualPoint {
		public PointND p;

		public Point(PointND p) {
			this.p = p;
			this.id = p.getID();
			unvisited.add(this);
			isKnot = false;
			isRun = false;
			group = this;
			topGroup = this;
			topGroupVirtualPoint = this;
			basePoint1 = this;
			basePoint2 = this;
			this.externalVirtualPoints = new ArrayList<>();
			externalVirtualPoints.add(this);
			knotPointsFlattened = new ArrayList<VirtualPoint>();
			knotPointsFlattened.add(this);
			sortedSegments = new ArrayList<Segment>();
		}

		public Point getNearestBasePoint(VirtualPoint vp) {
			assert (basePoint1 != null);
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

		public boolean contains(VirtualPoint vp) {
			if (this.equals(vp)) {
				return true;
			}
			return false;
		}
	}

	int knotmergecount = 0;
	int knotflattencount = 0;
	int runlistmergecount = 0;
	int runmergecount = 0;

	class Knot extends VirtualPoint {
		public int size;
		public ArrayList<VirtualPoint> knotPoints; // [ vp1, vp2, ... vpm];
		public HashMap<Integer, VirtualPoint> pointToInternalKnot;
		// [[s1, ..., sn-1], [s1, ..., sn-1], ... m]; sorted and remove
		// vp1, vp2, ... vpm

		public Knot(ArrayList<VirtualPoint> knotPointsToAdd) {
			if (knotPointsToAdd.get(0).match2 == null
					|| knotPointsToAdd.get(knotPointsToAdd.size() - 1).match2 == null) {
				VirtualPoint vp1 = knotPointsToAdd.get(0);
				VirtualPoint vp2 = knotPointsToAdd.get(knotPointsToAdd.size() - 1);
				Segment s = vp1.getClosestSegment(vp2, null);
				Point bp2 = (Point) s.getOtherKnot(vp1);
				Point bp1 = (Point) s.getOther(bp2);
				vp1.match2 = vp2;
				vp1.basePoint2 = bp1;
				vp1.match2endpoint = bp2;
				vp1.s2 = s;
				vp2.match2 = vp1;
				vp2.basePoint2 = bp2;
				vp2.match2endpoint = bp1;
				vp2.s2 = s;
			}

			for (VirtualPoint vp : knotPointsToAdd) {
				System.out.println(vp.fullString());
			}
			sortedSegments = new ArrayList<>();
			ArrayList<VirtualPoint> flattenRunPoints = flattenRunPoints(knotPointsToAdd, true);
			System.out.println("-------------------------------");
			for (VirtualPoint vp : flattenRunPoints) {
				System.out.println(vp.fullString());
			}
			System.out.println("---------------------------");
			fixRunList(flattenRunPoints, flattenRunPoints.size());
			if (flattenRunPoints.size() != knotPointsToAdd.size()) {
				runlistmergecount++;
				if (runlistmergecount > 20) {
					float zero = 1 / 0;
				}
			}

			this.knotPoints = flattenRunPoints;
			isKnot = true;
			isRun = false;
			this.topGroup = this;
			this.group = this;
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
				} else if (vp.isRun) {
					Run knot = ((Run) vp);
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

			this.externalVirtualPoints = new ArrayList<>();
			externalVirtualPoints.addAll(knotPointsFlattened);
			// store the segment lists of each point contained in the knot, recursive
			sortedSegments = new ArrayList<Segment>();
			for (VirtualPoint vp : knotPoints) {
				if (vp.isKnot) {
					ArrayList<Segment> segments = new ArrayList<Segment>();
					ArrayList<Segment> vpExternal = vp.sortedSegments;
					for (Segment s : vpExternal) {
						if (!(knotPointsFlattened.contains(s.first) && knotPointsFlattened.contains(s.last))) {
							sortedSegments.add(s);
						}
					}
				} else if (vp.isRun) {
					ArrayList<Segment> vpExternal = vp.sortedSegments;
					for (Segment s : vpExternal) {
						if (!(knotPointsFlattened.contains(s.first) && knotPointsFlattened.contains(s.last))) {
							sortedSegments.add(s);
						}
					}
				} else {
					ArrayList<Segment> vpExternal = vp.sortedSegments;
					for (Segment s : vpExternal) {
						if (!(knotPointsFlattened.contains(s.first) && knotPointsFlattened.contains(s.last))) {
							sortedSegments.add(s);
						}
					}
				}
				vp.group = this;
				vp.topGroup = this;
				for (VirtualPoint flat : vp.knotPointsFlattened) {
					flat.topGroupVirtualPoint = vp;
				}
				vp.topGroupVirtualPoint = vp;
			}
			for (VirtualPoint p : knotPointsFlattened) {
				p.topGroup = this;
			}
			sortedSegments.sort(null);
			System.out.println(sortedSegments);
			if (knotmergecount > 10) {
				float zero = 1 / 0;
			}
			this.id = numPoints;
			pointMap.put(id, this);
			unvisited.add(this);
			numKnots++;
		}

		public Point getNearestBasePoint(VirtualPoint vp) {
			for (int i = 0; i < sortedSegments.size(); i++) {
				Segment s = sortedSegments.get(i);
				if (vp.isKnot) {
					Knot knot = (Knot) vp;
					VirtualPoint p = s.getKnotPoint(knot.knotPointsFlattened);
					if (p != null) {
						return (Point) s.getOther(p);
					}
				} else {
					if (s.contains(vp)) {
						return (Point) s.getOther(vp);
					}
				}
			}
			assert (false);
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

		public boolean contains(VirtualPoint vp) {
			if (this.equals(vp)) {
				return true;
			}
			if (knotPointsFlattened.contains(vp)) {
				return true;
			}
			return false;
		}
	}

	// TODO: need to actually measure best orientation?
	public ArrayList<VirtualPoint> flattenRunPoints(ArrayList<VirtualPoint> knotPoints, boolean knot) {
		ArrayList<VirtualPoint> flattenRunPoints = new ArrayList<>();
		boolean twoKnot = knotPoints.size() == 2 && knot;
		if (twoKnot) {
			if (knotPoints.get(0).isRun && knotPoints.get(1).isRun) {
				Run run1 = (Run) knotPoints.get(0);
				Run run2 = (Run) knotPoints.get(1);
				Segment s11 = run1.endpoint1.getClosestSegment(run2.endpoint1, null);
				Segment s12 = run1.endpoint2.getClosestSegment(run2.endpoint2, null);
				Double d1 = s11.distance + s12.distance;
				Segment s21 = run1.endpoint1.getClosestSegment(run2.endpoint2, null);
				Segment s22 = run1.endpoint2.getClosestSegment(run2.endpoint1, null);
				Double d2 = s21.distance + s22.distance;
				System.out.println(s11 + " " + s12 + " " + d1);
				System.out.println(s21 + " " + s22 + " " + d2);
				if (d1 < d2) {
					if (run1.endpoint1.contains(run1.knotPoints.get(0))) {
						flattenRunPoints.addAll(run1.knotPoints);
						if (run2.endpoint1.contains(run2.knotPoints.get(0))) {
							int idx = flattenRunPoints.size();
							for (VirtualPoint vp : run2.knotPoints) {
								flattenRunPoints.add(idx, vp);
							}
						} else {
							flattenRunPoints.addAll(run2.knotPoints);
						}
					} else {
						for (VirtualPoint vp : run1.knotPoints) {
							flattenRunPoints.add(0, vp);
						}
						if (run2.endpoint1.contains(run2.knotPoints.get(0))) {
							int idx = flattenRunPoints.size();
							for (VirtualPoint vp : run2.knotPoints) {
								flattenRunPoints.add(idx, vp);
							}
						} else {
							flattenRunPoints.addAll(run2.knotPoints);
						}
					}
				} else {
					if (run1.endpoint1.contains(run1.knotPoints.get(0))) {
						for (VirtualPoint vp : run1.knotPoints) {
							flattenRunPoints.add(0, vp);
						}
						if (run2.endpoint1.contains(run2.knotPoints.get(0))) {
							int idx = flattenRunPoints.size();
							for (VirtualPoint vp : run2.knotPoints) {
								flattenRunPoints.add(idx, vp);
							}
						} else {
							flattenRunPoints.addAll(run2.knotPoints);
						}
					} else {
						flattenRunPoints.addAll(run1.knotPoints);
						if (run2.endpoint1.contains(run2.knotPoints.get(0))) {
							int idx = flattenRunPoints.size();
							for (VirtualPoint vp : run2.knotPoints) {
								flattenRunPoints.add(idx, vp);
							}
						} else {
							flattenRunPoints.addAll(run2.knotPoints);
						}
					}
				}
				return flattenRunPoints;
			}
		}
		for (int i = 0; i < knotPoints.size(); i++) {
			VirtualPoint vp = knotPoints.get(i);
			if (i + 1 >= knotPoints.size() && !knot) {
				if (vp.isRun) {
					Run run = ((Run) vp);
					if (run.endpoint1.contains(run.basePoint1)) {
						for (VirtualPoint p : run.knotPoints) {
							flattenRunPoints.add(p);
						}
					} else {
						int end = flattenRunPoints.size();
						for (VirtualPoint p : run.knotPoints) {
							flattenRunPoints.add(end, p);
						}
					}
				} else {
					flattenRunPoints.add(vp);
				}
				break;
			}

			VirtualPoint vp2 = null;
			if (i + 1 >= knotPoints.size() && knot) {
				vp2 = knotPoints.get(0);
			} else {
				vp2 = knotPoints.get(i + 1);
			}
			if (vp.isRun) {
				Run run = ((Run) vp);
				if ((run.endpoint1.contains(run.basePoint1)
						&& ((vp2.isRun && ((Run) vp2).endpoint1.contains(run.match1endpoint))
								|| vp2.contains(run.match1endpoint)))
						|| (!twoKnot
								&& (!run.endpoint1.contains(run.basePoint1) && run.endpoint1.contains(run.basePoint2)
										&& ((vp2.isRun && ((Run) vp2).endpoint1.contains(run.match2endpoint))
												|| vp2.contains(run.match2endpoint))))
						|| (twoKnot && i + 1 >= knotPoints.size() && (run.endpoint2.contains(run.basePoint1)
								&& ((vp2.isRun && ((Run) vp2).endpoint1.contains(run.match1endpoint))
										|| vp2.contains(run.match1endpoint))))) {

					int end = flattenRunPoints.size();
					for (VirtualPoint p : run.knotPoints) {
						flattenRunPoints.add(end, p);
					}

				} else {
					for (VirtualPoint p : run.knotPoints) {
						flattenRunPoints.add(p);
					}
				}

			} else {
				flattenRunPoints.add(vp);
			}
		}
		return flattenRunPoints;
	}

	public void fixRunList(ArrayList<VirtualPoint> flattenRunPoints, int end) {

		System.out.println("Fixing run List: " + flattenRunPoints);
		for (int i = 0; i < end; i++) {
			VirtualPoint vp = flattenRunPoints.get(i);
			VirtualPoint vp2 = null;
			System.out.println("BEfore: ");
			System.out.println(vp.fullString());
			if (i < flattenRunPoints.size() - 1) {
				vp2 = flattenRunPoints.get(i + 1);
			} else {
				vp2 = flattenRunPoints.get(0);
			}
			System.out.println(vp2.fullString());
			if (!vp2.group.equals(vp.group) && (vp.group.isRun || vp2.group.isRun)) {

				Segment s = vp.getClosestSegment(vp2, null);
				System.out.println("closest Segment: " + s);
				System.out.println("closest Segment vp: " + vp);
				System.out.println("closest Segment vp2: " + vp2);
				System.out.println("closest Segment vp2: " + vp2.sortedSegments);
				VirtualPoint bp1 = s.getKnotPoint(vp.externalVirtualPoints);
				VirtualPoint bp2 = s.getOther(bp1);
				if (vp.match1 == null || (vp.match1.contains(bp2) && vp.match1.isRun)) {
					vp.match1 = vp2;
					vp.basePoint1 = (Point) bp1;
					vp.match1endpoint = (Point) bp2;
					vp.s1 = s;
				} else if (vp.match2 == null || (vp.match2.contains(bp2) && vp.match2.isRun)) {
					if (s.distance < vp.s1.distance) {
						vp.match2 = vp.match1;
						vp.basePoint2 = vp.basePoint1;
						vp.match2endpoint = vp.match1endpoint;
						vp.s2 = vp.s1;
						vp.match1 = vp2;
						vp.basePoint1 = (Point) bp1;
						vp.match1endpoint = (Point) bp2;
						vp.s1 = s;
					} else {
						vp.match2 = vp2;
						vp.basePoint2 = (Point) bp1;
						vp.match2endpoint = (Point) bp2;
						vp.s2 = s;
					}

				}
				if (vp2.match1 == null || (vp2.match1.contains(bp1) && vp2.match1.isRun)) {
					vp2.match1 = vp;
					vp2.basePoint1 = (Point) bp2;
					vp2.match1endpoint = (Point) bp1;
					vp2.s1 = s;

				} else if (vp2.match2 == null || (vp2.match2.contains(bp1) && vp2.match2.isRun)) {
					if (s.distance < vp2.s1.distance) {
						vp2.match2 = vp2.match1;
						vp2.basePoint2 = vp2.basePoint1;
						vp2.match2endpoint = vp2.match1endpoint;
						vp2.s2 = vp2.s1;
						vp2.match1 = vp;
						vp2.basePoint1 = (Point) bp2;
						vp2.match1endpoint = (Point) bp1;
						vp2.s1 = s;
					} else {
						vp2.match2 = vp;
						vp2.basePoint2 = (Point) bp2;
						vp2.match2endpoint = (Point) bp1;
						vp2.s2 = s;
					}
				}
				vp.numMatches = 2;
				vp2.numMatches = 2;
			}
			System.out.println("After:");
			System.out.println(vp.fullString());
			System.out.println(vp2.fullString());
			System.out.println();

		}
	}

	class Run extends VirtualPoint {
		public int size;
		public ArrayList<VirtualPoint> knotPoints; // [ vp1, vp2, ... vpm];
		public HashMap<Integer, VirtualPoint> pointToInternalKnot;
		public VirtualPoint endpoint1;
		public VirtualPoint endpoint2;
		// [[s1, ..., sn-1], [s1, ..., sn-1], ... m]; sorted and remove
		// vp1, vp2, ... vpm

		public Run(ArrayList<VirtualPoint> knotPoints) {
			// TODO: need to flatten all runs in the constructor
			sortedSegments = new ArrayList<>();

			ArrayList<VirtualPoint> flattenRunPoints = flattenRunPoints(knotPoints, false);
			fixRunList(flattenRunPoints, flattenRunPoints.size() - 1);
			if (flattenRunPoints.size() > knotPoints.size()) {
				System.out.println("Breaking!");
				for (VirtualPoint vp : flattenRunPoints) {
					System.out.println(vp.fullString());
				}
				runmergecount++;
				if (runmergecount > 20) {
					float zero = 1 / 0;
				}
			}
			if (flattenRunPoints.size() != knotPoints.size()) {
			}
			this.knotPoints = flattenRunPoints;
			this.endpoint1 = this.knotPoints.get(0);
			this.endpoint2 = this.knotPoints.get(this.knotPoints.size() - 1);
			isKnot = false;
			isRun = true;
			this.topGroup = this;
			size = this.knotPoints.size();
			this.externalVirtualPoints = new ArrayList<>();
			externalVirtualPoints.add(endpoint1);
			externalVirtualPoints.add(endpoint2);
			knotPointsFlattened = new ArrayList<VirtualPoint>();
			pointToInternalKnot = new HashMap<>();

			for (VirtualPoint vp : this.knotPoints) {
				if (vp.isKnot) {
					Knot knot = ((Knot) vp);
					for (VirtualPoint p : knot.knotPointsFlattened) {
						knotPointsFlattened.add(p);
						pointToInternalKnot.put(p.id, knot);
					}
				} else if (vp.isRun) {
					Run knot = ((Run) vp);
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
			ArrayList<VirtualPoint> endpoints = new ArrayList<>();
			endpoints.add(endpoint2);
			endpoints.add(endpoint1);
			for (VirtualPoint vp : endpoints) {
				if (vp.isKnot) {
					ArrayList<Segment> vpExternal = vp.sortedSegments;
					for (Segment s : vpExternal) {
						if (!(knotPointsFlattened.contains(s.first) && knotPointsFlattened.contains(s.last))) {
							sortedSegments.add(s);
						}
					}
				} else if (vp.isRun) {
					ArrayList<Segment> vpExternal = vp.sortedSegments;
					for (Segment s : vpExternal) {
						if (!(knotPointsFlattened.contains(s.first) && knotPointsFlattened.contains(s.last))) {
							sortedSegments.add(s);
						}
					}
				} else {
					ArrayList<Segment> vpExternal = vp.sortedSegments;
					for (Segment s : vpExternal) {
						if (!(knotPointsFlattened.contains(s.first) && knotPointsFlattened.contains(s.last))) {
							sortedSegments.add(s);
						}
					}
				}
				for (VirtualPoint flat : vp.knotPointsFlattened) {
					flat.topGroupVirtualPoint = vp;
				}
			}
			for (VirtualPoint vp : this.knotPoints) {
				vp.group = this;
				vp.topGroup = this;
			}
			for (VirtualPoint p : knotPointsFlattened) {
				p.topGroup = this;
			}
			sortedSegments.sort(null);
			System.out.println(sortedSegments);
			this.id = numPoints;
			pointMap.put(id, this);
			unvisited.add(this);
			numKnots++;
		}

		public Point getNearestBasePoint(VirtualPoint vp) {
			for (int i = 0; i < sortedSegments.size(); i++) {
				Segment s = sortedSegments.get(i);
				if (vp.isKnot) {
					Knot knot = (Knot) vp;
					VirtualPoint p = s.getKnotPoint(knot.knotPointsFlattened);
					if (p != null) {
						return (Point) s.getOther(p);
					}
				} else {
					if (s.contains(vp)) {
						return (Point) s.getOther(vp);
					}
				}
			}
			assert (false);
			return null;
		}

		@Override
		public String toString() {
			String str = "Run[";
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
					+ " basepoint2: " + (basePoint2 == null ? " none " : "" + basePoint2.id)
					+ " endPoint1: " + (endpoint1 == null ? " none " : "" + endpoint1.id)
					+ " endPoint2: " + (endpoint2 == null ? " none " : "" + endpoint2.id);
		}

		public boolean contains(VirtualPoint vp) {
			if (this.equals(vp)) {
				return true;
			}
			if (knotPointsFlattened.contains(vp)) {
				return true;
			}
			return false;
		}
	}

	int breakCount = 0;
	int runCount = 0;

	boolean skipHalfKnotFlag = true;

	public ArrayList<VirtualPoint> createKnots() {
		int recurseCount = 0;
		ArrayList<VirtualPoint> knots = new ArrayList<>();
		ArrayList<VirtualPoint> toVisit = (ArrayList<VirtualPoint>) unvisited.clone();
		ArrayList<VirtualPoint> runList = new ArrayList<>();
		System.out.println("runList: " + runList);
		VirtualPoint mainPoint = toVisit.get(0);
		System.out.println("startPoint: " + mainPoint);
		boolean endpointReached = false;
		VirtualPoint runFailedMatch1 = null;
		VirtualPoint runFailedMatch2 = null;
		VirtualPoint endPoint1 = null;
		VirtualPoint endPoint2 = null;
		boolean knotFound = false;
		while (toVisit.size() > 0 || runList.size() > 0) {
			toVisit.remove(mainPoint);
			System.out.println("Main Point is now:" + mainPoint);
			System.out.println("runList:" + runList);
			System.out.println("toVisit:" + toVisit);
			System.out.println("knots:" + knots);
			if (mainPoint.numMatches > 2) {
				float zero = 1 / 0;
			}
			Segment potentialSegment1 = mainPoint.getPointer(1);
			Point pointer1 = (Point) potentialSegment1.getOtherKnot(mainPoint.topGroup);
			System.out.println("Point " + mainPoint + " points to: " + pointer1 + " (" + pointer1.topGroup + "):"
					+ potentialSegment1);

			Segment potentialSegment11 = pointer1.topGroup.getPointer(1);
			Point pointer11 = (Point) potentialSegment11.getOtherKnot(pointer1.topGroup);

			Segment potentialSegment12 = pointer1.topGroup.getPointer(2);
			Point pointer12 = (Point) potentialSegment12.getOtherKnot(pointer1.topGroup);
			System.out.println("Point " + pointer1 + " points to: " + pointer11 + " (" + pointer11.topGroup + ") : "
					+ potentialSegment11 + " and "
					+ pointer12 + " (" + pointer12.topGroup + ") :" + potentialSegment12);

			Segment potentialSegment2 = mainPoint.getPointer(2);
			Point pointer2 = (Point) potentialSegment2.getOtherKnot(mainPoint.topGroup);
			System.out.println("Point " + mainPoint + " points to: " + pointer2 + " (" + pointer2.topGroup + ") : "
					+ potentialSegment2);

			Segment potentialSegment21 = pointer2.topGroup.getPointer(1);
			Point pointer21 = (Point) potentialSegment21.getOtherKnot(pointer2.topGroup);

			Segment potentialSegment22 = pointer2.topGroup.getPointer(2);
			Point pointer22 = (Point) potentialSegment22.getOtherKnot(pointer2.topGroup);
			System.out.println(
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
				recurseCount = 0;
				System.out.println("Point " + matchPoint + " points back" + " | basepoint: " + matchBasePoint
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
						System.out.println("No Knot Found,  runlist: " + runList);

					}
				}

				mainPoint = matchPoint;
			} else {
				recurseCount++;
				if (endpointReached) {
					if (mainPoint.match1.equals(pointer1.topGroup)) {
						runFailedMatch2 = pointer2;
					} else {
						runFailedMatch2 = pointer1;
					}
					endPoint2 = mainPoint;
					System.out.println("Found both end of the run, adding to knot");
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
					System.out.println(runList);
					int size = runList.size();
					runList = flattenRunPoints(runList, false);
					fixRunList(runList, runList.size() - 1);
					int size2 = runList.size();
					for (VirtualPoint vp : runList) {
						vp.topGroup = vp;
						vp.group = vp;
						for (VirtualPoint kp : vp.knotPointsFlattened) {
							kp.topGroup = vp;
						}
					}

					for (int i = 0; i < runList.size(); i++) {
						VirtualPoint vp = runList.get(i);
						Segment s1 = vp.getFirstUnmatched();
						System.out.println(s1.getOtherKnot(vp));
						VirtualPoint other = s1.getOtherKnot(vp).topGroup;
						Segment s2 = other.getFirstUnmatched();
						System.out.println(vp.fullString());
						System.out.println("Checking: " + vp + " and : " + other + " s1: " + s1 + " s2: " + s2);
						if (s1.equals(s2) && runList.contains(other)) {
							knotFlag = true;
							System.out.println("Half-Knot ends:" + s1);
							System.out.println(runList);
							makeHalfKnot(runList, vp, other);
							System.out.println(runList);
							i = 0;
						} else if (other.isKnot && vp.shouldJoinKnot((Knot) other)) {

							knotFlag = true;
							System.out.println("Half-Knot ends:" + s1);
							System.out.println(runList);
							makeHalfKnot(runList, vp, other);
							System.out.println(runList);

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

						if (halfKnotCount > 100) {
							System.out.println(runList);
							System.out.println(endPoint2.fullString());
							System.out.println(endPoint1.fullString());
							System.out.println(runFailedMatch2.fullString());
							System.out.println(runFailedMatch1.fullString());
							float zero = 1 / 0;
						}
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
					Run k = new Run(runList);
					knots.add(k);
					runList = new ArrayList<>();
					if (toVisit.size() == 0) {
						return knots;
					}
					mainPoint = toVisit.get(0);
					endpointReached = false;
				} else {

					if (mainPoint.match1 != null && mainPoint.match1.equals(pointer1.topGroup)) {
						runFailedMatch1 = pointer2;
					} else {
						runFailedMatch1 = pointer1;
					}
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
					System.out.println("nothing points back so go to other end of the runlist");
					System.out.println("potential match 1's " + vp1 + " (" + pointer1 + ") matches: " + vp11 + "("
							+ pointer11 + ")" + " " + vp12 + "(" + pointer12 + ")");
					System.out.println("potential match 2's " + vp2 + " (" + pointer2 + ") matches: " + vp21 + "("
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
			System.out.println(vp);
			System.out.println(subList);
			for (VirtualPoint vp1 : runList) {
				System.out.println(vp1.fullString());
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
			System.out.println(other);
			System.out.println(subList);
			for (VirtualPoint vp1 : runList) {
				System.out.println(vp1.fullString());
			}
			other.swap();
			other.setMatch2(null, null, null, null);
		}
		System.out.println("reeee: " + subList + " vp: " + vp + " other: " + other + "runList: " + runList + " vpidx: "
				+ vpIdx + " otheridx: " + otherIdx);
		Knot k = new Knot(subList);
		k.setMatch1(tempMatch, tempME, tempBP, tempS);
		if (tempMatch != null) {
			System.out.println(tempMatch.fullString());
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
		System.out.println(runList);
	}

	int halfKnotCount = 0;
	int sameKnotPointCount = 0;

	@SuppressWarnings("unused")
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
		System.out.println(sortedString);
		int idx = 0;
		while (unvisited.size() > 1) {
			ArrayList<VirtualPoint> knots = createKnots();
			System.out.println("\n================= - Layer: " + idx + " - =================");
			unvisited = knots;
			System.out.println("unvisited:" + unvisited);
			System.out.println("visited:" + visited);
			System.out.println("================= - Layer: " + idx + " - =================\n");
			if (idx == 30) {
				float zero = 1 / 0;
			}
			idx++;
		}
		System.out.println("\n================= - WARNING - =================");
		System.out.println("警告:ゴーディアスノットを切断します");
		System.out.println("システムロックが解除されました");
		System.out.println("ナイフが噛み合った");
		System.out.println("カット開始");
		System.out.println("================= - WARNING - =================\n");
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
		System.out.println(sortedString);
		int idx = 0;
		while (unvisited.size() > 1 && idx != layers) {
			ArrayList<VirtualPoint> knots = createKnots();
			System.out.println("\n================= - Layer: " + idx + " - =================");
			unvisited = knots;
			System.out.println("unvisited:" + unvisited);
			System.out.println("visited:" + visited);
			System.out.println("================= - Layer: " + idx + " - =================\n");
			idx++;
		}
		return unvisited;
	}

	public Shell cutKnot(Knot mainKnot) {
		// seems like there are three cases: combining two knots, (need to remove two
		// segments and add two with the lowest cost increase)
		// pulling apart two knot that has two endpoints and want to cut different
		// segments (need to match the two segments that lost an end)
		// pulling apart two knot that has two endpoints and want to cut the same
		// segment (just remove the segment)
		ArrayList<VirtualPoint> knotList = cutKnot(mainKnot.knotPoints);
		Shell result = new Shell();
		for (VirtualPoint p : knotList) {
			result.add(((Point) p).p);
		}
		return result;

	}

	public ArrayList<VirtualPoint> cutKnot(ArrayList<VirtualPoint> knotList) {
		knotList = new ArrayList<>(knotList);
		// move on to the cutting phase
		VirtualPoint prevPoint = knotList.get(knotList.size() - 1);
		for (int i = 0; i < knotList.size(); i++) {

			VirtualPoint vp = knotList.get(i);
			System.out.println("Checking Point: " + vp);
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
				System.out.println("Found Knot!" + knot.fullString());

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
				System.out.println(knotPoint2);
				if (!knotPoint2.group.equals(knot)) {
					knotPoint2 = knot.pointToInternalKnot.get(knotPoint2.id);
				}
				VirtualPoint cutPoint2 = knotPoint2.match2endpoint;
				if (!cutPoint2.group.equals(knot)) {
					cutPoint2 = knot.pointToInternalKnot.get(cutPoint2.id);
				}
				if ((external1.isKnot || external2.isKnot)
						&& (external1.getHeight() > 1 || knot.getHeight() > 1 || external2.getHeight() > 1)) {
					System.out.println("Need to simplify knots internally before matching : knot: " + knot
							+ " external1: " + external1 + " external2: " + external2);
					ArrayList<VirtualPoint> flattenKnots = cutKnot(knot.knotPoints);
					Knot knotNew = new Knot(flattenKnots);

					boolean makeExternal1 = external1.isKnot;

					boolean same = external1.equals(external2);
					boolean makeExternal2 = external2.isKnot && !same;

					Knot external1Knot = null;
					ArrayList<VirtualPoint> flattenKnotsExternal1 = null;
					Knot external1New = null;
					if (makeExternal1) {

						external1Knot = (Knot) external1;
						flattenKnotsExternal1 = cutKnot(external1Knot.knotPoints);
						external1New = new Knot(flattenKnotsExternal1);
					}
					Knot external2Knot = null;
					ArrayList<VirtualPoint> flattenKnotsExternal2 = null;
					Knot external2New = null;
					if (makeExternal2) {

						external2Knot = (Knot) external2;
						flattenKnotsExternal2 = cutKnot(external2Knot.knotPoints);
						external2New = new Knot(flattenKnotsExternal2);
					}

					if (external1.contains(knot.match1endpoint)) {
						if (makeExternal1) {
							knotNew.match1 = external1New;
						} else if (!same) {
							knotNew.match1 = external1;
						}
						knotNew.match1endpoint = knot.match1endpoint;
						knotNew.basePoint1 = knot.basePoint1;
						knotNew.s1 = knot.s1;
					}
					if (external1.contains(knot.match2endpoint)) {
						if (makeExternal1) {
							knotNew.match2 = external1New;
						} else if (!same) {
							knotNew.match2 = external1;
						}
						knotNew.match2endpoint = knot.match2endpoint;
						knotNew.basePoint2 = knot.basePoint2;
						knotNew.s2 = knot.s2;
					}

					if (external2.contains(knot.match1endpoint)) {
						if (makeExternal2) {
							knotNew.match1 = external2New;
						} else if (!same) {
							knotNew.match1 = external2;
						}
						knotNew.match1endpoint = knot.match1endpoint;
						knotNew.basePoint1 = knot.basePoint1;
						knotNew.s1 = knot.s1;
					}
					if (external2.contains(knot.match2endpoint)) {
						if (makeExternal2) {
							knotNew.match2 = external2New;
						} else if (!same) {
							knotNew.match2 = external2;
						}
						knotNew.match2endpoint = knot.match2endpoint;
						knotNew.basePoint2 = knot.basePoint2;
						knotNew.s2 = knot.s2;
					}

					if (knotNew.contains(external1.match1endpoint)) {
						if (makeExternal1) {
							external1New.match1 = knotNew;
							external1New.match1endpoint = external1.match1endpoint;
							external1New.basePoint1 = external1.basePoint1;
							external1New.s1 = external1.s1;
							if (!same) {
								external1New.match2 = external1.match2;
								external1New.match2endpoint = external1.match2endpoint;
								external1New.basePoint2 = external1.basePoint2;
								external1New.s2 = external1.s2;
							}
						} else {
							external1.match1 = knotNew;
						}
					}
					if (knotNew.contains(external1.match2endpoint)) {
						if (makeExternal1) {
							external1New.match2 = knotNew;
							external1New.match2endpoint = external1.match2endpoint;
							external1New.basePoint2 = external1.basePoint2;
							external1New.s2 = external1.s2;
							if (!same) {
								external1New.match1 = external1.match1;
								external1New.match1endpoint = external1.match1endpoint;
								external1New.basePoint1 = external1.basePoint1;
								external1New.s1 = external1.s1;
							}
						} else {
							external1.match2 = knotNew;
						}
					}

					if (knotNew.contains(external2.match1endpoint)) {
						if (makeExternal2) {
							external2New.match1 = knotNew;
							external2New.match1endpoint = external2.match1endpoint;
							external2New.basePoint1 = external2.basePoint1;
							external2New.s1 = external2.s1;
							external2New.match2 = external2.match2;
							external2New.match2endpoint = external2.match2endpoint;
							external2New.basePoint2 = external2.basePoint2;
							external2New.s2 = external2.s2;

						} else {
							external2.match1 = knotNew;
						}
					}
					if (knotNew.contains(external2.match2endpoint)) {
						if (makeExternal2) {
							external2New.match2 = knotNew;
							external2New.match2endpoint = external2.match2endpoint;
							external2New.basePoint2 = external2.basePoint2;
							external2New.s2 = external2.s2;
							external2New.match1 = external2.match1;
							external2New.match1endpoint = external2.match1endpoint;
							external2New.basePoint1 = external2.basePoint1;
							external2New.s1 = external2.s1;
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
						if (makeExternal2) {
							external1New.match1 = external2New;
						} else {
							external1.match1 = external2New;
						}
					}
					if (makeExternal2 && external2New.contains(external1.match2endpoint)) {
						if (makeExternal2) {
							external1New.match2 = external2New;
						} else {
							external1.match2 = external2New;
						}
					}
					if (makeExternal1) {
						if (external1New.match1.basePoint1.equals(external1New.match1endpoint)) {
							external1New.match1.match1 = external1New;
						} else {
							external1New.match1.match2 = external1New;
						}
						if (external1New.match2.basePoint1.equals(external1New.match1endpoint)) {
							external1New.match2.match1 = external1New;
						} else {
							external1New.match2.match2 = external1New;
						}
					}
					if (makeExternal2) {
						if (external2New.match1.basePoint1.equals(external2New.match1endpoint)) {
							external2New.match1.match1 = external2New;
						} else {
							external2New.match1.match2 = external2New;
						}
						if (external2New.match2.basePoint1.equals(external2New.match1endpoint)) {
							external2New.match2.match1 = external2New;
						} else {
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
					if (makeExternal1) {
						prevPoint = external1New;
					}
					i = i - 1;
					System.out.println(external1New);
					System.out.println(external1);
					System.out.println(external2New);
					System.out.println(external2);
					System.out.println(knotNew);
					System.out.println(knotList);
					System.out.println(prevPoint);
					// float zero =1/0;
					continue;
				}
				System.out.println("knotpoint1: " + knotPoint1.fullString());
				System.out.println("knotpoint2: " + knotPoint2.fullString());
				System.out.println("cutPoint1: " + cutPoint1.fullString());
				System.out.println("cutPoint2: " + cutPoint2.fullString());
				System.out.println("external1: " + external1.fullString());
				System.out.println("external2: " + external2.fullString());
				if (knotPoint2.equals(knotPoint1)) {
					System.out.println("!!!!Both externals : ( " + external1 + " " + external2
							+ " ) point to the same VirtualPoint: " + knotPoint1);
					// this doesnt work if both cut points are part of the same knot
					cutPoint2 = knotPoint1.match1endpoint;
					if (!cutPoint2.group.equals(knot)) {
						cutPoint2 = knot.pointToInternalKnot.get(cutPoint2.id);
					}
					System.out.println("Cut Point 1: " + cutPoint2.fullString());
					System.out.println("Cut Point 2: " + cutPoint2);
					// this checking needs to be recursive down to the base when dealing with nested
					// knots
					Segment s1 = knotPoint1.getClosestSegment(external1, null);
					Segment s11 = cutPoint1.getClosestSegment(external2, null);
					Segment s12 = cutPoint2.getClosestSegment(external2, null);
					if (cutPoint1.equals(cutPoint2)) {
						s11 = cutPoint2.basePoint2.getClosestSegment(external2, null);
					}

					Segment s2 = knotPoint1.getClosestSegment(external2, null);
					Segment s21 = cutPoint1.getClosestSegment(external1, null);
					Segment s22 = cutPoint2.getClosestSegment(external1, null);
					if (cutPoint1.equals(cutPoint2)) {
						s22 = cutPoint2.basePoint2.getClosestSegment(external1, null);
					}
					// need to change this, unsure how
					// instead of being the closest segment needs to be the farthest segment that
					// knotPoint 1 matches
					Point vp1 = (Point) s1.getKnotPoint(knot.knotPointsFlattened);
					Point vp11 = (Point) s11.getKnotPoint(knot.knotPointsFlattened);

					Segment cutSegment1 = new Segment(vp1, vp11, distanceMatrix.getDistance(vp1.p, vp11.p));

					Point vp2 = (Point) s2.getKnotPoint(knot.knotPointsFlattened);
					Point vp22 = (Point) s22.getKnotPoint(knot.knotPointsFlattened);

					Segment cutSegment2 = new Segment(vp2, vp22, distanceMatrix.getDistance(vp2.p, vp22.p));

					System.out.println(s1 + "" + s11 + "" + "cut: " + cutSegment1);
					System.out.println(s1.distance + s11.distance - cutSegment1.distance);
					double d11 = s1.distance + s11.distance - cutSegment1.distance;
					System.out.println(s1 + "" + s12 + "" + "cut: " + cutSegment2);
					System.out.println(s1.distance + s12.distance - cutSegment2.distance);
					double d12 = s1.distance + s12.distance - cutSegment2.distance;
					System.out.println(s2 + "" + s21 + "" + "cut: " + cutSegment1);
					System.out.println(s2.distance + s21.distance - cutSegment1.distance);
					double d21 = s2.distance + s21.distance - cutSegment1.distance;
					System.out.println(s2 + "" + s22 + "" + "cut: " + cutSegment2);
					System.out.println(s2.distance + s22.distance - cutSegment2.distance);
					double d22 = s2.distance + s22.distance - cutSegment2.distance;

					/*
					 * need to make a decision tree of all possible cuts
					 * a decision point occurs when we change the external points of an internal
					 * knot, we need to recursively check all of the possible cut segments
					 * should make a method that takes in a Knot and a proposed cut segment and
					 * returns the smallest change possible
					 * How do I do this in a way thats not totally ass?
					 */

					if (knotPoint1.isKnot) {

						d11 = s1.distance + s11.distance;
						d12 = s1.distance + s12.distance;
						d21 = s2.distance + s21.distance;
						d22 = s2.distance + s22.distance;
					}
					if (d11 < d12 && d11 < d21 && d11 < d22) {
						System.out.println("s1 + s11 (" + s1 + ", " + s11 +
								") is the smallest, cutting: " + cutSegment1);

						external1.matchAcross2(knotPoint1, s1, s11, cutSegment1);
						external2.matchAcross2(cutPoint1, s11, s1, cutSegment1);
					} else if (d12 < d21 && d12 < d22) {
						System.out.println("s1 + s12 (" + s1 + ", " + s12 +
								") is the smallest, cutting: " + cutSegment2);

						external1.matchAcross2(knotPoint1, s1, s12, cutSegment2);
						external2.matchAcross2(cutPoint2, s12, s1, cutSegment2);
					} else if (d21 < d22) {
						System.out.println("s2 + s21 (" + s2 + ", " + s21 +
								") is the smallest, cutting: " + cutSegment1);

						external2.matchAcross2(knotPoint1, s2, s21, cutSegment1);
						external1.matchAcross2(cutPoint1, s21, s2, cutSegment1);
					} else {
						System.out.println("s2 + s22 (" + s2 + ", " + s22 +
								") is the smallest, cutting: " + cutSegment2);

						external2.matchAcross2(knotPoint1, s2, s22, cutSegment2);
						external1.matchAcross2(cutPoint2, s22, s2, cutSegment2);
						System.out.println(cutPoint1);
						System.out.println(cutPoint2);
					}
				} else {
					if (!cutPoint2.group.equals(knot)) {
						cutPoint2 = cutPoint2.group;
					}
					if (cutPoint2.equals(knotPoint1) || cutPoint1.equals(knotPoint2)) {
						cutPoint1 = knotPoint2;
						cutPoint2 = knotPoint1;
					}
					// TODO: if we have a 3 knot with two different cut segments, we need to test
					// which is better
					Segment nearestSegment1 = knotPoint1.getClosestSegment(external1, null);
					Segment nearestSegment2 = knotPoint2.getClosestSegment(external2, null);
					Point nearestbp1 = (Point) nearestSegment1.getKnotPoint(knotPoint1.knotPointsFlattened);
					Point nearestbp2 = (Point) nearestSegment2.getKnotPoint(knotPoint2.knotPointsFlattened);
					Segment cutSegment1 = new Segment(knotPoint1, cutPoint1, 0);
					Segment cutSegment2 = new Segment(knotPoint2, cutPoint2, 0);
					if (cutSegment1.equals(cutSegment2)) {
						System.out.println("----Both externals agree on cut segment " + cutSegment1
								+ ", proceed -------------------");
						external1.setMatch(external1.match1.equals(knot), knotPoint1, nearestbp1,
								(Point) nearestSegment1.getOther(nearestbp1), nearestSegment1);

						knotPoint1.setMatch(cutPoint1.equals(knotPoint1.match1), external1);

						external2.setMatch(external2.match1.equals(knot), knotPoint2, nearestbp2,
								(Point) nearestSegment2.getOther(nearestbp2), nearestSegment2);

						knotPoint2.setMatch(cutPoint2.equals(knotPoint2.match1), external2);
					} else {
						System.out.println("----Both externals disagree on cut segment " + cutSegment1 + "   "
								+ cutSegment2 + ", need to assess -------------------");
						if (cutPoint1.equals(cutPoint2)) {
							System.out.println("----Both cutpoints:  " + cutPoint1 + "   " + cutPoint2
									+ " are the same, need to assess which is better -------------------");
							Segment s11 = knotPoint1.getClosestSegment(external1, null);
							Segment s12 = cutPoint1.getClosestSegment(external2, null);

							Point vp1 = (Point) s11.getKnotPoint(knot.knotPointsFlattened);
							Point vp11 = (Point) s12.getKnotPoint(knot.knotPointsFlattened);

							cutSegment1 = new Segment(vp1, vp11, distanceMatrix.getDistance(vp1.p, vp11.p));

							Segment s2 = knotPoint2.getClosestSegment(external2, null);
							Segment s21 = cutPoint1.getClosestSegment(external1, null);

							Point vp2 = (Point) s2.getKnotPoint(knot.knotPointsFlattened);
							Point vp22 = (Point) s21.getKnotPoint(knot.knotPointsFlattened);

							cutSegment2 = new Segment(vp2, vp22, distanceMatrix.getDistance(vp2.p, vp22.p));

							if (s11.distance + s12.distance < s2.distance + s21.distance) {
								System.out.println("s1 + s11 (" + s11 + ", " + s12 +
										") is the smallest, cut: " + cutSegment1);
								external1.matchAcross2(knotPoint1, s11, s12, cutSegment1);
								external2.matchAcross2(cutPoint1, s12, s11, cutSegment1);
							} else {
								System.out.println("s2 + s21 (" + s2 + ", " + s21 +
										") is the smallest, cut: " + cutSegment2);
								external2.matchAcross2(knotPoint2, s2, s21, cutSegment2);
								external1.matchAcross2(cutPoint2, s21, s2, cutSegment2);
							}
						} else {
							// there should be two cases:
							// first case: we make two unconnected strands with the loose ends being the cut
							// POints
							// so we connect the externals to their preferred knotpoints and the cut points
							// to each other
							// second case: both externals want cut off the same strand leaving it orphaned,
							// similar to the above case where both knots points are different
							// but the cutpoint is the same leaving it orphaned. need to choose a knotpoint
							// that results in the smaller knot. ( I haven't seen this and am unsure about
							// how to
							// easily check that this is the case) likely need to march until you find the
							// cutpoint
							// or knotpoint from one of the cutpoints.
							// I think you can also check if the attach segment already exists? no that
							// wouldn't work

							Segment s1 = knotPoint1.getClosestSegment(external1, null);
							Segment s11 = cutPoint1.getClosestSegment(external2, s1);

							Segment s4 = knotPoint2.getClosestSegment(external1, null);
							Segment s42 = cutPoint2.getClosestSegment(external2, s4);

							Segment s22 = cutPoint2.getClosestSegment(external1, null);
							Segment s2 = knotPoint2.getClosestSegment(external2, s22);

							Segment s51 = cutPoint1.getClosestSegment(external1, null);
							Segment s5 = knotPoint1.getClosestSegment(external2, s51);

							Segment s3 = cutPoint2.getClosestSegment(cutPoint1, null);

							cutSegment1.distance = distanceMatrix.getDistance(((Point) cutSegment1.first).p,
									((Point) cutSegment1.last).p);

							cutSegment2.distance = distanceMatrix.getDistance(((Point) cutSegment2.first).p,
									((Point) cutSegment2.last).p);

							System.out.println(s1 + "" + s11 + "" + "cut: " + cutSegment1);
							System.out
									.println(s1.distance + s11.distance - cutSegment1.distance + cutSegment2.distance);
							double d11 = s1.distance + s11.distance - cutSegment1.distance + cutSegment2.distance;
							System.out.println(s4 + "" + s42 + "" + "cut: " + cutSegment2);
							System.out
									.println(s4.distance + s42.distance - cutSegment2.distance + cutSegment1.distance);
							double d4 = s4.distance + s42.distance - cutSegment2.distance + cutSegment1.distance;
							System.out.println(s5 + "" + s51 + "" + "cut: " + cutSegment1);
							System.out
									.println(s5.distance + s51.distance - cutSegment1.distance + cutSegment2.distance);
							double d5 = s5.distance + s51.distance - cutSegment1.distance + cutSegment2.distance;
							System.out.println(s2 + "" + s22 + "" + "cut: " + cutSegment2);
							System.out
									.println(s2.distance + s22.distance - cutSegment2.distance + cutSegment1.distance);
							double d22 = s2.distance + s22.distance - cutSegment2.distance + cutSegment1.distance;
							// if we march away from cutpoint 1 toward knotpoint1 do we reach knotpoint2?
							// then we would orphan a segment
							// if we reach cutpoint 2 before knotpoint2 then we would not orphan
							// then distance would be the orphan middle also needs to be cut
							System.out.println(
									s2 + "" + s1 + "" + s3 + "" + "cut: " + cutSegment2 + " and " + cutSegment1);
							System.out.println(s2.distance + s1.distance + s3.distance);
							int kp1 = knot.knotPoints.indexOf(knotPoint1);
							int cp1 = knot.knotPoints.indexOf(cutPoint1);
							int cp2 = knot.knotPoints.indexOf(cutPoint2);
							int kp2 = knot.knotPoints.indexOf(knotPoint2);
							double d3 = s1.distance + s2.distance + s3.distance;
							boolean orphan = false;
							double min = Double.MAX_VALUE;
							VirtualPoint vpOrph1 = null;
							VirtualPoint vpOrph2 = null;
							Segment sOrph1 = null;
							Segment sOrph2 = null;
							Segment cutOrph = null;
							// check if it would orphan
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
								System.out.println("Would ORPHAN! ");
								orphan = true;
								// need to find the best segment to insert in?
								// alternativetely need to make orphan into a knot?
								// get longest segment in the non-orphan segment and cut?
								//
								VirtualPoint prevP = cutPoint1;
								VirtualPoint nextP = knotPoint1;

								while (!nextP.equals(knotPoint2)) {
									VirtualPoint temp = nextP;
									if (nextP.match2.equals(prevP)) {
										nextP = nextP.match1;
									} else {
										nextP = nextP.match2;
									}
									prevP = temp;
									System.out.println(prevP + " " + nextP);

									Segment temp1 = cutPoint1.getClosestSegment(prevP, null);
									Segment temp2 = cutPoint2.getClosestSegment(nextP, null);
									Segment cutTemp = nextP.getClosestSegment(prevP, null);
									double delta = temp1.distance + temp2.distance - cutTemp.distance;

									Segment temp3 = cutPoint1.getClosestSegment(nextP, null);
									Segment temp4 = cutPoint2.getClosestSegment(prevP, null);
									double delta2 = temp3.distance + temp4.distance - cutTemp.distance;
									if (delta < min) {
										sOrph1 = temp1;
										sOrph2 = temp2;
										cutOrph = cutTemp;
										min = delta;
										vpOrph1 = prevP;
										vpOrph2 = nextP;
									}
									if (delta2 < min) {
										sOrph1 = temp3;
										sOrph2 = temp4;
										cutOrph = cutTemp;
										min = delta2;
										vpOrph1 = nextP;
										vpOrph2 = prevP;
									}
									if (prevP.equals(knotPoint2)) {
										break;
									}

								}
								System.out.println(
										"Orphan Link: " + sOrph1 + " " + sOrph2 + " cut:" + cutOrph + " :" + min);
								d3 += min;
							}
							if (d3 < d22 && d3 < d11 && d3 < d5 && d3 < d4) {
								System.out.println("Cutting and attaching cutpoints : " + d3);
								external1.matchAcross2(knotPoint1, s1, s2, cutSegment1);

								external2.matchAcross2(knotPoint2, s2, s1, cutSegment2);

								if (!orphan) {
									Point nearestcp1bp = cutPoint1.getNearestBasePoint(cutPoint2);
									Point nearestcp2bp = cutPoint2.getNearestBasePoint(cutPoint1);
									cutPoint1.setMatch(cutPoint1.match1.equals(knotPoint1), cutPoint2, nearestcp2bp,
											nearestcp1bp, s3);
									cutPoint2.setMatch(cutPoint2.match1.equals(knotPoint2), cutPoint1, nearestcp1bp,
											nearestcp2bp, s3);
								} else {

									Point nearestcp1bp = cutPoint1.getNearestBasePoint(vpOrph1);
									Point nearestcp2bp = vpOrph1.getNearestBasePoint(cutPoint1);
									vpOrph1.setMatch(vpOrph1.match1.equals(vpOrph2), cutPoint1, nearestcp1bp,
											nearestcp2bp, sOrph1);
									cutPoint1.setMatch(cutPoint1.match1.equals(knotPoint1), cutPoint1, nearestcp2bp,
											nearestcp1bp, sOrph1);

									Point nearestcp1bp2 = cutPoint1.getNearestBasePoint(vpOrph2);
									Point nearestcp2bp2 = vpOrph2.getNearestBasePoint(cutPoint1);
									vpOrph2.setMatch(vpOrph2.match1.equals(vpOrph1), cutPoint2, nearestcp1bp,
											nearestcp2bp, sOrph2);
									cutPoint2.setMatch(cutPoint2.match1.equals(knotPoint2), cutPoint2, nearestcp2bp,
											nearestcp1bp, sOrph2);
									System.out.println("Linking orphan back in: ");
								}

							} else if (d11 < d22 && d11 < d4 && d11 < d5) {
								System.out.println("s1 + s11 (" + s1 + ", " + s11 +
										") is the smallest, cut: " + cutSegment1);

								external1.matchAcross2(knotPoint1, s1, s11, cutSegment1);
								external2.matchAcross2(cutPoint1, s11, s1, cutSegment1);
							} else if (d4 < d22 && d4 < d5) {
								System.out.println("s4 + s42 (" + s4 + ", " + s42 +
										") is the smallest, cut: " + cutSegment2);
								external1.matchAcross2(knotPoint2, s4, s42, cutSegment2);
								external2.matchAcross2(cutPoint2, s42, s4, cutSegment2);
							} else if (d5 < d22) {
								System.out.println("s5 + s51 (" + s5 + ", " + s51 +
										") is the smallest, cut: " + cutSegment1);

								external1.matchAcross2(knotPoint1, s5, s51, cutSegment1);
								external2.matchAcross2(cutPoint1, s51, s5, cutSegment1);
							} else {
								System.out.println("s2 + s22 (" + s2 + ", " + s22 +
										") is the smallest, cut: " + cutSegment2);

								System.out.println(external1.fullString());
								external2.matchAcross2(knotPoint2, s2, s22, cutSegment2);
								System.out.println(external1.fullString());
								external1.matchAcross2(cutPoint2, s22, s2, cutSegment2);
								System.out.println(external1.fullString());
								// float zero =1/0;
							}

							sameKnotPointCount++;
							if (sameKnotPointCount > 30) {
								float zero = 1 / 0;
							}
						}
					}
				}
				System.out.println("knotPoint1 final: " + knotPoint1.fullString());
				System.out.println("knotPoint2 final: " + knotPoint2.fullString());
				System.out.println("cutPoint1 final: " + cutPoint1.fullString());
				System.out.println("cutPoint2 final: " + cutPoint2.fullString());
				System.out.println("external1 final: " + external1.fullString());
				System.out.println("external2 final: " + external2.fullString());
				knotList.remove(vp);
				VirtualPoint addPoint = knotPoint2;
				if (knotPoint1.match1.equals(prevPoint) || knotPoint1.match2.equals(prevPoint)) {
					addPoint = knotPoint1;
				} else if (cutPoint1.match1.equals(prevPoint) || cutPoint1.match2.equals(prevPoint)) {
					addPoint = cutPoint1;
				} else if (cutPoint2.match1.equals(prevPoint) || cutPoint2.match2.equals(prevPoint)) {
					addPoint = cutPoint2;
				}
				VirtualPoint prevPointTemp = prevPoint;
				for (int j = 0; j < knot.knotPoints.size(); j++) {
					System.out.println("adding: " + addPoint);
					knotList.add(i + j, addPoint);
					if (prevPointTemp.equals(addPoint.match2)) {
						prevPointTemp = addPoint;
						addPoint = addPoint.match1;
					} else {
						prevPointTemp = addPoint;
						addPoint = addPoint.match2;
					}
				}
				System.out.println(knotList);
				i = i - 1;
			}
			if (!vp.isKnot) {
				prevPoint = vp;
			}
		}
		return knotList;
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
