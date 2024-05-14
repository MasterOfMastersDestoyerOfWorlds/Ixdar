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
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.apache.commons.collections4.map.MultiKeyMap;
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

		public boolean partialOverlaps(Segment cutSegment2) {
			if ((cutSegment2.contains(first) && !cutSegment2.contains(last)) ||
					(cutSegment2.contains(last) && !cutSegment2.contains(first))) {
				return true;
			}
			return false;
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

		public Segment getFirstUnmatched(ArrayList<VirtualPoint> runList) {

			for (Segment s : sortedSegments) {
				VirtualPoint other = s.getOtherKnot(this);
				if ((match1 == null || !match1.contains(other))
						&& (match2 == null || !match2.contains(other))) {
					return s;
				}
			}
			return s1;
		}

		public boolean shouldJoinKnot(Knot k) {
			System.out.println(k.fullString());
			int desiredCount = k.knotPointsFlattened.size() * this.knotPointsFlattened.size();
			boolean oneOutFlag = false;
			for (Segment s : this.sortedSegments) {
				VirtualPoint vp = s.getOtherKnot(this);

				if (!k.contains(vp)) {
					if (!oneOutFlag) {
						oneOutFlag = true;
					} else {
						System.out.println("broke on this segment: " + s + " desired count: " + desiredCount
								+ " org count: " + k.knotPoints.size() + " sorted segments: " + this.sortedSegments);
						return false;
					}
				}
				desiredCount--;
				if (desiredCount == 0) {
					return true;
				}
			}
			return true;
		}

		public boolean shouldKnotConsume(Knot k) {
			System.out.println(k.fullString());
			int desiredCount = k.knotPointsFlattened.size() * this.knotPointsFlattened.size();
			boolean oneOutFlag = false;
			for (Segment s : k.sortedSegments) {
				VirtualPoint vp = s.getOtherKnot(k);

				if (!this.contains(vp)) {
					if (!oneOutFlag) {
						oneOutFlag = true;
					} else {
						System.out.println("broke on this segment: " + s + " desired count: " + desiredCount
								+ " org count: " + k.knotPoints.size() + " sorted segments: " + this.sortedSegments);
						return false;
					}
				}
				desiredCount--;
				if (desiredCount == 0) {
					return true;
				}
			}
			return true;
		}

		public Segment getClosestSegment(VirtualPoint vp, Segment excludeSegment) {
			VirtualPoint excludeThis = excludeSegment == null ? null : excludeSegment.getKnotPoint(knotPointsFlattened);
			VirtualPoint excludeOther = excludeSegment == null ? null
					: excludeSegment.getKnotPoint(vp.knotPointsFlattened);

			for (int i = 0; i < sortedSegments.size(); i++) {
				Segment s = sortedSegments.get(i);
				if (vp.isKnot) {
					Knot knot = (Knot) vp;
					if (s.getKnotPoint(knot.knotPointsFlattened) != null && (excludeSegment == null
							|| ((!(vp.isKnot || vp.isRun) || !s.contains(excludeOther))
									&& (!(this.isKnot || this.isRun) || !s.contains(excludeThis))))) {
						return s;
					}
				}
				if (vp.isRun) {
					Run run = (Run) vp;
					if (s.getKnotPoint(run.externalVirtualPoints) != null && (excludeSegment == null
							|| ((!(vp.isKnot || vp.isRun) || !s.contains(excludeOther))
									&& (!(this.isKnot || this.isRun) || !s.contains(excludeThis))))) {
						return s;
					}
				} else {
					if (s.contains(vp) && (!(this.isKnot || this.isRun) || !s.contains(excludeThis))) {
						return s;
					}
				}
			}
			System.out.println("no better segment found");
			System.out.println(excludeSegment);
			System.out.println(vp.fullString());
			System.out.println(vp.sortedSegments);
			System.out.println(this.fullString());
			System.out.println(this.sortedSegments);

			float zero = 1 / 0;
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
			if (s2 != null && s1.distance > s2.distance) {
				this.swap();
			}
			this.checkValid();
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
			if (s2 != null && s1.distance > s2.distance) {
				this.swap();
			}
			this.checkValid();
		}

		public void setMatch2(VirtualPoint matchPoint, Point matchEndPoint, Point matchBasePoint,
				Segment matchSegment) {
			match2 = matchPoint;
			match2endpoint = matchEndPoint;
			basePoint2 = matchBasePoint;
			s2 = matchSegment;
			if (s1 == null || (s1 != null && s2 != null && s1.distance > s2.distance)) {
				this.swap();
			}
			if ((this.isKnot || this.isRun) && basePoint1 != null && basePoint2 != null
					&& basePoint1.equals(basePoint2)) {
				System.out.println("REEEEEEEEEEEEEEEEEEEEEEEE");
				// System.out.println(this.fullString());
				// Segment fixSeg = this.getClosestSegment(match2, s1);
				// VirtualPoint bp = fixSeg.getKnotPoint(knotPointsFlattened);
				// VirtualPoint me = fixSeg.getOther(bp);
				// this.setMatch2(match2, (Point) me, (Point) bp, fixSeg);
				// match2.setMatch(this.contains(match2.match1endpoint), this);
			}
			this.checkValid();
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
			if (s1 == null && s2 != null) {
				this.swap();
			}
			this.checkValid();
		}

		public boolean hasMatch(VirtualPoint matchPoint, Point matchEndPoint, Point matchBasePoint,
				Segment matchSegment) {

			if (match1 != null && match1.equals(matchPoint) && match1endpoint.equals(matchEndPoint)
					&& basePoint1.equals(matchBasePoint) && s1.equals(matchSegment)) {
				return true;
			}
			if (match2 != null && match2.equals(matchPoint) && match2endpoint.equals(matchEndPoint)
					&& basePoint2.equals(matchBasePoint) && s2.equals(matchSegment)) {
				return true;
			}
			return false;

		}

		public boolean hasMatch(VirtualPoint matchPoint) {

			if (match1 != null && match1.contains(matchPoint)) {
				return true;
			}
			if (match2 != null && match2.contains(matchPoint)) {
				return true;
			}
			return false;

		}

		private void checkValid() {
			if (match1 == null && match2 != null) {
				System.out.println(this.fullString());
				float zero = 1 / 0;
			}

			if (s1 == null && s2 != null) {
				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
			if (s2 != null && s1.distance > s2.distance) {
				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
			if ((match1endpoint != null && basePoint1 == null) || (basePoint1 != null && match1endpoint == null)) {

				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
			if ((match2endpoint != null && basePoint2 == null) || (basePoint2 != null && match2endpoint == null)) {

				System.out.println(this.fullString());
				float zero = 1 / 0;
			}

			if ((match1 != null && !match1.contains(match1endpoint))
					|| (match2 != null && !match2.contains(match2endpoint))) {

				System.out.println(this.fullString());
				float zero = 1 / 0;
			}

			if ((match1 == null && (match1endpoint != null || basePoint1 != null))
					|| (match2 == null && (match2endpoint != null || basePoint2 != null))) {

				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
			if (match1endpoint != null && match2endpoint != null && (match1.isKnot || match1.isRun)
					&& (match2.isKnot || match2.isRun) && match1endpoint.equals(match2endpoint)) {

				System.out.println(this.fullString());
				float zero = 1 / 0;
			}
			if ((this.isKnot || this.isRun) && basePoint1 != null && basePoint2 != null
					&& basePoint1.equals(basePoint2)) {

				System.out.println(this.fullString());
				// float zero = 1 / 0;
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
			this.setMatch((!this.match1.equals(this.match2) && this.match1.contains(matchSegment.getOtherKnot(this))) ||
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
			s1 = null;
			s2 = null;
		}

		public void reset(VirtualPoint match) {
			if (match1 != null && match1.contains(match)) {
				numMatches--;
				match1 = null;
				match1endpoint = null;
				basePoint1 = null;
				s1 = null;
			}

			if (match2 != null && match2.contains(match)) {
				numMatches--;
				match2 = null;
				match2endpoint = null;
				basePoint2 = null;
				s2 = null;
			}
			if (match1 == null && match2 != null) {
				this.swap();
			}

			System.out.println("REEEESSSEEEETTTT");
			System.out.println(match.fullString());
			System.out.println(this.fullString());
		}

		public void copyMatches(VirtualPoint vp) {
			match1 = vp.match1;
			match1endpoint = vp.match1endpoint;
			basePoint1 = vp.basePoint1;
			s1 = vp.s1;
			match2 = vp.match2;
			match2endpoint = vp.match2endpoint;
			basePoint2 = vp.basePoint2;
			s2 = vp.s2;
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
				Segment s = vp1.getClosestSegment(vp2, vp1.s1);
				Point bp2 = (Point) s.getOtherKnot(vp1);
				Point bp1 = (Point) s.getOther(bp2);
				if (vp2.basePoint1 != null && vp2.isKnot && vp2.basePoint1.equals(bp2)) {
					s = vp1.getClosestSegment(vp2, vp2.s1);
					bp2 = (Point) s.getOtherKnot(vp1);
					bp1 = (Point) s.getOther(bp2);
				}
				System.out.println(vp1.fullString());
				System.out.println(s);
				vp1.setMatch2(vp2, bp2, bp1, s);
				vp2.setMatch2(vp1, bp1, bp2, s);
			}
			sortedSegments = new ArrayList<>();
			ArrayList<VirtualPoint> flattenRunPoints = flattenRunPoints(knotPointsToAdd, true);
			fixRunList(flattenRunPoints, flattenRunPoints.size());
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
			this.id = pointMap.keySet().size();
			pointMap.put(id, this);
			unvisited.add(this);
			numKnots++;
		}

		public Segment getSegment(VirtualPoint a, VirtualPoint b) {

			if (a.match1.equals(b)) {
				return a.s1;
			}
			if (a.match2.equals(b)) {
				return a.s2;
			}
			if (!a.isKnot && !b.isKnot) {
				Point ap = (Point) a;
				Point bp = (Point) b;
				return new Segment(bp, ap, distanceMatrix.getDistance(ap.p, bp.p));
			}
			return null;
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
		for (int i = 0; i < end; i++) {
			VirtualPoint vp = flattenRunPoints.get(i);
			VirtualPoint vp2 = null;
			if (i < flattenRunPoints.size() - 1) {
				vp2 = flattenRunPoints.get(i + 1);
			} else {
				vp2 = flattenRunPoints.get(0);
			}
			vp.reset();
			vp2.reset();
		}
		for (int i = 0; i < end; i++) {
			VirtualPoint vp = flattenRunPoints.get(i);
			VirtualPoint vp2 = null;
			if (i < flattenRunPoints.size() - 1) {
				vp2 = flattenRunPoints.get(i + 1);
			} else {
				vp2 = flattenRunPoints.get(0);
			}
			// System.out.println("BEfore: ");
			// System.out.println(vp.fullString());
			// System.out.println(vp2.fullString());
			Segment s = vp.getClosestSegment(vp2, vp.s1);
			VirtualPoint bp1 = s.getKnotPoint(vp.externalVirtualPoints);
			VirtualPoint bp2 = s.getOther(bp1);
			if (vp2.basePoint1 != null && vp2.isKnot && vp2.basePoint1.equals(bp2)) {
				s = vp.getClosestSegment(vp2, vp2.s1);
				bp1 = s.getKnotPoint(vp.externalVirtualPoints);
				bp2 = s.getOther(bp1);
			}
			vp.setMatch(vp.match1 == null, vp2, (Point) bp2, (Point) bp1, s);
			vp2.setMatch(vp2.match1 == null, vp, (Point) bp1, (Point) bp2, s);
			vp.numMatches = 2;
			vp2.numMatches = 2;

			// System.out.println("After:");
			// System.out.println(vp.fullString());
			// System.out.println(vp2.fullString());
			// System.out.println();

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
			this.id = pointMap.keySet().size();
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
					if (runList.size() > 2) {
						for (int i = 0; i < runList.size() && runList.size() > 1; i++) {
							VirtualPoint vp = runList.get(i);
							Segment s1 = vp.getFirstUnmatched(runList);
							System.out.println(s1.getOtherKnot(vp));
							VirtualPoint other = s1.getOtherKnot(vp).topGroup;
							Segment s2 = other.getFirstUnmatched(runList);
							System.out.println(vp.fullString());
							System.out.println("Checking: " + vp + " and : " + other + " s1: " + s1 + " s2: " + s2);
							if (s1.equals(s2) && runList.contains(other)) {
								if (other.match1.isKnot && other.shouldJoinKnot((Knot) other.match1)) {
									knotFlag = true;
									System.out.println("Should JOIN JNOT");
									makeHalfKnot(runList, other, other.match1);
									System.out.println(runList);
									i = -1;
									continue;
								} else if (vp.match1.isKnot && vp.shouldJoinKnot((Knot) vp.match1)) {

									knotFlag = true;
									System.out.println("Should JOIN JNOT");
									System.out.println(runList);
									makeHalfKnot(runList, vp, vp.match1);
									System.out.println(runList);
									i = -1;
									continue;
								}
								knotFlag = true;
								System.out.println("Half-Knot ends:" + s1);
								System.out.println(runList);
								makeHalfKnot(runList, vp, other);
								System.out.println(runList);
								i = -1;
							} else if (vp.match1.isKnot && vp.shouldJoinKnot((Knot) vp.match1)) {

								knotFlag = true;
								System.out.println("Should JOIN JNOT");
								System.out.println(runList);
								makeHalfKnot(runList, vp, vp.match1);
								System.out.println(runList);
								i = -1;

							} else if (vp.isKnot && vp.match1.shouldKnotConsume((Knot) vp)) {

								knotFlag = true;
								System.out.println("Should JOIN JNOT");
								System.out.println(runList);
								makeHalfKnot(runList, vp.match1, vp);
								System.out.println(runList);
								i = -1;

							} else if (vp.isKnot && vp.match2 != null && vp.match2.shouldKnotConsume((Knot) vp)) {

								knotFlag = true;
								System.out.println("Should JOIN JNOT");
								System.out.println(runList);
								makeHalfKnot(runList, vp.match2, vp);
								System.out.println(runList);
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
		if (vpIdx < 0) {
			System.out.println(vp.fullString());
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

				VirtualPoint external1 = knot.match1;
				VirtualPoint external2 = knot.match2;

				if ((external1.getHeight() > 1 || knot.getHeight() > 1 || external2.getHeight() > 1)) {
					System.out.println("Need to simplify knots internally before matching : knot: " + knot
							+ " external1: " + external1 + " external2: " + external2);
					Knot knotNew = flattenKnots(knot, external1, external2, knotList);
					int prevIdx = knotList.indexOf(knotNew) - 1;
					if (prevIdx < 0) {
						prevIdx = knotList.size() - 1;
					}
					prevPoint = knotList.get(prevIdx);

					System.out.println(prevPoint);
					i = i - 1;
					continue;
				} else {
					updateSmallestKnot(knot);
					updateSmallestCommonKnot(knot);
					if (!flatKnots.containsKey(knot.id)) {
						flatKnots.put(knot.id, knot);
					}
				}

				CutMatchList cutMatchList = findCutMatchList(knot, external1, external2);
				external1.reset(knot);
				external2.reset(knot);

				System.out.println("===================================================");
				System.out.println(knot);
				System.out.println(knotList);
				System.out.println(cutMatchList);

				System.out.println("===================================================");
				ArrayList<CutMatch> cutMatches = cutMatchList.cutMatches;
				for (int j = 0; j < cutMatches.size(); j++) {
					CutMatch cutMatch = cutMatches.get(j);
					System.out.println("MAKING CUT: " + cutMatch);
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
						System.out.println(match1);
						System.out.println(match2);
						if (!match1.hasMatch(match2, pMatch2, pMatch1, matchSegment)) {
							match1.setMatch2(match2, pMatch2, pMatch1, matchSegment);
						}
						if (!match2.hasMatch(match1, pMatch1, pMatch2, matchSegment)) {
							match2.setMatch2(match1, pMatch1, pMatch2, matchSegment);
						}

					}
				}

				System.out.println("external1 final: " + external1.fullString());
				System.out.println("external2 final: " + external2.fullString());
				knotList.remove(vp);
				CutMatch finalCut = cutMatchList.cutMatches.get(0);
				VirtualPoint addPoint = finalCut.kp2;
				if (finalCut.kp1.match1.equals(prevPoint) || finalCut.kp1.match2.equals(prevPoint)) {
					addPoint = finalCut.kp1;
				}
				System.out.println("Prev: " + prevPoint);
				VirtualPoint prevPointTemp = prevPoint;
				for (int j = 0; j < knot.knotPoints.size(); j++) {
					System.out.println("adding: " + addPoint.fullString());
					knotList.add(i + j, addPoint);
					if (prevPointTemp.equals(addPoint.match2)) {
						prevPointTemp = addPoint;
						addPoint = addPoint.match1;
					} else {
						prevPointTemp = addPoint;
						addPoint = addPoint.match2;
					}
				}
				System.out.println(flatKnots);
				System.out.println(knotList);

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

		System.out.println(" " + resolved / totalCalls * 100 + " %");
		return knotList;
	}

	class CutMatch {
		private ArrayList<Segment> cutSegments;
		private ArrayList<Segment> matchSegments;
		private Knot knot;
		VirtualPoint kp1;
		VirtualPoint kp2;
		CutMatch diff;
		double delta;
		Knot superKnot;

		public CutMatch() {
			cutSegments = new ArrayList<>();
			matchSegments = new ArrayList<>();
		}

		public void updateDelta() {
			delta = 0;
			for (Segment s : cutSegments) {
				delta -= s.distance;
			}
			for (Segment s : matchSegments) {
				delta += s.distance;
			}

		}

		public void checkValid() {
			for (Segment s : cutSegments) {
				if (matchSegments.contains(s)) {
					System.out.println(this);
					float zero = 1 / 0;
				}
			}
			for (Segment s : matchSegments) {
				if (cutSegments.contains(s)) {
					System.out.println(this);
					float zero = 1 / 0;
				}
			}
			if (superKnot != null) {
				for (Segment s : matchSegments) {
					if (!superKnot.contains(s.getOtherKnot(knot))) {
						System.out.println(this);
						System.out.println(s);
						System.out.println(knot);
						System.out.println(superKnot);
						// float z = 1 / 0;
					}
				}
			}

			if (superKnot == null) {
				ArrayList<Segment> knotSegments = new ArrayList<>();
				for (int a = 0; a < knot.knotPoints.size(); a++) {
					VirtualPoint knotPoint11 = knot.knotPoints.get(a);
					VirtualPoint knotPoint12 = knot.knotPoints.get(a + 1 >= knot.knotPoints.size() ? 0 : a + 1);
					Segment s = knot.getSegment(knotPoint11, knotPoint12);
					knotSegments.add(s);
				}

				for (Segment s : matchSegments) {
					if (knotSegments.contains(s)) {
						System.out.println(this);
						System.out.println(s);
						System.out.println(knot);
						System.out.println(knotSegments);
						float z = 1 / 0;
					}
				}
			}
		}

		public String toString() {
			String str = "CM[\n" +
					"cutSegments: " + cutSegments + " \n" +
					"matchSegments: " + matchSegments + " \n" +
					"knot: " + knot + " \n" +
					"super: " + superKnot + " \n" +
					"diff: " + diff + " \n" +
					"delta: " + delta + " \n]";
			return str;

		}

		public CutMatch copy() {
			CutMatch copy = new CutMatch();
			copy.knot = knot;
			copy.delta = delta;
			copy.cutSegments.addAll(cutSegments);
			copy.matchSegments.addAll(matchSegments);
			if (diff != null) {
				copy.diff = diff.copy();
			}
			copy.superKnot = superKnot;
			copy.kp1 = kp1;
			copy.kp2 = kp2;
			return copy;
		}
	}

	class CutMatchList {

		ArrayList<CutMatch> cutMatches;
		double delta;

		public CutMatchList() {
			cutMatches = new ArrayList<>();
		}

		public String toString() {
			String str = "CML[\n" + cutMatches + " \n]\n totalDelta: " + delta;
			return str;

		}

		public void addCut(Segment cutSegment, Segment matchSegment1, Segment matchSegment2, Knot knot,
				VirtualPoint kp1, VirtualPoint kp2) {
			CutMatch cm = new CutMatch();
			cm.cutSegments.add(cutSegment);
			cm.matchSegments.add(matchSegment1);
			cm.matchSegments.add(matchSegment2);
			cm.knot = knot;
			cm.kp1 = kp1;
			cm.kp2 = kp2;
			cm.updateDelta();
			cm.checkValid();
			delta += cm.delta;
			cutMatches.add(cm);
		}

		public void addCut(Segment cutSegment, Segment matchSegment2, Knot knot, VirtualPoint kp1, VirtualPoint kp2,
				Knot superKnot) {
			CutMatch cm = new CutMatch();
			cm.matchSegments.add(matchSegment2);
			cm.knot = knot;
			cm.kp1 = kp1;
			cm.kp2 = kp2;
			cm.superKnot = superKnot;
			if (knot.knotPointsFlattened.size() > 3) {
				CutMatch diff = diffKnots(knot, superKnot, cm, cutSegment);
				cm.diff = diff;
				cm.cutSegments.addAll(diff.cutSegments);
				cm.matchSegments.addAll(diff.matchSegments);
			}
			cm.updateDelta();
			cm.checkValid();
			delta += cm.delta;
			cutMatches.add(cm);
		}

		public void addTwoCut(Segment cutSegment, Segment cutSegment2, Segment matchSegment1, Segment matchSegment2,
				Knot knot, VirtualPoint kp1, VirtualPoint kp2, CutMatchList cml) {
			CutMatch cm = new CutMatch();
			cm.cutSegments.add(cutSegment);
			cm.cutSegments.add(cutSegment2);
			cm.matchSegments.add(matchSegment1);
			cm.matchSegments.add(matchSegment2);
			cm.knot = knot;
			cm.kp1 = kp1;
			cm.kp2 = kp2;
			cutMatches.add(cm);
			for (CutMatch m : cml.cutMatches) {
				if (m.knot == knot) {
					cm.matchSegments.addAll(m.matchSegments);
					cm.cutSegments.addAll(m.cutSegments);
				} else {
					cutMatches.add(m);
				}
			}
			this.updateDelta();
			cm.checkValid();
		}

		public void addTwoCut(Segment cutSegment, Segment cutSegment2, Segment matchSegment2,
				Knot knot, VirtualPoint kp1, VirtualPoint kp2, CutMatchList cml, Knot superKnot) {
			CutMatch cm = new CutMatch();
			cm.cutSegments.add(cutSegment2);
			cm.matchSegments.add(matchSegment2);
			cm.knot = knot;
			cm.kp1 = kp1;
			cm.kp2 = kp2;
			cm.superKnot = superKnot;
			cutMatches.add(cm);
			for (CutMatch m : cml.cutMatches) {
				if (m.knot == knot) {
					cm.matchSegments.addAll(m.matchSegments);
					cm.matchSegments.addAll(m.cutSegments);
				} else {
					cutMatches.add(m);
				}
			}
			if (knot.knotPointsFlattened.size() > 3) {
				CutMatch diff = diffKnots(knot, superKnot, cm, cutSegment);
				cm.cutSegments.addAll(diff.cutSegments);
				cm.matchSegments.addAll(diff.matchSegments);
				cm.diff = diff;
			}
			this.updateDelta();
			cm.checkValid();
		}

		public void addSimpleMatch(Segment matchSegment, Knot knot) {
			CutMatch cm = new CutMatch();
			cm.matchSegments.add(matchSegment);
			cm.knot = knot;
			cm.updateDelta();
			cm.checkValid();
			delta += cm.delta;
			cutMatches.add(cm);
		}

		public CutMatch diffKnots(Knot subKnot, Knot superKnot, CutMatch cm, Segment cutSegment) {
			if (subKnot.equals(superKnot)) {
				return new CutMatch();
			}
			ArrayList<Segment> subKnotSegments = new ArrayList<>();
			ArrayList<Segment> diffList = new ArrayList<>();
			for (int a = 0; a < subKnot.knotPoints.size(); a++) {
				VirtualPoint knotPoint11 = subKnot.knotPoints.get(a);
				VirtualPoint knotPoint12 = subKnot.knotPoints.get(a + 1 >= subKnot.knotPoints.size() ? 0 : a + 1);
				Segment s = subKnot.getSegment(knotPoint11, knotPoint12);
				subKnotSegments.add(s);

			}
			ArrayList<Segment> superKnotSegments = new ArrayList<>();
			ArrayList<Segment> diffList2 = new ArrayList<>();
			for (int a = 0; a < superKnot.knotPoints.size(); a++) {
				VirtualPoint knotPoint11 = superKnot.knotPoints.get(a);
				VirtualPoint knotPoint12 = superKnot.knotPoints.get(a + 1 >= superKnot.knotPoints.size() ? 0 : a + 1);
				Segment s = superKnot.getSegment(knotPoint11, knotPoint12);
				superKnotSegments.add(s);
				if (!subKnotSegments.contains(s) && !cm.matchSegments.contains(s) && subKnot.contains(knotPoint11)
						&& subKnot.contains(knotPoint12)) {

					diffList2.add(s);
				}
			}

			for (Segment s : subKnotSegments) {
				if (!superKnotSegments.contains(s) && !cm.cutSegments.contains(s) && !s.equals(cutSegment)) {
					diffList.add(s);
				}
			}
			System.out.println("Diff Super -> Sub");
			System.out.println(diffList2);
			System.out.println("Diff Sub -> Super");
			System.out.println(diffList);
			ArrayList<Segment> toRemoveCuts = new ArrayList<>();
			for (Segment s : cm.cutSegments) {
				if (!superKnotSegments.contains(s)) {
					toRemoveCuts.add(s);
				}
			}
			cm.cutSegments.removeAll(toRemoveCuts);
			ArrayList<Segment> toRemoveMatches = new ArrayList<>();
			for (Segment s : cm.matchSegments) {
				if (superKnotSegments.contains(s)) {
					toRemoveMatches.add(s);
				}
			}
			cm.matchSegments.removeAll(toRemoveMatches);
			CutMatch cmNew = new CutMatch();

			cmNew.cutSegments.addAll(diffList2);
			cmNew.matchSegments.addAll(diffList);
			cmNew.knot = superKnot;
			cmNew.updateDelta();
			cmNew.checkValid();
			return cmNew;
		}

		public void updateDelta() {
			delta = 0;
			for (CutMatch cm : cutMatches) {
				cm.updateDelta();
				delta += cm.delta;
			}
		}

		public void addNeighborCut(Segment neighborCut, Knot knot, CutMatchList cml) {
			CutMatch cm = new CutMatch();
			cm.cutSegments.add(neighborCut);
			cm.knot = knot;

			for (CutMatch m : cml.cutMatches) {
				if (m.knot == knot) {
					cm.matchSegments.addAll(m.matchSegments);
					cm.matchSegments.addAll(m.cutSegments);
				} else {
					cutMatches.add(m);
				}
			}
			cutMatches.add(cm);
			this.updateDelta();
			cm.checkValid();
		}

		public boolean hasMatch(Segment s) {
			for (CutMatch cm : cutMatches) {
				if (cm.matchSegments.contains(s)) {
					return true;
				}
			}
			return false;
		}

		public boolean hasMatchWith(VirtualPoint vp) {
			for (CutMatch cm : cutMatches) {
				for (Segment s : cm.matchSegments) {
					if (s.contains(vp)) {
						return true;
					}
				}
			}
			return false;
		}

		public void removeMatch(Segment s) {
			for (CutMatch cm : cutMatches) {
				if (cm.matchSegments.contains(s)) {
					cm.matchSegments.remove(s);
				}
			}
			this.updateDelta();
		}

		public CutMatchList copy() {
			CutMatchList copy = new CutMatchList();
			copy.delta = delta;
			for (CutMatch cm : cutMatches) {
				CutMatch copyCM = cm.copy();
				copy.cutMatches.add(copyCM);

			}
			return copy;
			// TODO Auto-generated method stub
		}

	}

	private CutMatchList findCutMatchList(Knot knot, VirtualPoint external1, VirtualPoint external2) {
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
				if (cutSegment2 == null) {
					System.out.println(external1);

					System.out.println(external2);
					System.out.println(knot);
				}
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
					CutMatchList internalCuts1 = calculateInternalPathLength(knotPoint12, knotPoint22,
							external1,
							external2, knotPoint11, knotPoint21, knot);
					double d1 = s11.distance + s12.distance + internalCuts1.delta - cutSegment1.distance
							- cutSegment2.distance;
					delta = d1 < delta ? d1 : delta;
					System.out.println(
							"s11: " + s11 + " s12: " + s12 + " cut1: " + cutSegment1 + " cut2: " + cutSegment2);
					System.out.println("d1: " + d1);
					Segment s21 = knotPoint21.getClosestSegment(external1, null);
					Segment s22 = knotPoint11.getClosestSegment(external2, s21);
					CutMatchList internalCuts2 = calculateInternalPathLength(knotPoint12, knotPoint22,
							external1, external2, knotPoint11, knotPoint21, knot);
					double d2 = s21.distance + s22.distance + internalCuts2.delta - cutSegment1.distance
							- cutSegment2.distance;
					delta = d2 < delta ? d2 : delta;
					System.out.println(
							"s21: " + s21 + " s22: " + s22 + " cut1: " + cutSegment1 + " cut2: " + cutSegment2);
					System.out.println("d2: " + d2);

					Segment s31 = knotPoint12.getClosestSegment(external1, null);
					Segment s32 = knotPoint22.getClosestSegment(external2, s31);
					CutMatchList internalCuts3 = calculateInternalPathLength(knotPoint11, knotPoint21,
							external1,
							external2, knotPoint12, knotPoint22, knot);
					double d3 = s31.distance + s32.distance + internalCuts3.delta - cutSegment1.distance
							- cutSegment2.distance;
					delta = d3 < delta ? d3 : delta;
					System.out.println(
							"s31: " + s31 + " s32: " + s32 + " cut1: " + cutSegment1 + " cut2: " + cutSegment2);
					System.out.println("d3: " + d3);
					if (knotPoint12.id == 11 && knotPoint22.id == 14 && knotPoint11.id == 12 && knotPoint21.id == 0) {
						System.out.println(
								internalCuts3.cutMatches);
						// float z = 1 / 0;
					}

					Segment s41 = knotPoint22.getClosestSegment(external1, null);
					Segment s42 = knotPoint12.getClosestSegment(external2, s41);
					CutMatchList internalCuts4 = calculateInternalPathLength(knotPoint11, knotPoint21,
							external1,
							external2, knotPoint12, knotPoint22, knot);

					double d4 = s41.distance + s42.distance + internalCuts4.delta - cutSegment1.distance
							- cutSegment2.distance;
					delta = d4 < delta ? d4 : delta;
					System.out.println(
							"s41: " + s41 + " s42: " + s42 + " cut1: " + cutSegment1 + " cut2: " + cutSegment2);
					System.out.println("d4: " + d4);

					// TODO: We aren't considering when this is the best cut and we'd need to
					// recisviely cut down from the orphaned points

					Segment s51 = knotPoint11.getClosestSegment(external1, null);
					Segment s52 = knotPoint22.getClosestSegment(external2, s51);
					Segment s53 = knotPoint12.getClosestSegment(knotPoint21, null);
					double d5 = s51.distance + s52.distance;

					System.out.println(
							"s51: " + s51 + " s52: " + s52 + " cut1: " + cutSegment1 + " cut2: " + cutSegment2);
					System.out.println("d5: " + d5 + cutSegment1.distance + cutSegment2.distance);

					Segment s61 = knotPoint12.getClosestSegment(external1, null);
					Segment s62 = knotPoint21.getClosestSegment(external2, s61);
					Segment s63 = knotPoint11.getClosestSegment(knotPoint22, null);
					double d6 = s61.distance + s62.distance;

					System.out.println(
							"s61: " + s61 + " s62: " + s62 + " cut1: " + cutSegment1 + " cut2: " + cutSegment2);
					System.out.println("d6: " + d6 + cutSegment1.distance + cutSegment2.distance);

					if (delta < minDelta) {
						if (delta == d1) {
							matchSegment1Final = s11;
							matchSegment2Final = s12;
							knotPoint1Final = knotPoint11;
							knotPoint2Final = knotPoint21;
							internalCuts = internalCuts1;
							cutSegmentFinal = cutSegment1;
							cutSegment2Final = cutSegment2;
							segmentName = "d1";
						} else if (delta == d2) {
							matchSegment1Final = s21;
							matchSegment2Final = s22;
							knotPoint1Final = knotPoint21;
							knotPoint2Final = knotPoint11;
							internalCuts = internalCuts2;
							cutSegmentFinal = cutSegment2;
							cutSegment2Final = cutSegment1;
							segmentName = "d2";
						} else if (delta == d3) {
							matchSegment1Final = s31;
							matchSegment2Final = s32;
							knotPoint1Final = knotPoint12;
							knotPoint2Final = knotPoint22;
							internalCuts = internalCuts3;
							cutSegmentFinal = cutSegment1;
							cutSegment2Final = cutSegment2;
							segmentName = "d3";
						} else {
							matchSegment1Final = s41;
							matchSegment2Final = s42;
							knotPoint1Final = knotPoint22;
							knotPoint2Final = knotPoint12;
							internalCuts = internalCuts4;
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
			CutMatchList result = new CutMatchList();
			result.addCut(cutSegmentFinal, matchSegment1Final, matchSegment2Final, knot, knotPoint1Final,
					knotPoint2Final);
			return result;
		} else {
			CutMatchList result = new CutMatchList();
			result.addTwoCut(cutSegmentFinal, cutSegment2Final, matchSegment1Final, matchSegment2Final, knot,
					knotPoint1Final, knotPoint2Final,
					internalCuts);

			return result;

		}

	}

	MultiKeyMap<Integer, CutMatchList> cutLookup = new MultiKeyMap<>();
	double resolved = 0;
	double totalCalls = 0;

	private CutMatchList findCutMatchListFixedCut(Knot knot, VirtualPoint external1,
			VirtualPoint external2, Segment cutSegment1, VirtualPoint kp1, VirtualPoint cp1, Knot superKnot) {


		totalCalls++;
		if (cutLookup.containsKey(knot.id, external2.id, kp1.id, cp1.id, superKnot.id)) {
			resolved++;
			return cutLookup.get(knot.id, external2.id, kp1.id, cp1.id, superKnot.id).copy();
		}

		if (!(knot.contains(cutSegment1.first) && knot.contains(cutSegment1.last))) {
			float z = 1 / 0;
		}
		if ((knot.contains(external1) || knot.contains(external2))) {
			float z = 1 / 0;
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
			if (cutSegment2 == null) {
				System.out.println(external1);

				System.out.println(external2);
				System.out.println(knot);
			}
			if (cutSegment1.partialOverlaps(cutSegment2)) {
				System.out.println("partial overlap: " + cutSegment1 + " " + cutSegment2);
				continue;
			}
			if (cutSegment1.equals(cutSegment2)) {

				Segment s11 = kp1.getClosestSegment(external1, null);
				Segment s12 = cp1.getClosestSegment(external2, s11);
				double d1 = s12.distance;

				double delta = d1;
				if (delta < minDelta) {
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

				CutMatchList internalCuts1 = null;
				double d1 = Double.MAX_VALUE;
				if (!orphanFlag) {
					internalCuts1 = calculateInternalPathLength(cp1, cp2, external1, external2, kp1, kp2, knot);
					d1 = s12.distance + internalCuts1.delta - cutSegment2.distance;
					delta = d1 < delta ? d1 : delta;
				}

				System.out.println(
						"s11: " + s11 + " s12: " + s12 + " cut1: " + cutSegment1 + " cut2: " + cutSegment2);

				boolean orphanFlag2 = wouldOrphan(cp1, kp1, kp2, cp2, knot.knotPointsFlattened);

				Segment s21 = kp1.getClosestSegment(external1, null);
				Segment s22 = cp2.getClosestSegment(external2, s21);

				CutMatchList internalCuts2 = null;
				double d2 = Double.MAX_VALUE;
				if (!orphanFlag2) {
					internalCuts2 = calculateInternalPathLength(cp1, kp2, external1, external2, kp1, cp2, knot);
					d2 = s22.distance + internalCuts2.delta - cutSegment2.distance;
					delta = d2 < delta ? d2 : delta;

				}

				System.out.println(
						"s11: " + s11 + " s12: " + s12 + " cut1: " + cutSegment1 + " cut2: " + cutSegment2);

				if (delta < minDelta) {
					if (!orphanFlag) {
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
			CutMatchList result = new CutMatchList();
			System.out.println(cutSegmentFinal);
			System.out.println(minDelta);
			System.out.println(knot.knotPointsFlattened.size());
			System.out.println(matchSegment2Final);
			result.addCut(cutSegmentFinal, matchSegment2Final, knot, knotPoint1Final, knotPoint2Final, superKnot);
			cutLookup.put(knot.id, external2.id, kp1.id, cp1.id,
					superKnot.id, result);
			return result;
		} else if (overlapping == 2) {
			CutMatchList result = new CutMatchList();
			result.addTwoCut(cutSegmentFinal, cutSegment2Final, matchSegment2Final, knot, knotPoint1Final,
					knotPoint2Final, internalCuts, superKnot);
			cutLookup.put(knot.id, external2.id, kp1.id, cp1.id,
					superKnot.id, result);
			return result;

		} else {
			float z = 1 / 0;
			return null;
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

	private CutMatchList calculateInternalPathLength(VirtualPoint cutPointA, VirtualPoint cutPointB,
			VirtualPoint external1, VirtualPoint external2, VirtualPoint knotPoint1, VirtualPoint knotPoint2,
			Knot knot) {

		System.out.println("recutting knot: " + knot);
		System.out.println(
				"knotPoint1: " + knotPoint1 + " external1: " + external1);
		System.out.println(
				"knotPoint2: " + knotPoint2 + " external2: " + external2);
		System.out.println(
				"cutPointA: " + cutPointA + " cutPointB: " + cutPointB);
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
		int smallestCommonKnotId = smallestCommonKnotLookup[cutPointA.id][cutPointB.id];
		int smallestKnotIdA = smallestKnotLookup[cutPointA.id];
		int smallestKnotIdB = smallestKnotLookup[cutPointB.id];
		System.out.println(
				"flatKnots: " + flatKnots);
		Knot smallestCommonKnot = flatKnots.get(smallestCommonKnotId);
		System.out.println(
				"smallestCommonKnot: " + smallestCommonKnot);

		System.out.println(cutPointA.id);

		Knot topKnot = flatKnots.get(smallestKnotIdA);
		VirtualPoint topPoint = cutPointA;

		Knot botKnot = flatKnots.get(smallestKnotIdB);
		VirtualPoint botPoint = cutPointB;

		if (topKnot.knotPointsFlattened.size() < botKnot.knotPointsFlattened.size()) {
			Knot tmpK = topKnot;
			VirtualPoint tmp = topPoint;
			topKnot = botKnot;
			botKnot = tmpK;
			topPoint = botPoint;
			botPoint = tmp;
		}

		System.out.println(
				"smallestKnotA: " + flatKnots.get(smallestKnotIdA).fullString());
		System.out.println(
				"smallestKnotB: " + flatKnots.get(smallestKnotIdB).fullString());

		// if common knot size is 4 or lower then simply match across?
		// should store the result of cutting a specific segment in knot?

		//
		// how to tell if knot is already fully determined?

		int matchKnotId = smallestCommonKnotLookup[knotPoint1.id][knotPoint2.id];
		Knot matchKnot = flatKnots.get(matchKnotId);

		System.out.println(
				"smallestmatchKnotA: " + flatKnots.get(smallestKnotLookup[knotPoint1.id]).fullString());
		System.out.println(
				"smallestmatchKnotB: " + flatKnots.get(smallestKnotLookup[knotPoint2.id]).fullString());
		System.out.println(
				"matchKnot " + matchKnot.fullString());

		// if both orphans are on the top level, then we can simply match across
		if (topKnot.equals(botKnot) || knot.knotPointsFlattened.size() <= 3) {
			System.out.println("knot: " + knot + "is fully realized");
			Segment connector = cutPointA.getClosestSegment(cutPointB, null);
			CutMatchList cutMatchList = new CutMatchList();
			cutMatchList.addSimpleMatch(connector, knot);
			return cutMatchList;
		}

		if (topKnot.equals(knot)) {
			System.out.println("Topknot equals knot");
			CutMatchList reCut;
			if (topPoint.equals(cutPointA)) {
				Segment cut = botKnot.getSegment(botPoint, knotPoint2);
				Knot minKnot = flatKnots.get(smallestCommonKnotLookup[botPoint.id][knotPoint2.id]);
				VirtualPoint kp = knotPoint2;
				VirtualPoint ex = external2;
				VirtualPoint vp = botPoint;
				System.out.println(cut);
				if (minKnot.equals(knot)) {
					minKnot = flatKnots.get(smallestCommonKnotLookup[botPoint.id][knotPoint1.id]);
					kp = knotPoint1;
					int idxKp = minKnot.knotPoints.indexOf(kp);
					VirtualPoint prev = minKnot.knotPoints
							.get(idxKp - 1 < 0 ? minKnot.knotPoints.size() - 1 : idxKp - 1);
					VirtualPoint next = minKnot.knotPoints.get(idxKp + 1 >= minKnot.knotPoints.size() ? 0 : idxKp + 1);
					boolean prevMatch = kp.hasMatch(prev);
					boolean nextMatch = kp.hasMatch(next);
					if (nextMatch && !prevMatch) {
						cut = minKnot.getSegment(prev, kp);
					} else if (!nextMatch && prevMatch) {
						cut = minKnot.getSegment(next, kp);
					} else {
						if (next.contains(cutPointA)) {
							cut = minKnot.getSegment(next, kp);
						} else if (prev.contains(cutPointA)) {
							cut = minKnot.getSegment(prev, kp);
						} else if (prev.contains(cutPointB)) {
							cut = minKnot.getSegment(prev, kp);
						} else if (next.contains(cutPointB)) {
							cut = minKnot.getSegment(next, kp);
						} else {
							float z = 1 / 0;
						}
					}
					ex = external1;
					vp = cut.getOther(kp);
				}
				System.out.println("REEEEEEEEEEEEEEEE: " + minKnot);
				System.out.println(cut);
				System.out.println(kp.fullString());
				System.out.println(ex);
				System.out.println(vp);
				System.out.println(topPoint);
				reCut = findCutMatchListFixedCut(minKnot, ex, topPoint, cut, kp, vp, knot);
			} else {
				Segment cut = botKnot.getSegment(botPoint, knotPoint1);
				Knot minKnot = flatKnots.get(smallestCommonKnotLookup[botPoint.id][knotPoint1.id]);
				VirtualPoint kp = knotPoint1;
				VirtualPoint ex = external1;
				VirtualPoint vp = botPoint;
				if (minKnot.equals(knot)) {
					minKnot = flatKnots.get(smallestCommonKnotLookup[botPoint.id][knotPoint2.id]);
					kp = knotPoint2;
					int idxKp = minKnot.knotPoints.indexOf(kp);
					VirtualPoint prev = minKnot.knotPoints
							.get(idxKp - 1 < 0 ? minKnot.knotPoints.size() - 1 : idxKp - 1);
					VirtualPoint next = minKnot.knotPoints.get(idxKp + 1 >= minKnot.knotPoints.size() ? 0 : idxKp + 1);
					boolean prevMatch = kp.hasMatch(prev);
					boolean nextMatch = kp.hasMatch(next);
					System.out.println(kp);
					System.out.println(prev);
					System.out.println(next);
					if (nextMatch && !prevMatch) {
						cut = minKnot.getSegment(prev, kp);
					} else if (!nextMatch && prevMatch) {
						cut = minKnot.getSegment(next, kp);
					} else {
						if (next.contains(cutPointA)) {
							cut = minKnot.getSegment(next, kp);
						} else if (prev.contains(cutPointA)) {
							cut = minKnot.getSegment(prev, kp);
						} else if (prev.contains(cutPointB)) {
							cut = minKnot.getSegment(prev, kp);
						} else if (next.contains(cutPointB)) {
							cut = minKnot.getSegment(next, kp);
						} else {
							float z = 1 / 0;
						}
					}
					ex = external2;
					vp = cut.getOther(kp);
				}
				System.out.println("2REEEEEEEEEEEEEEEE: " + minKnot);
				System.out.println(cut);
				System.out.println(kp.fullString());
				System.out.println(ex);
				System.out.println(vp);
				System.out.println(topPoint);
				reCut = findCutMatchListFixedCut(minKnot, ex, topPoint, cut, kp, vp, knot);
			}
			// TODO::::::::when cutting a sub knot we need to add the distance any segments
			// and their
			// distances that are not in the super knot
			System.out.println(reCut);
			boolean breakFlag2 = false;
			int cp11 = 17;
			int kp11 = 1;
			int cp21 = 0;
			int kpp21 = 20;
			if (breakFlag2 && cutPointA.id == cp11 && knotPoint1.id == kp11 && cutPointB.id == cp21
					&& knotPoint2.id == kpp21) {
				float z = 1 / 0;
			}
			if (breakFlag2 && cutPointA.id == cp21 && knotPoint1.id == kpp21 && cutPointB.id == cp11
					&& knotPoint2.id == kp11) {
				float z = 1 / 0;
			}
			return reCut;
			// if orphan A is on the top level and orphan B is not, then recut the orphan
			// B's knot with its two externals as orphan A and whatever is matching orphan
			// B's knot

		} else {

			// if neither orphan is on the top level, find their minimal knot in common and
			// recut it with the external that matched to the knot and its still matched
			// neighbor
			System.out.println("smk in larger structure");
			CutMatchList reCut;
			VirtualPoint neighbor;
			if (topPoint.equals(cutPointA)) {

				int minKnotId = smallestCommonKnotLookup[botPoint.id][knotPoint2.id];
				Knot minKnot = flatKnots.get(minKnotId);
				VirtualPoint kp = knotPoint2;
				VirtualPoint kp2 = knotPoint1;
				VirtualPoint vp = botPoint;
				VirtualPoint vp2 = topPoint;
				VirtualPoint ex = external2;
				if (minKnot.equals(knot)) {
					minKnotId = smallestCommonKnotLookup[topPoint.id][knotPoint1.id];
					minKnot = flatKnots.get(minKnotId);
					kp = knotPoint1;
					kp2 = knotPoint2;
					vp = topPoint;
					vp2 = botPoint;
					ex = external1;
					System.out.println(minKnot);
					if (minKnot.equals(knot)) {
						Segment connector = cutPointA.getClosestSegment(cutPointB, null);
						CutMatchList cutMatchList = new CutMatchList();
						cutMatchList.addSimpleMatch(connector, knot);
						return cutMatchList;
					}
				}
				// this should actually be more like if minknot doesn't contain vp2 and neighbor
				// doesn't then go up a level
				if (smallestCommonKnot.equals(knot)) {
					neighbor = vp2;
				} else if (minKnot.match1endpoint.contains(kp2) && minKnot.match2endpoint.contains(kp2)) {
					neighbor = matchKnot.match1endpoint;
				} else {
					if (minKnot.match1endpoint.contains(kp2)) {
						neighbor = minKnot.match2endpoint;
					} else {
						neighbor = minKnot.match1endpoint;
					}
				}
				Segment cut = minKnot.getSegment(vp, kp);
				System.out.println(neighbor);
				System.out.println(cut);
				System.out.println(minKnot.fullString());
				System.out.println(vp);
				System.out.println(kp);
				System.out.println(kp2);

				System.out.println("FIXED CUT: " + cut);
				Segment neighborCut = null;
				if (minKnot.contains(neighbor.match1endpoint)) {
					neighborCut = neighbor.s1;
				} else if (minKnot.contains(neighbor.match2endpoint)) {
					neighborCut = neighbor.s2;
				} else {
					System.out.println(minKnot.fullString());
					System.out.println(neighbor.fullString());
					// float z = 1/0;
				}

				reCut = findCutMatchListFixedCut(minKnot, ex, neighbor, cut, kp, vp, knot);
				if (neighborCut != null && !neighborCut.equals(knot.getSegment(cutPointA, knotPoint1))
						&& !neighborCut.equals(knot.getSegment(cutPointB, knotPoint2))) {
					if (reCut.hasMatchWith(neighbor)) {

						System.out.println("NIEGHBOR CUT: " + neighborCut);
						System.out.println(knot);
						System.out.println(minKnot);
						System.out.println(knot.getSegment(cutPointA, knotPoint1));
						System.out.println(knot.getSegment(cutPointB, knotPoint2));
						System.out.println(reCut);
						System.out.println(neighborCut);
						CutMatchList cutMatchList = new CutMatchList();
						cutMatchList.addNeighborCut(neighborCut, knot, reCut);
						reCut = cutMatchList;
						reCut.updateDelta();
					} else {
						reCut.removeMatch(neighborCut);
					}
				}
				System.out.println(reCut);
				boolean breakFlag = true;
				int cp1 = 14;
				int kp1 = 0;
				int cp2 = 1;
				int kpp2 = 11;
				if (breakFlag && cutPointA.id == cp1 && knotPoint1.id == kp1 && cutPointB.id == cp2
						&& knotPoint2.id == kpp2) {
					float z = 1 / 0;
				}
				if (breakFlag && cutPointA.id == cp2 && knotPoint1.id == kpp2 && cutPointB.id == cp1
						&& knotPoint2.id == kp1) {
					float z = 1 / 0;
				}
				boolean flag = false;
				System.out.println(reCut);

			} else {
				int minKnotId = smallestCommonKnotLookup[botPoint.id][knotPoint1.id];
				Knot minKnot = flatKnots.get(minKnotId);
				VirtualPoint kp = knotPoint1;
				VirtualPoint kp2 = knotPoint2;
				VirtualPoint vp = botPoint;
				VirtualPoint vp2 = topPoint;
				VirtualPoint ex = external1;
				if (minKnot.equals(knot)) {
					minKnotId = smallestCommonKnotLookup[topPoint.id][knotPoint2.id];
					minKnot = flatKnots.get(minKnotId);
					kp = knotPoint2;
					kp2 = knotPoint1;
					vp = topPoint;
					vp2 = botPoint;
					ex = external2;
					System.out.println(minKnot);
					if (minKnot.equals(knot)) {
						Segment connector = cutPointA.getClosestSegment(cutPointB, null);
						CutMatchList cutMatchList = new CutMatchList();
						cutMatchList.addSimpleMatch(connector, knot);
						return cutMatchList;
					}
				}
				if (smallestCommonKnot.equals(knot)) {
					neighbor = vp2;
				} else if (minKnot.match1endpoint.contains(kp2) && minKnot.match2endpoint.contains(kp2)) {
					neighbor = matchKnot.match1endpoint;
				} else {
					if (minKnot.match1endpoint.contains(kp2)) {
						neighbor = minKnot.match2endpoint;
					} else {
						neighbor = minKnot.match1endpoint;
					}
				}

				Segment neighborCut = null;
				if (minKnot.contains(neighbor.match1endpoint)) {
					neighborCut = neighbor.s1;
				} else if (minKnot.contains(neighbor.match2endpoint)) {
					neighborCut = neighbor.s2;
				} else {
					System.out.println(minKnot.fullString());
					System.out.println(neighbor.fullString());
					// float z = 1/0;
				}
				Segment cut = minKnot.getSegment(vp, kp);
				System.out.println(neighbor);
				System.out.println("FIXED CUT: " + cut);
				reCut = findCutMatchListFixedCut(minKnot, ex, neighbor, cut, kp, vp, knot);
				if (neighborCut != null) {
					if (reCut.hasMatchWith(neighbor) && !neighborCut.equals(knot.getSegment(cutPointA, knotPoint1))
							&& !neighborCut.equals(knot.getSegment(cutPointB, knotPoint2))) {
						System.out.println("NIEGHBOR2 CUT: " + neighborCut);
						CutMatchList cutMatchList = new CutMatchList();
						cutMatchList.addNeighborCut(neighborCut, knot, reCut);
						reCut = cutMatchList;
						reCut.updateDelta();
					} else {
						reCut.removeMatch(neighborCut);
					}
				}
				boolean breakFlag = true;
				int cp1 = 14;
				int kp1 = 0;
				int cp2 = 1;
				int kpp2 = 11;
				if (breakFlag && cutPointA.id == cp1 && knotPoint1.id == kp1 && cutPointB.id == cp2
						&& knotPoint2.id == kpp2) {
					float z = 1 / 0;
				}
				if (breakFlag && cutPointA.id == cp2 && knotPoint1.id == kpp2 && cutPointB.id == cp1
						&& knotPoint2.id == kp1) {
					float z = 1 / 0;
				}
				boolean flag = false;
				// for (Segment s : reCut.getSecond().matchSegments) {
				// if (s.first.equals(vp2) || s.last.equals(vp2)) {
				// flag = true;
				// }
				// }
				if (!flag) {
					System.out.println("failed knot: " + knot);
					System.out.println(
							"knotPoint1: " + knotPoint1 + " external1: " + external1);
					System.out.println(
							"knotPoint2: " + knotPoint2 + " external2: " + external2);
					System.out.println(
							"cutPointA: " + cutPointA + " cutPointB: " + cutPointB);
					System.out.println(vp2);
					System.out.println(neighbor);
					System.out.println(minKnot);
					System.out.println(knot);
					System.out.println(ex);
					System.out.println(cut);
					System.out.println(kp);
					System.out.println(vp);
					System.out.println(reCut);
					// ok, if we end up in this situation, it's because the underlying knot does not
					// match the overknot,
					// so I think we need to do the following:
					// for each neighbor of vp2 in the under knot, add connector segment to recut
					// matches
					// then in the overknot if they are not already in the cut list, or both match

					// it'd probably be more clear to rebuild the knot every time from its parts
					// to do that you could add all of the segments contained in the overknot to the
					// cut list
					// then add all of the non-external and non-cutsegment segments in the underknot
					// to the match list
					// float zero = 1 / 0;
				}
			}
			System.out.println(reCut);
			System.out.println(knot.fullString());
			return reCut;
		}
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

	public Knot flattenKnots(Knot knot, VirtualPoint external1, VirtualPoint external2,
			ArrayList<VirtualPoint> knotList) {

		ArrayList<VirtualPoint> flattenKnots = cutKnot(knot.knotPoints);
		Knot knotNew = new Knot(flattenKnots);
		knotNew.copyMatches(knot);
		flatKnots.put(knotNew.id, knotNew);
		updateSmallestCommonKnot(knotNew);
		System.out.println(flatKnots);

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
			flatKnots.put(external1New.id, external1New);
			updateSmallestCommonKnot(external1New);
			external1New.copyMatches(external1);
		}
		Knot external2Knot = null;
		ArrayList<VirtualPoint> flattenKnotsExternal2 = null;
		Knot external2New = null;
		if (makeExternal2) {

			external2Knot = (Knot) external2;
			flattenKnotsExternal2 = cutKnot(external2Knot.knotPoints);
			external2New = new Knot(flattenKnotsExternal2);
			external2New.copyMatches(external2);
			updateSmallestCommonKnot(external2New);
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
			System.out.println(external1New.fullString());
			System.out.println(external2New != null ? external2New.fullString() : "null");
			System.out.println(knotNew.fullString());
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

		System.out.println(external1New);
		System.out.println(external1);
		System.out.println(external2New);
		System.out.println(external2);
		System.out.println(knotNew);
		System.out.println(knotList);
		System.out.println(knotNew.fullString());
		return knotNew;
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
