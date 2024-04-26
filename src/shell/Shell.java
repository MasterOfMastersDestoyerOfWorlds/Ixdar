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

		public boolean contains(VirtualPoint vp) {
			throw new UnsupportedOperationException("Unimplemented method 'contains'");
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

		public Segment getPointer(int idx) {
			ArrayList<Segment> seenGroups = new ArrayList<Segment>();
			ArrayList<VirtualPoint> seenPoints = new ArrayList<VirtualPoint>();
			int count = idx;
			for (int i = 0; i < sortedSegments.size(); i++) {
				Segment s = sortedSegments.get(i);
				VirtualPoint basePoint = s.getOther(this);
				VirtualPoint vp = basePoint;
				if (vp.group != null) {
					vp = vp.group;
				}
				Segment potentialSegment = new Segment(basePoint, this, 0);
				if ((!vp.isRun || ((Run) vp).endpoint1.contains(basePoint) || ((Run) vp).endpoint2.contains(basePoint))
						&& (!seenGroups.contains(potentialSegment)) && (!seenPoints.contains(basePoint))
						|| potentialSegment.equals(s1) || potentialSegment.equals(s2)) {
					count--;
					if (count == 0) {
						return s;
					}
					seenGroups.add(potentialSegment);
				}
			}
			return null;
		}

		public Segment getClosestSegment(VirtualPoint vp) {
			for (int i = 0; i < sortedSegments.size(); i++) {
				Segment s = sortedSegments.get(i);
				if (vp.isKnot) {
					Knot knot = (Knot) vp;
					if (s.getKnotPoint(knot.knotPointsFlattened) != null) {
						return s;
					}
				} else {
					if (s.contains(vp)) {
						return s;
					}
				}
			}
			System.out.println(vp.fullString());
			assert (false);
			return null;
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
				Segment s = vp1.getClosestSegment(vp2);
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

			// if we have two knots that make up the whole knot we need to insert one of the
			// knots into the other
			// TODO: when we insert we need to make sure the externals still match
			boolean skipflag = false;
			if (skipflag && flattenRunPoints.size() == 2 && flattenRunPoints.get(0).isKnot
					&& flattenRunPoints.get(1).isKnot) {
				System.out.println("Knot, Knot found, need to insert one into the other");
				// need to loop through each segment in each knot and see which is most
				// beneficial
				// (current minus new)
				// to cut
				Knot gp1 = (Knot) flattenRunPoints.get(0);

				Knot gp2 = (Knot) gp1.match1endpoint.group;

				Point pointer1 = gp1.match1endpoint;
				VirtualPoint vKnotPoint1 = pointer1;
				VirtualPoint cutPoint1 = vKnotPoint1.match2;
				VirtualPoint vCutPoint1 = cutPoint1;
				ArrayList<VirtualPoint> newList = new ArrayList<>();
				System.out.println("Both are knots, find the two cut segments and join across");
				System.out.println(gp1.fullString());
				System.out.println(gp2.fullString());
				System.out.println(vKnotPoint1.fullString());
				// It's somthing about needing to like recursively insert into a knot sometimes
				// I think its happening when both of gp1's matches point to the same object and
				// that object is a knot?
				// I actually think this more has to do with making knots where we shouldn't out
				// of runs
				Segment sKP = gp1.getClosestSegment(vKnotPoint1);
				System.out.println(sKP);
				Point m1e = (Point) sKP.getOtherKnot(gp1);
				Point bp1 = (Point) sKP.getOther(m1e);
				if (vKnotPoint1.match2.equals(vCutPoint1)) {
					vKnotPoint1.match2 = gp1;
					vKnotPoint1.basePoint2 = m1e;
					vKnotPoint1.match2endpoint = bp1;
					vKnotPoint1.s1 = sKP;
				} else {
					vKnotPoint1.match1 = gp1;
					vKnotPoint1.basePoint1 = m1e;
					vKnotPoint1.match1endpoint = bp1;
					vKnotPoint1.s1 = sKP;
				}

				Segment sCP = gp1.getClosestSegment(vCutPoint1);
				System.out.println(sCP);
				Point m2e = (Point) sCP.getOtherKnot(gp1);
				Point bp2 = (Point) sCP.getOther(m2e);

				if (vCutPoint1.match2.equals(vKnotPoint1)) {
					vCutPoint1.match2 = gp1;
					vCutPoint1.basePoint2 = m2e;
					vCutPoint1.match2endpoint = bp2;
					vCutPoint1.s2 = sCP;
				} else {
					vCutPoint1.match1 = gp1;
					vCutPoint1.basePoint1 = m2e;
					vCutPoint1.match1endpoint = bp2;
					vCutPoint1.s1 = sCP;

				}

				if (sKP.distance < sCP.distance) {
					gp1.match1 = vKnotPoint1;
					gp1.basePoint1 = bp1;
					gp1.match1endpoint = m1e;
					gp1.s1 = sKP;
					gp1.match2 = vCutPoint1;
					gp1.basePoint2 = bp2;
					gp1.match2endpoint = m2e;
					gp1.s2 = sCP;
				} else {
					gp1.match2 = vKnotPoint1;
					gp1.basePoint2 = bp1;
					gp1.match2endpoint = m1e;
					gp1.s2 = sKP;
					gp1.match1 = vCutPoint1;
					gp1.basePoint1 = bp2;
					gp1.match1endpoint = m2e;
					gp1.s1 = sCP;
				}
				// need to bubble this info down
				gp1.group = gp2;
				gp1.topGroup = gp2.topGroup;
				// need to bubble this info up
				int index1 = gp2.knotPoints.indexOf(vKnotPoint1);
				int index2 = gp2.knotPoints.indexOf(gp1.match2);
				int insertIndex = Math.min(index1, index2);
				if (Math.abs(index1 - index2) > 1) {
					insertIndex = gp2.knotPoints.size() - 1;
				}
				gp2.knotPoints.add(insertIndex + 1, gp1);
				gp2.knotPointsFlattened.addAll(gp1.knotPointsFlattened);
				Knot finalKnot = ((Knot) flattenRunPoints.get(1));
				Knot topKnot = ((Knot) gp2.topGroupVirtualPoint);
				boolean topKnotUpdate = false;
				for (VirtualPoint vp : finalKnot.knotPoints) {
					if (vp.contains(gp1.match1endpoint)) {
						if (vp.isKnot) {
							topKnot = (Knot) vp;
							topKnotUpdate = true;
						}
					}
				}

				finalKnot.knotPointsFlattened.addAll(gp1.knotPointsFlattened);

				for (Segment s : gp1.sortedSegments) {
					if (!(finalKnot.sortedSegments.contains(s) && finalKnot.knotPointsFlattened.contains(s.first)
							&& finalKnot.knotPointsFlattened.contains(s.last))) {
						finalKnot.sortedSegments.add(s);
					}
				}
				ArrayList<Segment> toRemoveFinal = new ArrayList<>();
				for (Segment s : finalKnot.sortedSegments) {
					if (finalKnot.knotPointsFlattened.contains(s.first)
							&& finalKnot.knotPointsFlattened.contains(s.last)) {
						toRemoveFinal.add(s);
					}
				}
				finalKnot.sortedSegments.sort(null);
				if (topKnotUpdate) {
					topKnot.knotPointsFlattened.addAll(gp1.knotPointsFlattened);

					for (Segment s : gp1.sortedSegments) {
						if (!(topKnot.sortedSegments.contains(s) && topKnot.knotPointsFlattened.contains(s.first)
								&& topKnot.knotPointsFlattened.contains(s.last))) {
							topKnot.sortedSegments.add(s);
						}
					}
					ArrayList<Segment> toRemove = new ArrayList<>();
					for (Segment s : topKnot.sortedSegments) {
						if (topKnot.knotPointsFlattened.contains(s.first)
								&& topKnot.knotPointsFlattened.contains(s.last)) {
							toRemove.add(s);
						}
					}

					topKnot.sortedSegments.removeAll(toRemove);
					topKnot.sortedSegments.sort(null);

					VirtualPoint mappedKnot = topKnot.pointToInternalKnot.get(gp1.match1endpoint.id);
					if (!mappedKnot.isKnot) {
						mappedKnot = gp1;
					}
					int idx = 0;
					while (mappedKnot != null && mappedKnot.isKnot) {
						for (VirtualPoint vp : gp1.knotPointsFlattened) {
							topKnot.pointToInternalKnot.put(vp.id, mappedKnot);
						}

						for (Segment s : gp1.sortedSegments) {
							if (!(topKnot.sortedSegments.contains(s) && topKnot.knotPointsFlattened.contains(s.first)
									&& topKnot.knotPointsFlattened.contains(s.last))) {
								topKnot.sortedSegments.add(s);
							}
						}
						ArrayList<Segment> toRemoveTemp = new ArrayList<>();
						for (Segment s : topKnot.sortedSegments) {
							if (topKnot.knotPointsFlattened.contains(s.first)
									&& topKnot.knotPointsFlattened.contains(s.last)) {
								toRemoveTemp.add(s);
							}
						}

						topKnot.knotPointsFlattened.addAll(gp1.knotPointsFlattened);
						topKnot.sortedSegments.removeAll(toRemove);
						topKnot.sortedSegments.sort(null);
						topKnot = (Knot) mappedKnot;
						mappedKnot = topKnot.pointToInternalKnot.get(gp1.match1endpoint.id);
						if (idx > 10) {
							break;
						}
						idx++;
					}
					if (!topKnot.equals(gp1)) {
						for (VirtualPoint vp : gp1.knotPointsFlattened) {
							topKnot.pointToInternalKnot.put(vp.id, gp1);
						}

						for (Segment s : gp1.sortedSegments) {
							if (!(topKnot.sortedSegments.contains(s) && topKnot.knotPointsFlattened.contains(s.first)
									&& topKnot.knotPointsFlattened.contains(s.last))) {
								topKnot.sortedSegments.add(s);
							}
						}
						ArrayList<Segment> toRemoveTemp = new ArrayList<>();
						for (Segment s : topKnot.sortedSegments) {
							if (topKnot.knotPointsFlattened.contains(s.first)
									&& topKnot.knotPointsFlattened.contains(s.last)) {
								toRemoveTemp.add(s);
							}
						}

						topKnot.knotPointsFlattened.addAll(gp1.knotPointsFlattened);
						topKnot.sortedSegments.removeAll(toRemove);
						topKnot.sortedSegments.sort(null);
					}

				}
				flattenRunPoints = finalKnot.knotPoints;
				if (true) {
					knotmergecount++;
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

		public Segment getPointer(int idx) {
			int count = idx;
			ArrayList<Segment> seenGroups = new ArrayList<Segment>();
			ArrayList<VirtualPoint> seenPoints = new ArrayList<VirtualPoint>();
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
					if (count == 0) {
						return s;
					}
					seenGroups.add(potentialSegment);
				}
			}
			return null;
		}

		public Segment getClosestSegment(VirtualPoint vp) {
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
	//TODO: need to actually measure best orientation?
	public ArrayList<VirtualPoint> flattenRunPoints(ArrayList<VirtualPoint> knotPoints, boolean knot) {
		ArrayList<VirtualPoint> flattenRunPoints = new ArrayList<>();
		boolean twoKnot = knotPoints.size() == 2 && knot;
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

				Segment s = vp.getClosestSegment(vp2);
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
			this.id = numPoints;
			pointMap.put(id, this);
			unvisited.add(this);
			numKnots++;
		}

		public Segment getPointer(int idx) {
			int count = idx;
			ArrayList<Segment> seenGroups = new ArrayList<Segment>();
			ArrayList<VirtualPoint> seenPoints = new ArrayList<VirtualPoint>();
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
						&& (!seenGroups.contains(potentialSegment) && (!seenPoints.contains(knotPoint))
								&& (!seenPoints.contains(basePoint)))
						|| potentialSegment.equals(s1) || potentialSegment.equals(s2)) {
					count--;
					if (count == 0) {
						return s;
					}
					seenGroups.add(potentialSegment);
				}
			}
			return null;
		}

		public Segment getClosestSegment(VirtualPoint vp) {
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
			if (mainPoint.isRun) {

				Run run2 = (Run) mainPoint;

				System.out.println("run found: " + run2.fullString());
				System.out.println(vp2);
				if (run2.endpoint1.equals(run2.basePoint1) || run2.endpoint2.equals(run2.basePoint1)) {
					System.out.println(run2.fullString());
					System.out.println(pointer1);
					System.out.println(pointer11);
					System.out.println(pointer12);
					System.out.println(pointer2);
					System.out.println(pointer21);
					System.out.println(pointer22);
					// float zero = 1/0;
				}
			}
			if (vp2.isRun) {
				Run run2 = (Run) vp2;

				System.out.println("run found: " + run2.fullString());
				if (run2.endpoint1.equals(run2.basePoint1) || run2.endpoint2.equals(run2.basePoint1)) {
					System.out.println(run2.fullString());
					// float zero = 1/0;
				}
			}
			if (vp1.isRun) {
				Run run1 = (Run) vp1;
				if (run1.endpoint1.equals(run1.basePoint1) || run1.endpoint2.equals(run1.basePoint1)) {
					System.out.println(run1.fullString());
				}
			}
			if (pointer2.topGroup.contains(pointMap.get(5)) && pointer2.topGroup.contains(pointMap.get(2))) {
				breakCount++;
				if (breakCount == 7) {
					// float zero = 1 / 0;
				}
			}
			// need to check that we haven't already match the end of the run in run case
			if (mainPoint.equals(vp11) && !inKnots1 && vp1.numMatches < 2
					&& (mainPoint.numMatches == 0 || !mainPoint.s1.contains(pointer1))) {
				matchPoint = vp1;
				matchEndPoint = pointer1;
				matchBasePoint = pointer11;
				matchSegment = potentialSegment11;
			} else if (mainPoint.equals(vp12) && !inKnots1 && vp1.numMatches < 2
					&& (mainPoint.numMatches == 0 || !mainPoint.s1.contains(pointer1))) {
				matchPoint = vp1;
				matchEndPoint = pointer1;
				matchBasePoint = pointer12;
				matchSegment = potentialSegment12;
			} else if ((mainPoint.equals(vp21)) && !inKnots2 && vp2.numMatches < 2
					&& (mainPoint.numMatches == 0 || !mainPoint.s1.contains(pointer2))) {
				matchPoint = vp2;
				matchEndPoint = pointer2;
				matchBasePoint = pointer21;
				matchSegment = potentialSegment21;
			} else if (mainPoint.equals(vp22) && !inKnots2 && vp2.numMatches < 2
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
						runFailedMatch2 = pointer2.topGroup;
					} else {
						runFailedMatch2 = pointer1.topGroup;
					}
					endPoint2 = mainPoint;
					System.out.println("Found both end of the run, adding to knot");
					// I think if we are pointing on either end to ourselves we need to make a knot,
					// not a run
					boolean skipflag = true;
					if (skipflag && (runList.contains(runFailedMatch1) || runList.contains(runFailedMatch2))) {

						Segment thirdMatch1 = runFailedMatch1.getPointer(3);
						VirtualPoint me1 = thirdMatch1.getOtherKnot(runFailedMatch1);
						VirtualPoint bp1 = thirdMatch1.getOther(me1);
						boolean flag1 = runList.contains(runFailedMatch1) && endPoint1.contains(me1);

						Segment thirdMatch2 = runFailedMatch2.getPointer(3);
						VirtualPoint me2 = thirdMatch2.getOtherKnot(runFailedMatch2);
						VirtualPoint bp2 = thirdMatch2.getOther(me2);
						boolean flag2 = runList.contains(runFailedMatch2) && endPoint2.contains(me2);
						if ((flag1 || flag2) && skipflag) {
							System.out.println("Half Knot Found: " + "flag1:" + flag1 + "flag2:" + flag2);
							System.out.println(runList);
							Knot k = null;
							if (flag1 && !flag2) {
								System.out.println("ep: " + endPoint1);
								System.out.println("failed match" + runFailedMatch1);
								System.out.println(thirdMatch1);
								System.out.println(bp1);
								System.out.println(me1);
								System.out.println("Should be Knot!");
								int knotIdx = runList.indexOf(runFailedMatch1);
								ArrayList<VirtualPoint> subList = new ArrayList<VirtualPoint>(
										runList.subList(knotIdx, runList.size()));

								VirtualPoint tempMatch;
								Point tempBP;
								Point tempME;
								Segment tempS;
								if (subList.contains(runFailedMatch1.match1)) {
									tempMatch = runFailedMatch1.match2;
									tempME = runFailedMatch1.match2endpoint;
									tempBP = runFailedMatch1.basePoint2;
									tempS = runFailedMatch1.s2;
									runFailedMatch1.match2 = null;
									runFailedMatch1.basePoint2 = null;
									runFailedMatch1.match2endpoint = null;
									runFailedMatch1.s2 = null;
								} else {
									tempMatch = runFailedMatch1.match1;
									tempME = runFailedMatch1.match1endpoint;
									tempBP = runFailedMatch1.basePoint1;
									tempS = runFailedMatch1.s1;
									runFailedMatch1.match1 = runFailedMatch1.match2;
									runFailedMatch1.basePoint1 = runFailedMatch1.basePoint2;
									runFailedMatch1.match1endpoint = runFailedMatch1.match2endpoint;
									runFailedMatch1.s1 = runFailedMatch1.s2;
									runFailedMatch1.match2 = null;
									runFailedMatch1.basePoint2 = null;
									runFailedMatch1.match2endpoint = null;
									runFailedMatch1.s2 = null;
								}
								k = new Knot(subList);
								k.match1 = tempMatch;
								k.match1endpoint = tempME;
								k.basePoint1 = tempBP;
								k.s1 = tempS;
								if (tempMatch != null) {
									if (k.contains(tempMatch.match1)) {
										tempMatch.match1 = k;
									} else {
										tempMatch.match2 = k;

									}
								}

								runList.removeAll(subList);
								runList.add(k);
								System.out.println(subList);
								System.out.println(runList);

							} else if (flag2 && !flag1) {
								System.out.println(endPoint2);
								System.out.println(runFailedMatch2.fullString());
								System.out.println(thirdMatch2);
								System.out.println(bp2);
								System.out.println(me2);
								System.out.println("Should be Knot!");
								int knotIdx = runList.indexOf(runFailedMatch2);
								ArrayList<VirtualPoint> subList = new ArrayList<VirtualPoint>(
										runList.subList(0, knotIdx + 1));
								VirtualPoint tempMatch;
								Point tempBP;
								Point tempME;
								Segment tempS;
								if (subList.contains(runFailedMatch2.match1)) {
									tempMatch = runFailedMatch2.match2;
									tempME = runFailedMatch2.match2endpoint;
									tempBP = runFailedMatch2.basePoint2;
									tempS = runFailedMatch2.s2;
									runFailedMatch2.match2 = null;
									runFailedMatch2.basePoint2 = null;
									runFailedMatch2.match2endpoint = null;
									runFailedMatch2.s2 = null;
								} else {
									System.out.println(runFailedMatch2.fullString());
									System.out.println("reee");
									tempMatch = runFailedMatch2.match1;
									tempME = runFailedMatch2.match1endpoint;
									tempBP = runFailedMatch2.basePoint1;
									tempS = runFailedMatch2.s1;
									runFailedMatch2.match1 = runFailedMatch2.match2;
									runFailedMatch2.basePoint1 = runFailedMatch2.basePoint2;
									runFailedMatch2.match1endpoint = runFailedMatch2.match2endpoint;
									runFailedMatch2.s1 = runFailedMatch2.s2;
									runFailedMatch2.match2 = null;
									runFailedMatch2.basePoint2 = null;
									runFailedMatch2.match2endpoint = null;
									runFailedMatch2.s2 = null;
								}

								k = new Knot(subList);
								k.match1 = tempMatch;
								k.match1endpoint = tempME;
								k.basePoint1 = tempBP;
								k.s1 = tempS;
								if (tempMatch != null) {
									if (k.contains(tempMatch.match1)) {
										tempMatch.match1 = k;
									} else {
										tempMatch.match2 = k;

									}
								}

								runList.removeAll(subList);
								runList.add(0, k);
								System.out.println(subList);
								System.out.println(runList);
							} else {
								System.out.println("Knot Found!");

								k = new Knot(runList);
								knots.add(k);
								runList = new ArrayList<>();
								if (toVisit.size() == 0) {
									return knots;
								}
								mainPoint = toVisit.get(0);
								endpointReached = false;
								continue;
							}
							halfKnotCount++;
							System.out.println(k.fullString());

							if (halfKnotCount > 5) {
								System.out.println(runList);
								System.out.println(k.match1.fullString());
								System.out.println(endPoint2.fullString());
								System.out.println(runFailedMatch2.fullString());
								float zero = 1 / 0;
							}
						}
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
						runFailedMatch1 = pointer2.topGroup;
					} else {
						runFailedMatch1 = pointer1.topGroup;
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
		ArrayList<VirtualPoint> knotList = mainKnot.knotPoints;

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

					// this checking needs to be recursive down to the base when dealing with nested
					// knots
					Segment s1 = knotPoint1.getClosestSegment(external1);
					Segment s11 = cutPoint1.getClosestSegment(external2);
					Segment s12 = cutPoint2.getClosestSegment(external2);
					if (cutPoint1.equals(cutPoint2)) {
						s12 = cutPoint2.basePoint2.getClosestSegment(external2);
					}

					Segment s2 = knotPoint1.getClosestSegment(external2);
					Segment s21 = cutPoint1.getClosestSegment(external1);
					Segment s22 = cutPoint2.getClosestSegment(external1);
					if (cutPoint1.equals(cutPoint2)) {
						s22 = cutPoint2.basePoint2.getClosestSegment(external1);
					}
					// need to change this, unsure how
					// instead of being the closest segment needs to be the farthest segment that
					// knotPoint 1 matches
					Point vp1 = (Point) s1.getKnotPoint(knot.knotPointsFlattened);
					Point vp11 = (Point) s11.getKnotPoint(knot.knotPointsFlattened);

					Segment cutSegment1 = new Segment(vp1, vp11, distanceMatrix.getDistance(vp1.p, vp11.p));

					Segment cutSegment2 = cutPoint2.s2;
					if (cutPoint1.equals(cutPoint2)) {
						cutSegment2 = cutPoint2.basePoint2.getClosestSegment(knotPoint1);
					}
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
					boolean usingcp2 = false;
					// this seems wrong
					if (d11 < d12 && d11 < d21 && d11 < d22) {
						System.out.println("s1 + s11 (" + s1 + ", " + s11 +
								") is the smallest");
						knotPoint1.match2 = external1;
						VirtualPoint p11 = external1;
						if (external1.isKnot) {
							p11 = s1.getKnotPoint(((Knot) external1).knotPointsFlattened);
						}
						knotPoint1.basePoint2 = (Point) s1.getOther(p11);
						if (external1.match2.equals(knot)) {
							external1.match2 = knotPoint1;
							knotPoint1.match2endpoint = external1.basePoint2;
							external1.match2endpoint = knotPoint1.basePoint2;
							external1.basePoint2 = (Point) s1.getOther(knotPoint1.basePoint1);
							knotPoint1.s2 = s1;
							external1.s2 = s1;
						} else {
							external1.match1 = knotPoint1;
							knotPoint1.match2endpoint = external1.basePoint1;
							external1.match1endpoint = knotPoint1.basePoint2;
							external1.basePoint1 = (Point) s1.getOther(knotPoint1.basePoint1);
							knotPoint1.s2 = s1;
							external1.s1 = s1;
						}
						cutPoint1.match2 = external2;
						VirtualPoint p12 = external2;
						if (external2.isKnot) {
							p12 = s11.getKnotPoint(((Knot) external2).knotPointsFlattened);
						}
						cutPoint1.basePoint2 = (Point) s11.getOther(p12);
						if (external2.match2.equals(knot)) {
							external2.match2 = cutPoint1;
							cutPoint1.match2endpoint = external2.basePoint2;
							external2.match2endpoint = cutPoint1.basePoint2;
							external2.basePoint2 = (Point) s11.getOther(cutPoint1.basePoint2);
							cutPoint1.s2 = s11;
							external2.s2 = s11;
						} else {
							external2.match1 = cutPoint1;
							cutPoint1.match2endpoint = external2.basePoint1;
							external2.match1endpoint = cutPoint1.basePoint2;
							external2.basePoint1 = (Point) s11.getOther(cutPoint1.basePoint2);
							cutPoint1.s2 = s11;
							external2.s1 = s11;
						}
					} else if (d12 < d21 && d12 < d22) {
						System.out.println("s1 + s12 (" + s1 + ", " + s12 +
								") is the smallest");
						knotPoint1.match1 = external1;
						VirtualPoint p21 = external1;
						if (external1.isKnot) {
							p21 = s1.getKnotPoint(((Knot) external1).knotPointsFlattened);
						}
						knotPoint1.basePoint1 = (Point) s1.getOther(p21);
						if (external1.match2.equals(knot)) {
							external1.match2 = knotPoint1;
							knotPoint1.match1endpoint = external1.basePoint2;
							external1.match2endpoint = knotPoint1.basePoint1;
							knotPoint1.s1 = s1;
							external1.s2 = s1;
						} else {
							external1.match1 = knotPoint1;
							knotPoint1.match1endpoint = external1.basePoint1;
							external1.match1endpoint = knotPoint1.basePoint1;
							knotPoint1.s1 = s1;
							external1.s1 = s1;
						}
						cutPoint2.match2 = external2;
						VirtualPoint p22 = external2;
						if (external2.isKnot) {
							p22 = s12.getKnotPoint(((Knot) external2).knotPointsFlattened);
						}
						cutPoint2.basePoint2 = (Point) s12.getOther(p22);
						if (external2.match2.equals(knot)) {
							external2.match2 = cutPoint2;
							cutPoint2.match2endpoint = external2.basePoint2;
							external2.match2endpoint = cutPoint2.basePoint2;
							cutPoint2.s2 = s12;
							external2.s2 = s12;
						} else {
							external2.match1 = cutPoint2;
							cutPoint2.match2endpoint = external2.basePoint1;
							external2.match1endpoint = cutPoint2.basePoint2;
							cutPoint2.s2 = s12;
							external2.s1 = s12;
						}
					} else if (d21 < d22) {
						System.out.println("s2 + s21 (" + s2 + ", " + s21 +
								") is the smallest");
						knotPoint1.match2 = external2;
						VirtualPoint p21 = external2;
						if (external2.isKnot) {
							p21 = s2.getKnotPoint(((Knot) external2).knotPointsFlattened);
						}
						knotPoint1.basePoint2 = (Point) s2.getOther(p21);
						if (external2.match2.equals(knot)) {
							external2.match2 = knotPoint1;
							knotPoint1.match2endpoint = external2.basePoint2;
							external2.match2endpoint = knotPoint1.basePoint2;
							knotPoint1.s2 = s2;
							external2.s2 = s2;
						} else {
							external2.match1 = knotPoint1;
							knotPoint1.match2endpoint = external2.basePoint1;
							external2.match1endpoint = knotPoint1.basePoint2;
							knotPoint1.s2 = s2;
							external2.s1 = s2;
						}
						cutPoint1.match2 = external1;
						VirtualPoint p22 = external1;
						if (external1.isKnot) {
							p22 = s21.getKnotPoint(((Knot) external1).knotPointsFlattened);
						}
						cutPoint1.basePoint2 = (Point) s21.getOther(p22);
						if (external1.match2.equals(knot)) {
							external1.match2 = cutPoint1;
							cutPoint1.match2endpoint = external1.basePoint2;
							external1.match2endpoint = cutPoint1.basePoint2;
							cutPoint1.s2 = s21;
							external1.s2 = s21;
						} else {
							external1.match1 = cutPoint1;
							cutPoint1.match2endpoint = external1.basePoint1;
							external1.match1endpoint = cutPoint1.basePoint2;
							cutPoint1.s2 = s21;
							external1.s1 = s21;
						}
					} else {
						System.out.println("s2 + s22 (" + s2 + ", " + s22 +
								") is the smallest");
						knotPoint1.match1 = external2;
						VirtualPoint p21 = external2;
						if (external2.isKnot) {
							p21 = s2.getKnotPoint(((Knot) external2).knotPointsFlattened);
						}
						knotPoint1.basePoint1 = (Point) s2.getOther(p21);
						if (external2.match2.equals(knot)) {
							external2.match2 = knotPoint1;
							knotPoint1.match1endpoint = external2.basePoint2;
							external2.match2endpoint = knotPoint1.basePoint1;
							knotPoint1.s1 = s2;
							external2.s2 = s2;
						} else {
							external2.match1 = knotPoint1;
							knotPoint1.match1endpoint = external2.basePoint1;
							external2.match1endpoint = knotPoint1.basePoint1;
							knotPoint1.s1 = s2;
							external2.s1 = s2;
						}
						cutPoint2.match2 = external1;
						VirtualPoint p22 = external1;
						if (external1.isKnot) {
							p22 = s22.getKnotPoint(((Knot) external1).knotPointsFlattened);
						}
						cutPoint2.basePoint2 = (Point) s22.getOther(p22);
						if (external1.match2.equals(knot)) {
							external1.match2 = cutPoint2;
							cutPoint2.match2endpoint = external1.basePoint2;
							external1.match2endpoint = cutPoint2.basePoint2;
							cutPoint2.s2 = s22;
							external1.s2 = s22;
						} else {
							external1.match1 = cutPoint2;
							cutPoint2.match2endpoint = external1.basePoint1;
							external1.match1endpoint = cutPoint2.basePoint2;
							cutPoint2.s2 = s22;
							external1.s1 = s22;
						}
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
					Point nearestbp1 = knotPoint1.getNearestBasePoint(external1);
					Point nearestbp2 = knotPoint2.getNearestBasePoint(external2);
					Segment cutSegment1 = new Segment(knotPoint1, cutPoint1, 0);
					Segment cutSegment2 = new Segment(knotPoint2, cutPoint2, 0);
					if (cutSegment1.equals(cutSegment2)) {
						System.out.println("----Both externals agree on cut segment " + cutSegment1
								+ ", proceed -------------------");
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
					} else {
						System.out.println("----Both externals disagree on cut segment " + cutSegment1 + "   "
								+ cutSegment2 + ", need to assess -------------------");
						if (cutPoint1.equals(cutPoint2)) {
							System.out.println("----Both cutpoints:  " + cutPoint1 + "   " + cutPoint2
									+ " are the same, need to assess which is better -------------------");
							Segment s11 = knotPoint1.getClosestSegment(external1);
							Segment s12 = cutPoint1.getClosestSegment(external2);
							Segment s1i1 = cutPoint1.getClosestSegment(knotPoint1);

							Segment s2 = knotPoint2.getClosestSegment(external2);
							Segment s21 = cutPoint1.getClosestSegment(external1);
							System.out.println(s11 + "" + s12 + "");
							System.out.println(s11.distance + s12.distance);
							double d11 = s11.distance + s12.distance;
							System.out.println(s2 + "" + s21 + "");
							System.out.println(s2.distance + s21.distance);
							double d21 = s2.distance + s21.distance;
							if (d11 < d21) {
								System.out.println("s1 + s11 (" + s11 + ", " + s12 +
										") is the smallest");
								knotPoint1.match2 = external1;
								VirtualPoint p11 = external1;
								if (external1.isKnot) {
									p11 = s11.getKnotPoint(((Knot) external1).knotPointsFlattened);
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
								if (external2.isKnot) {
									p12 = s12.getKnotPoint(((Knot) external2).knotPointsFlattened);
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
								System.out.println("s2 + s21 (" + s2 + ", " + s21 +
										") is the smallest");
								knotPoint2.match2 = external2;
								VirtualPoint p21 = external2;
								if (external2.isKnot) {
									p21 = s2.getKnotPoint(((Knot) external2).knotPointsFlattened);
								}
								knotPoint2.basePoint2 = (Point) s2.getOther(p21);
								if (external2.match2.equals(knot)) {
									external2.match2 = knotPoint2;
									knotPoint1.match2endpoint = external2.basePoint2;
									external2.match2endpoint = knotPoint1.basePoint2;
								} else {
									external2.match1 = knotPoint1;
									knotPoint2.match2endpoint = external2.basePoint1;
									external2.match1endpoint = knotPoint2.basePoint2;
								}
								cutPoint1.match2 = external1;
								VirtualPoint p22 = external1;
								if (external1.isKnot) {
									p22 = s21.getKnotPoint(((Knot) external1).knotPointsFlattened);
								}
								cutPoint1.basePoint2 = (Point) s21.getOther(p22);
								if (external1.match2.equals(knot)) {
									external1.match2 = cutPoint1;
									cutPoint1.match2endpoint = external1.basePoint2;
									external1.match2endpoint = cutPoint1.basePoint2;
								} else {
									external1.match1 = cutPoint1;
									cutPoint1.match2endpoint = external1.basePoint1;
									external1.match1endpoint = cutPoint1.basePoint2;
								}
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

							Segment s1 = knotPoint1.getClosestSegment(external1);
							Segment s11 = cutPoint1.getClosestSegment(external2);
							Segment s12 = cutPoint2.getClosestSegment(external2);

							Segment s2 = knotPoint2.getClosestSegment(external2);
							Segment s21 = cutPoint1.getClosestSegment(external1);
							Segment s22 = cutPoint2.getClosestSegment(external1);
							Segment s4 = cutPoint2.getClosestSegment(knotPoint2);
							Segment s5 = cutPoint1.getClosestSegment(knotPoint1);

							Segment s3 = cutPoint2.getClosestSegment(cutPoint1);
							System.out.println(s1 + "" + s11 + "" + "cut: " + cutSegment1);
							System.out.println(s1.distance + s11.distance + s4.distance);
							double d11 = s1.distance + s11.distance + s4.distance;
							System.out.println(s2 + "" + s22 + "" + "cut: " + cutSegment2);
							System.out.println(s2.distance + s22.distance + s5.distance);
							double d22 = s2.distance + s22.distance + s5.distance;
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

									Segment temp1 = cutPoint1.getClosestSegment(prevP);
									Segment temp2 = cutPoint2.getClosestSegment(nextP);
									Segment cutTemp = nextP.getClosestSegment(prevP);
									double delta = temp1.distance + temp2.distance - cutTemp.distance;

									Segment temp3 = cutPoint1.getClosestSegment(nextP);
									Segment temp4 = cutPoint2.getClosestSegment(prevP);
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
							if (d3 < d22 && d3 < d11) {
								System.out.println("Cutting and attaching cutpoints : " + d3);
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
								if (!orphan) {
									Point nearestcp1bp = cutPoint1.getNearestBasePoint(cutPoint2);
									Point nearestcp2bp = cutPoint2.getNearestBasePoint(cutPoint1);
									if (cutPoint1.match1.equals(knotPoint1)) {
										cutPoint1.match1 = cutPoint2;
										cutPoint1.match1endpoint = nearestcp2bp;
										cutPoint1.basePoint1 = nearestcp1bp;
										cutPoint1.s1 = s3;
									} else {
										cutPoint1.match2 = cutPoint2;
										cutPoint1.match2endpoint = nearestcp2bp;
										cutPoint1.basePoint2 = nearestcp1bp;
										cutPoint1.s2 = s3;
									}

									if (cutPoint2.match1.equals(knotPoint2)) {
										cutPoint2.match1 = cutPoint1;
										cutPoint2.match1endpoint = nearestcp1bp;
										cutPoint2.basePoint1 = nearestcp2bp;
										cutPoint2.s1 = s3;
									} else {
										cutPoint2.match2 = cutPoint1;
										cutPoint2.match2endpoint = nearestcp1bp;
										cutPoint2.basePoint2 = nearestcp2bp;
										cutPoint2.s2 = s3;
									}
								} else {

									Point nearestcp1bp = cutPoint1.getNearestBasePoint(vpOrph1);
									Point nearestcp2bp = vpOrph1.getNearestBasePoint(cutPoint1);
									if (vpOrph1.match1.equals(vpOrph2)) {
										vpOrph1.match1 = cutPoint1;
										vpOrph1.match1endpoint = nearestcp1bp;
										vpOrph1.basePoint1 = nearestcp2bp;
										vpOrph1.s1 = sOrph1;
									} else {
										vpOrph1.match2 = cutPoint1;
										vpOrph1.match2endpoint = nearestcp1bp;
										vpOrph1.basePoint2 = nearestcp2bp;
										vpOrph1.s2 = sOrph1;
									}
									if (cutPoint1.match1.equals(knotPoint1)) {
										cutPoint1.match1 = vpOrph1;
										cutPoint1.match1endpoint = nearestcp2bp;
										cutPoint1.basePoint1 = nearestcp1bp;
										cutPoint1.s1 = sOrph1;
									} else {
										cutPoint1.match2 = vpOrph1;
										cutPoint1.match2endpoint = nearestcp2bp;
										cutPoint1.basePoint2 = nearestcp1bp;
										cutPoint1.s2 = sOrph1;
									}

									Point nearestcp1bp2 = cutPoint1.getNearestBasePoint(vpOrph2);
									Point nearestcp2bp2 = vpOrph2.getNearestBasePoint(cutPoint1);	
									if (vpOrph2.match1.equals(vpOrph1)) {
										vpOrph2.match1 = cutPoint2;
										vpOrph2.match1endpoint = nearestcp1bp2;
										vpOrph2.basePoint1 = nearestcp2bp2;
										vpOrph2.s1 = sOrph2;
									} else {
										vpOrph2.match2 = cutPoint2;
										vpOrph2.match2endpoint = nearestcp1bp2;
										vpOrph2.basePoint2 = nearestcp2bp2;
										vpOrph2.s2 = sOrph2;
									}
									if (cutPoint2.match1.equals(knotPoint2)) {
										cutPoint2.match1 = vpOrph2;
										cutPoint2.match1endpoint = nearestcp2bp2;
										cutPoint2.basePoint1 = nearestcp1bp2;
										cutPoint2.s1 = sOrph2;
									} else {
										cutPoint2.match2 = vpOrph2;
										cutPoint2.match2endpoint = nearestcp2bp2;
										cutPoint2.basePoint2 = nearestcp1bp2;
										cutPoint2.s2 = sOrph2;
									}
									System.out.println("Linking orphan back in: ");
								}

							} else if (d11 < d22) {
								System.out.println("s1 + s11 (" + s1 + ", " + s11 +
										") is the smallest");
								knotPoint1.match2 = external1;
								VirtualPoint p11 = external1;
								if (external1.isKnot) {
									p11 = s1.getKnotPoint(((Knot) external1).knotPointsFlattened);
								}
								knotPoint1.basePoint2 = (Point) s1.getOther(p11);
								if (external1.match2.equals(knot)) {
									external1.match2 = knotPoint1;
									knotPoint1.match2endpoint = external1.basePoint2;
									external1.match2endpoint = knotPoint1.basePoint2;
									external1.basePoint2 = (Point) s1.getOther(knotPoint1.basePoint2);
								} else {
									external1.match1 = knotPoint1;
									knotPoint1.match2endpoint = external1.basePoint1;
									external1.match1endpoint = knotPoint1.basePoint2;
									external1.basePoint1 = (Point) s1.getOther(knotPoint1.basePoint2);
								}
								cutPoint1.match2 = external2;
								VirtualPoint p12 = external2;
								if (external2.isKnot) {
									p12 = s11.getKnotPoint(((Knot) external2).knotPointsFlattened);
								}
								cutPoint1.basePoint2 = (Point) s11.getOther(p12);
								if (external2.match2.equals(knot)) {
									external2.match2 = cutPoint1;
									cutPoint1.match2endpoint = external2.basePoint2;
									external2.match2endpoint = cutPoint1.basePoint2;
									external2.basePoint2 = (Point) s11.getOther(cutPoint1.basePoint2);
								} else {
									external2.match1 = cutPoint1;
									cutPoint1.match2endpoint = external2.basePoint1;
									external2.match1endpoint = cutPoint1.basePoint2;
									external2.basePoint1 = (Point) s11.getOther(cutPoint1.basePoint2);
								}
							} else {
								System.out.println("s2 + s22 (" + s2 + ", " + s22 +
										") is the smallest");
								knotPoint2.match2 = external2;
								VirtualPoint p21 = external2;
								if (external2.isKnot) {
									p21 = s2.getKnotPoint(((Knot) external2).knotPointsFlattened);
								}
								knotPoint2.basePoint1 = (Point) s2.getOther(p21);
								if (external2.match2.equals(knot)) {
									external2.match2 = knotPoint2;
									knotPoint2.match2endpoint = external2.basePoint2;
									external2.match2endpoint = knotPoint2.basePoint1;
									external2.basePoint2 = (Point) s2.getOther(knotPoint1.basePoint1);
								} else {
									external2.match1 = knotPoint2;
									knotPoint2.match2endpoint = external2.basePoint1;
									external2.match1endpoint = knotPoint2.basePoint1;
									external2.basePoint1 = (Point) s2.getOther(knotPoint1.basePoint1);
								}
								cutPoint2.match2 = external1;
								VirtualPoint p22 = external1;
								if (external1.isKnot) {
									p22 = s22.getKnotPoint(((Knot) external1).knotPointsFlattened);
								}
								cutPoint2.basePoint2 = (Point) s22.getOther(p22);
								if (external1.match2.equals(knot)) {
									external1.match2 = cutPoint2;
									cutPoint2.match2endpoint = external1.basePoint2;
									external1.match2endpoint = cutPoint2.basePoint2;
									external1.basePoint2 = (Point) s22.getOther(cutPoint2.basePoint2);
								} else {
									external1.match1 = cutPoint2;
									cutPoint2.match2endpoint = external1.basePoint1;
									external1.match1endpoint = cutPoint2.basePoint2;
									external1.basePoint1 = (Point) s22.getOther(cutPoint2.basePoint2);
								}
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

		Shell result = new Shell();
		for (VirtualPoint p : knotList) {
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
