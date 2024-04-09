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
			sortedSegments = new ArrayList<Segment>();
		}

		public Segment getPointer(int idx) {
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
				/*
				 * System.out.println("CheckPointer: vp: " + vp +
				 * " seengroup.contains(potentialSegment): "
				 * + seenGroups.contains(potentialSegment) + " basepoint: " + basePoint +
				 * " topGroupnumMatches: "
				 * + vp.topGroup.numMatches + " topGroup: " + vp.topGroup);
				 */
				//
				if ((!vp.isRun || ((Run) vp).endpoint1.contains(basePoint) || ((Run) vp).endpoint2.contains(basePoint))
						&& (!seenGroups.contains(potentialSegment))
						|| potentialSegment.equals(s1) || potentialSegment.equals(s2)) {
					// System.out.println("match!");
					count--;
					if (count == 0) {
						return potentialSegment;
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
	int runmergecount = 0;

	class Knot extends VirtualPoint {
		public int size;
		public ArrayList<VirtualPoint> knotPoints; // [ vp1, vp2, ... vpm];
		public HashMap<Integer, VirtualPoint> pointToInternalKnot;
		// [[s1, ..., sn-1], [s1, ..., sn-1], ... m]; sorted and remove
		// vp1, vp2, ... vpm

		public Knot(ArrayList<VirtualPoint> knotPointsToAdd) {
			// if (knotPointsToAdd.get(0).match2 == null) {
			// VirtualPoint vp1 = knotPointsToAdd.get(0);
			// VirtualPoint vp2 = knotPointsToAdd.get(knotPointsToAdd.size() - 1);
			// Segment s = vp1.getClosestSegment(vp2);
			// Point bp2 = (Point) s.getOtherKnot(vp1);
			// Point bp1 = (Point) s.getOther(bp2);
			// vp1.match2 = vp2;
			// vp1.basePoint2 = bp1;
			// vp1.match2endpoint = bp2;
			// vp1.s2 = s;
			// vp2.match2 = vp1;
			// vp2.basePoint2 = bp2;
			// vp2.match2endpoint = bp1;
			// vp2.s2 = s;
			// }
			sortedSegments = new ArrayList<>();
			ArrayList<VirtualPoint> flattenRunPoints = new ArrayList<>();
			if (knotPointsToAdd.size() == 2 && knotPointsToAdd.get(0).isRun && knotPointsToAdd.get(1).isRun) {
				System.out.println("looped run Points flattening");
				Run run1 = (Run) knotPointsToAdd.get(0);
				Run run2 = (Run) knotPointsToAdd.get(1);
				for (VirtualPoint p : run1.knotPoints) {
					flattenRunPoints.add(p);
				}
				System.out.println(run2.fullString());
				System.out.println(run1.fullString());
				System.out.println(flattenRunPoints.get(flattenRunPoints.size() - 1));
				if (run2.endpoint1.equals(run2.basePoint1)
						&& flattenRunPoints.get(flattenRunPoints.size() - 1).contains(run2.match1endpoint)) {

					for (VirtualPoint p : run2.knotPoints) {
						flattenRunPoints.add(p);
					}
				} else {
					int end = flattenRunPoints.size();
					for (VirtualPoint p : run2.knotPoints) {
						flattenRunPoints.add(end, p);
					}
				}
				System.out.println(flattenRunPoints);

			} else {
				for (int i = 0; i < knotPointsToAdd.size(); i++) {
					VirtualPoint vp = knotPointsToAdd.get(i);
					System.out.println("flatten endpoints: " + flattenRunPoints);
					if (i + 1 >= knotPointsToAdd.size()) {
						VirtualPoint vp2 = knotPointsToAdd.get(0);
						if (vp.isRun) {
							Run run = ((Run) vp);
							if ((run.endpoint1.equals(run.basePoint1)
									&& ((vp2.isRun && ((Run) vp2).endpoint1.contains(run.match1endpoint))
											|| vp2.contains(run.match1endpoint)))
									|| (run.endpoint1.equals(run.basePoint2)
											&& ((vp2.isRun && ((Run) vp2).endpoint1.contains(run.match2endpoint))
													|| vp2.contains(run.match2endpoint)))) {

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
						break;
					}
					VirtualPoint vp2 = knotPointsToAdd.get(i + 1);
					if (vp.isRun) {
						Run run = ((Run) vp);
						System.out.println("vp1: " + vp.fullString());
						System.out.println("endpoint1: " + run.endpoint1);
						System.out.println("endpoint2: " + run.endpoint2);
						System.out.println("vp2: " + vp2.fullString());
						System.out.println(
								"run.endpoint1.equals(run.basePoint1): " + run.endpoint1.equals(run.basePoint1));
						System.out.println("vp2.contains(run.match1endpoint): " + vp2.contains(run.match1endpoint));
						if ((run.endpoint1.equals(run.basePoint1)
								&& ((vp2.isRun && ((Run) vp2).endpoint1.contains(run.match1endpoint))
										|| vp2.contains(run.match1endpoint)))
								|| (run.endpoint1.equals(run.basePoint2)
										&& ((vp2.isRun && ((Run) vp2).endpoint1.contains(run.match2endpoint))
												|| vp2.contains(run.match2endpoint)))) {

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
			}

			for (int i = 0; i < flattenRunPoints.size(); i++) {
				VirtualPoint vp = flattenRunPoints.get(i);
				VirtualPoint vp2 = null;

				if (i < flattenRunPoints.size() - 1) {
					vp2 = flattenRunPoints.get(i + 1);
				} else {
					vp2 = flattenRunPoints.get(0);
				}
				System.out.println(vp + " " + vp.group);
				System.out.println(vp2 + " " + vp2.group);
				if (!vp2.group.equals(vp.group) && (vp.group.isRun || vp2.group.isRun)) {
					System.out.println(vp + " " + vp.group);
					System.out.println(vp2 + " " + vp2.group);
					System.out.println(vp2.group.equals(vp.group));
					System.out.println("fixing match2 for: " + vp.fullString());

					System.out.println("new match 2: " + vp2.fullString());

					Segment s = vp.getClosestSegment(vp2);
					VirtualPoint bp1 = s.getKnotPoint(vp.externalVirtualPoints);
					VirtualPoint bp2 = s.getOther(bp1);
					System.out.println("bp1:" + bp1);
					System.out.println("bp2:" + bp2);
					System.out.println(vp.match1 == null || (vp.match1.contains(bp2) && vp.match1.isRun));
					System.out.println(vp.match2 == null || (vp.match2.contains(bp2) && vp.match2.isRun));
					if (vp.match1 == null || (vp.match1.contains(bp2) && vp.match1.isRun)) {
						vp.match1 = vp2;
						vp.basePoint1 = (Point) bp1;
						vp.match1endpoint = (Point) bp2;
						vp.s1 = s;
					} else if (vp.match2 == null || (vp.match2.contains(bp2) && vp.match2.isRun)) {
						vp.match2 = vp2;
						vp.basePoint2 = (Point) bp1;
						vp.match2endpoint = (Point) bp2;
						vp.s2 = s;

					}
					if (vp2.match1 == null || (vp2.match1.contains(bp1) && vp2.match1.isRun)) {
						vp2.match1 = vp;
						vp2.basePoint1 = (Point) bp2;
						vp2.match1endpoint = (Point) bp1;
						vp2.s1 = s;
					} else if (vp2.match2 == null || (vp2.match2.contains(bp1) && vp2.match2.isRun)) {
						vp2.match2 = vp;
						vp2.basePoint2 = (Point) bp2;
						vp2.match2endpoint = (Point) bp1;
						vp2.s2 = s;
					}
					if (vp.s1.distance > vp.s2.distance) {
						Point tempBp = vp.basePoint1;
						Point tempMe = vp.match1endpoint;
						VirtualPoint tempMatch = vp.match1;
						Segment tempSeg = vp.s1;
						vp.basePoint1 = vp.basePoint2;
						vp.match1 = vp.match2;
						vp.match1endpoint = vp.match2endpoint;
						vp.s1 = vp.s2;
						vp.basePoint2 = tempBp;
						vp.match2 = tempMatch;
						vp.match2endpoint = tempMe;
						vp.s2 = tempSeg;
					}
					if (vp2.s1.distance > vp2.s2.distance) {
						Point tempBp = vp2.basePoint1;
						Point tempMe = vp2.match1endpoint;
						VirtualPoint tempMatch = vp2.match1;
						Segment tempSeg = vp2.s1;
						vp2.basePoint1 = vp2.basePoint2;
						vp2.match1 = vp2.match2;
						vp2.match1endpoint = vp2.match2endpoint;
						vp2.s1 = vp2.s2;
						vp2.basePoint2 = tempBp;
						vp2.match2 = tempMatch;
						vp2.match2endpoint = tempMe;
						vp2.s2 = tempSeg;
					}
					vp.numMatches = 2;
					vp2.numMatches = 2;

					System.out.println("fixed match2: " + vp.fullString());

					System.out.println("new match 2 after: " + vp2.fullString() + "\n");
				}
			}

			System.out.println(flattenRunPoints);

			// if we have two knots that make up the whole knot we need to insert one of the
			// knots into the other
			if (flattenRunPoints.size() == 2 && flattenRunPoints.get(0).isKnot && flattenRunPoints.get(1).isKnot) {
				System.out.println("Knot, Knot found, need to insert one into the other");
				// need to loop through each segment in each knot and see which is most
				// beneficial
				// (current minus new)
				// to cut
				Knot gp1 = (Knot) flattenRunPoints.get(0);

				Knot gp2 = (Knot) flattenRunPoints.get(1);

				Point pointer1 = gp1.match1endpoint;
				VirtualPoint vKnotPoint1 = pointer1.topGroupVirtualPoint;
				// System.out.println(((Knot) gp1).externalKnotSegments);
				VirtualPoint cutPoint1 = vKnotPoint1.match2;
				VirtualPoint vCutPoint1 = cutPoint1;
				ArrayList<VirtualPoint> newList = new ArrayList<>();
				System.out.println("Both are knots, find the two cut segments and join across");
				Knot knot1 = (Knot) gp1;
				Knot knot2 = (Knot) gp2;
				System.out.println("Knot1: " + knot1);
				System.out.println("Knot2: " + knot2);
				System.out.println("knotPoint1: " + vKnotPoint1.fullString());
				System.out.println("cutPoint1: " + vCutPoint1.fullString());

				Segment sKP = gp1.getClosestSegment(vKnotPoint1);
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

				System.out.println(cutPoint1.fullString());
				System.out.println(pointer1.fullString());
				System.out.println(gp1.fullString());

				VirtualPoint addPoint = gp2.knotPoints.get(0);
				VirtualPoint prevPointTemp = gp2.knotPoints.get(gp2.knotPoints.size() - 1);
				for (int j = 0; j < gp2.knotPoints.size() + 1; j++) {
					System.out.println("adding: " + addPoint);
					newList.add(j, addPoint);
					if (prevPointTemp.equals(addPoint.match2)) {
						prevPointTemp = addPoint;
						addPoint = addPoint.match1;
					} else {
						prevPointTemp = addPoint;
						addPoint = addPoint.match2;
					}
				}

				System.out.println(newList);
				flattenRunPoints = newList;

				if (true) {
					knotmergecount++;
					if (knotmergecount == 3) {
						float zero = 1 / 0;
					}
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
					System.out.println(vp.isKnot);
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
			System.out.println("knotPoints Flattened:" + knotPointsFlattened);
			System.out.println(sortedSegments);
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
					System.out.println("seen points: " + seenPoints);

					System.out.println("seen points: " + basePoint.group);
					System.out.println("Potnential match seg: " + potentialSegment);
					if (count == 0) {
						return potentialSegment;
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
			System.out.println("Making New Run: " + knotPoints);
			sortedSegments = new ArrayList<>();
			ArrayList<VirtualPoint> flattenRunPoints = new ArrayList<>();
			for (int i = 0; i < knotPoints.size(); i++) {
				VirtualPoint vp = knotPoints.get(i);
				if (i + 1 >= knotPoints.size()) {
					if (vp.isRun) {
						Run run = ((Run) vp);
						if (run.endpoint1.equals(run.basePoint1)) {
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
				VirtualPoint vp2 = knotPoints.get(i + 1);
				if (vp.isRun) {
					Run run = ((Run) vp);
					if ((run.endpoint1.equals(run.basePoint1)
							&& ((vp2.isRun && ((Run) vp2).endpoint1.contains(run.match1endpoint))
									|| vp2.contains(run.match1endpoint)))
							|| (run.endpoint1.equals(run.basePoint2)
									&& ((vp2.isRun && ((Run) vp2).endpoint1.contains(run.match2endpoint))
											|| vp2.contains(run.match2endpoint)))) {

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

			for (int i = 0; i < flattenRunPoints.size() - 1; i++) {
				VirtualPoint vp = flattenRunPoints.get(i);
				VirtualPoint vp2 = flattenRunPoints.get(i + 1);
				System.out.println(vp + " " + vp.group);
				System.out.println(vp2 + " " + vp2.group);
				if (!vp2.group.equals(vp.group) && (vp.group.isRun || vp2.group.isRun)) {
					System.out.println(vp + " " + vp.group);
					System.out.println(vp2 + " " + vp2.group);
					System.out.println(vp2.group.equals(vp.group));
					System.out.println("fixing match2 for: " + vp.fullString());

					System.out.println("new match 2: " + vp2.fullString());

					Segment s = vp.getClosestSegment(vp2);
					VirtualPoint bp1 = s.getKnotPoint(vp.externalVirtualPoints);
					VirtualPoint bp2 = s.getOther(bp1);
					System.out.println("bp1:" + bp1);
					System.out.println("bp2:" + bp2);
					if (vp.match1 == null || vp.match1.contains(bp2)) {
						vp.match1 = vp2;
						vp.basePoint1 = (Point) bp1;
						vp.match1endpoint = (Point) bp2;
						vp.s1 = s;
					} else if (vp.match2 == null || vp.match1.contains(bp2)) {
						vp.match2 = vp2;
						vp.basePoint2 = (Point) bp1;
						vp.match2endpoint = (Point) bp2;
						vp.s2 = s;

					}
					if (vp2.match1 == null || vp2.match1.contains(bp1)) {
						vp2.match1 = vp;
						vp2.basePoint1 = (Point) bp2;
						vp2.match1endpoint = (Point) bp1;
						vp2.s1 = s;
					} else if (vp2.match2 == null || vp2.match2.contains(bp1)) {
						vp2.match2 = vp;
						vp2.basePoint2 = (Point) bp2;
						vp2.match2endpoint = (Point) bp1;
						vp2.s2 = s;
					}
					vp.numMatches = 2;
					vp2.numMatches = 2;

					System.out.println("fixed match2: " + vp.fullString());

					System.out.println("new match 2 after: " + vp2.fullString() + "\n");
				}
			}

			System.out.println(flattenRunPoints);
			if (flattenRunPoints.size() != knotPoints.size()) {
				runmergecount++;
				if (runmergecount == 10) {
					float zero = 1 / 0;
				}
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
				System.out.println(vp);
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
					System.out.println(vp.isKnot);
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
			System.out.println("runPointsFlattened:" + knotPointsFlattened);
			System.out.println("externalKnotPoints: " + sortedSegments);
			System.out.println("knotPoints: " + this.knotPoints);
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
					System.out.println("Potnential run match seg: " + potentialSegment);
					if (count == 0) {
						return potentialSegment;
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

	int breakCount = 0;

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
			System.out.println("visited:" + visited);
			System.out.println("toVisit:" + toVisit);
			System.out.println("knots:" + knots);
			if (mainPoint.numMatches > 2) {
				float zero = 1 / 0;
			}
			Segment potentialSegment1 = mainPoint.getPointer(1);
			Point pointer1 = (Point) potentialSegment1.getOtherKnot(mainPoint.topGroup);
			System.out.println("Point " + mainPoint + " points to: " + pointer1 + " (" + pointer1.topGroup + ")");

			Segment potentialSegment11 = pointer1.topGroup.getPointer(1);
			Point pointer11 = (Point) potentialSegment11.getOtherKnot(pointer1.topGroup);

			Segment potentialSegment12 = pointer1.topGroup.getPointer(2);
			Point pointer12 = (Point) potentialSegment12.getOtherKnot(pointer1.topGroup);
			System.out.println("Point " + pointer1 + " points to: " + pointer11 + " (" + pointer11.topGroup + ") and "
					+ pointer12 + " (" + pointer12.topGroup + ")");

			Segment potentialSegment2 = mainPoint.getPointer(2);
			Point pointer2 = (Point) potentialSegment2.getOtherKnot(mainPoint.topGroup);
			System.out.println("Point " + mainPoint + " points to: " + pointer2 + " (" + pointer2.topGroup + ")");

			Segment potentialSegment21 = pointer2.topGroup.getPointer(1);
			Point pointer21 = (Point) potentialSegment21.getOtherKnot(pointer2.topGroup);

			Segment potentialSegment22 = pointer2.topGroup.getPointer(2);
			Point pointer22 = (Point) potentialSegment22.getOtherKnot(pointer2.topGroup);
			System.out.println(potentialSegment22);
			System.out.println(
					"Point " + pointer2.topGroup + " points to: " + pointer21 + " (" + pointer21.topGroup + ") and "
							+ pointer22 + " (" + pointer22.topGroup + ")");

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
			System.out.println("mainPoint.s1: " + mainPoint.s1);
			System.out.println("mainPoint " + mainPoint);
			System.out.println("pointer1 " + pointer1);
			System.out.println("potentialSegment1 " + potentialSegment1);
			System.out.println("potentialSegment2 " + potentialSegment2);
			System.out.println("potentialSegment11 " + potentialSegment11);
			System.out.println("potentialSegment12 " + potentialSegment12);
			System.out.println("potentialSegment21 " + potentialSegment21);
			System.out.println("potentialSegment22 " + potentialSegment22);
			System.out.println(potentialSegment12);
			System.out.println("Point " + mainPoint + " points to: " + vp1 + "(" + pointer1
					+ ") " + " and " + vp2 + "("
					+ pointer2 + ") ");
			System.out.println(mainPoint.equals(vp11));
			System.out.println("vp11: " + vp11);
			System.out.println("vp12: " + vp12);
			System.out.println("vp21: " + vp21);
			System.out.println("vp22: " + vp22);
			boolean inKnots1 = knots.contains(vp1);
			boolean inKnots2 = knots.contains(vp2);
			System.out.println(mainPoint.equals(vp11));
			System.out.println(!inKnots1);
			System.out.println(vp1.numMatches < 2);
			System.out.println(mainPoint.numMatches == 0 || !mainPoint.s1.equals(potentialSegment11));
			if (pointer2.topGroup.contains(pointMap.get(5)) && pointer2.topGroup.contains(pointMap.get(2))) {
				breakCount++;
				if (breakCount == 7) {
					// float zero = 1 / 0;
				}
			}
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
					if (runList.contains(runFailedMatch1) || runList.contains(runFailedMatch2)) {
						Segment thirdMatch1 = runFailedMatch1.getPointer(3);
						VirtualPoint me1 = thirdMatch1.getOtherKnot(runFailedMatch1);
						VirtualPoint bp1 = thirdMatch1.getOther(me1);
						boolean flag1 = runFailedMatch1.contains(bp1);

						Segment thirdMatch2 = runFailedMatch2.getPointer(3);
						VirtualPoint me2 = thirdMatch1.getOtherKnot(runFailedMatch2);
						VirtualPoint bp2 = thirdMatch1.getOther(me2);
						boolean flag2 = runFailedMatch1.contains(bp2);
						if (flag1 || flag2) {
							System.out.println("Half Knot Found: ");
							System.out.println(runList);
							System.out.println(endPoint1);
							System.out.println(runFailedMatch1);
							System.out.println(thirdMatch1);
							System.out.println(endPoint2);
							System.out.println(runFailedMatch2);
							System.out.println(thirdMatch2);
							halfKnotCount++;
							if (halfKnotCount > 1) {
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
		int idx = 0;
		while (unvisited.size() > 1) {
			ArrayList<VirtualPoint> knots = createKnots();
			System.out.println("\n================= - Layer: " + idx + " - =================");
			unvisited = knots;
			System.out.println("unvisited:" + unvisited);
			System.out.println("visited:" + visited);
			System.out.println("================= - Layer: " + idx + " - =================\n");
			if (idx > 10) {
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
		System.out.println(unvisited);
		// seems like there are three cases: combining two knots, (need to remove two
		// segments and add two with the lowest cost increase)
		// pulling apart two knot that has two endpoints and want to cut different
		// segments (need to match the two segments that lost an end)
		// pulling apart two knot that has two endpoints and want to cut the same
		// segment (just remove the segment)
		int knotsCleared = 0;
		if (unvisited.size() == 1) {
			System.out.println("one Knot, unwrapping");
			VirtualPoint gp1 = unvisited.get(0);
			if (gp1.isKnot) {
				unvisited = ((Knot) gp1).knotPoints;
			}
		}

		System.out.println(unvisited);

		// move on to the cutting phase
		VirtualPoint prevPoint = unvisited.get(unvisited.size() - 1);
		for (int i = 0; i < unvisited.size(); i++) {

			VirtualPoint vp = unvisited.get(i);
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
				knotsCleared++;
				if (knotsCleared == A.size()) {
					float zero = 1 / 0;
				}
				Knot knot = (Knot) vp;
				System.out.println("Found Knot!" + " match1: " + knot.match1 + " basepoint 1: " + knot.basePoint1
						+ " match2: " + knot.match2 + " basepoint 2: " + knot.basePoint2);

				System.out.println("knot: " + knot.fullString());
				VirtualPoint knotPoint1 = knot.basePoint1;
				if (!knotPoint1.group.equals(knot)) {
					knotPoint1 = knot.pointToInternalKnot.get(knotPoint1.id);
				}
				System.out.println("knotpoint1: " + knotPoint1.fullString());
				VirtualPoint cutPoint1 = knotPoint1.match2endpoint;
				if (!cutPoint1.group.equals(knot)) {
					cutPoint1 = knot.pointToInternalKnot.get(cutPoint1.id);
				}
				VirtualPoint external1 = knot.match1;
				VirtualPoint external2 = knot.match2;
				VirtualPoint knotPoint2 = knot.basePoint2;
				System.out.println("knotpoint2: " + knotPoint2.fullString());
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

					cutPoint2 = knotPoint1.match1endpoint;
					if (!cutPoint2.group.equals(knot)) {
						cutPoint2 = knot.pointToInternalKnot.get(cutPoint2.id);
					}
					// this checking needs to be recursive down to the base when dealing with nested
					// knots
					Segment s1 = knotPoint1.getClosestSegment(external1);
					Segment s11 = cutPoint1.getClosestSegment(external2);
					Segment s12 = cutPoint2.getClosestSegment(external2);
					Segment s1i1 = cutPoint1.getClosestSegment(knotPoint1);
					Segment s1i2 = cutPoint2.getClosestSegment(knotPoint1);

					Segment s2 = knotPoint1.getClosestSegment(external2);
					Segment s21 = cutPoint1.getClosestSegment(external1);
					Segment s22 = cutPoint2.getClosestSegment(external1);
					Segment cutSegment1 = knotPoint1.getClosestSegment(cutPoint1);
					Segment cutSegment2 = knotPoint1.getClosestSegment(cutPoint2);
					System.out.println(s1 + "" + s11 + "" + "cut: " + cutSegment1);
					System.out.println(s1.distance + s11.distance);
					double d11 = s1.distance + s11.distance;
					System.out.println(s1 + "" + s12 + "" + "cut: " + cutSegment2);
					System.out.println(s1.distance + s12.distance);
					double d12 = s1.distance + s12.distance;
					System.out.println(s2 + "" + s21 + "" + "cut: " + cutSegment1);
					System.out.println(s2.distance + s21.distance);
					double d21 = s2.distance + s21.distance;
					System.out.println(s2 + "" + s22 + "" + "cut: " + cutSegment2);
					System.out.println(s2.distance + s22.distance);
					double d22 = s2.distance + s22.distance;
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
						} else {
							external1.match1 = knotPoint1;
							knotPoint1.match2endpoint = external1.basePoint1;
							external1.match1endpoint = knotPoint1.basePoint2;
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
						} else {
							external2.match1 = cutPoint1;
							cutPoint1.match2endpoint = external2.basePoint1;
							external2.match1endpoint = cutPoint1.basePoint2;
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
						} else {
							external1.match1 = knotPoint1;
							knotPoint1.match1endpoint = external1.basePoint1;
							external1.match1endpoint = knotPoint1.basePoint1;
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
						} else {
							external2.match1 = cutPoint2;
							cutPoint2.match2endpoint = external2.basePoint1;
							external2.match1endpoint = cutPoint2.basePoint2;
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
						} else {
							external2.match1 = knotPoint1;
							knotPoint1.match2endpoint = external2.basePoint1;
							external2.match1endpoint = knotPoint1.basePoint2;
						}
						cutPoint1.match2 = external1;
						VirtualPoint p22 = external1;
						if (external1.isKnot) {
							p22 = s21.getKnotPoint(((Knot) external1).knotPointsFlattened);
						}
						cutPoint1.basePoint2 = (Point) s21.getOther(p22);
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
						} else {
							external2.match1 = knotPoint1;
							knotPoint1.match1endpoint = external2.basePoint1;
							external2.match1endpoint = knotPoint1.basePoint1;
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
						} else {
							external1.match1 = cutPoint2;
							cutPoint2.match2endpoint = external1.basePoint1;
							external1.match1endpoint = cutPoint2.basePoint2;
						}
						System.out.println();
						System.out.println("knotPoint1: " + knotPoint1.fullString());
						System.out.println("external2: " + external2.fullString());
						System.out.println("cutPoint2: " + cutPoint2.fullString());
						System.out.println("external1: " + external1.fullString());
						System.out.println();
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

							Segment s1 = knotPoint1.getClosestSegment(external1);
							Segment s11 = cutPoint1.getClosestSegment(external2);
							Segment s12 = cutPoint2.getClosestSegment(external2);
							Segment s1i1 = cutPoint1.getClosestSegment(knotPoint1);
							Segment s1i2 = cutPoint2.getClosestSegment(knotPoint1);

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

							System.out.println(s2 + "" + s1 + "" + s3 + "" + "cut: " + cutSegment2);
							System.out.println(s2.distance + s1.distance + s3.distance);
							double d3 = s1.distance + s2.distance + s3.distance;
							if (d3 < d22 && d3 < d11) {
								System.out.println("Cutting and attaching cutpoints");
								System.out.println(knotPoint1.fullString());
								System.out.println(knotPoint2.fullString());
								System.out.println(cutPoint2.fullString());
								System.out.println(cutPoint1.fullString());
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

								Point nearestcp1bp = cutPoint1.getNearestBasePoint(cutPoint2);
								Point nearestcp2bp = cutPoint2.getNearestBasePoint(cutPoint1);
								if (cutPoint1.match1.equals(knotPoint1)) {
									cutPoint1.match1 = cutPoint2;
									cutPoint1.match1endpoint = nearestcp2bp;
									cutPoint1.basePoint1 = nearestcp1bp;
								} else {
									cutPoint1.match2 = cutPoint2;
									cutPoint1.match2endpoint = nearestcp2bp;
									cutPoint1.basePoint2 = nearestcp1bp;
								}

								if (cutPoint2.match1.equals(knotPoint2)) {
									cutPoint2.match1 = cutPoint1;
									cutPoint2.match1endpoint = nearestcp1bp;
									cutPoint2.basePoint1 = nearestcp2bp;
								} else {
									cutPoint2.match2 = cutPoint1;
									cutPoint2.match2endpoint = nearestcp1bp;
									cutPoint2.basePoint2 = nearestcp2bp;
								}
								System.out.println();
								System.out.println(knotPoint1.fullString());
								System.out.println(knotPoint2.fullString());
								System.out.println(cutPoint2.fullString());
								System.out.println(cutPoint1.fullString());
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
								} else {
									external1.match1 = knotPoint1;
									knotPoint1.match2endpoint = external1.basePoint1;
									external1.match1endpoint = knotPoint1.basePoint2;
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
								} else {
									external2.match1 = cutPoint1;
									cutPoint1.match2endpoint = external2.basePoint1;
									external2.match1endpoint = cutPoint1.basePoint2;
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
								} else {
									external2.match1 = knotPoint2;
									knotPoint2.match2endpoint = external2.basePoint1;
									external2.match1endpoint = knotPoint2.basePoint1;
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
								} else {
									external1.match1 = cutPoint2;
									cutPoint2.match2endpoint = external1.basePoint1;
									external1.match1endpoint = cutPoint2.basePoint2;
								}
								System.out.println();
								System.out.println("knotPoint1: " + knotPoint1.fullString());
								System.out.println("knotPoint2: " + knotPoint2.fullString());
								System.out.println("external2: " + external2.fullString());
								System.out.println("cutPoint2: " + cutPoint2.fullString());
								System.out.println("external1: " + external1.fullString());
								System.out.println();
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
				unvisited.remove(vp);
				System.out.println(unvisited);
				System.out.println("prevPoint: " + prevPoint.fullString());
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
