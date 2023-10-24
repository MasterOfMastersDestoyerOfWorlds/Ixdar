package shell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.commons.math3.util.Pair;

import shell.PointND.Double;

/**
 * A set of all of the points in the current TSP problem
 */
public class PointSet extends ArrayList<PointND> {
	private int getLargestDim() {
		int maxDim = 0;
		for (PointND p : this) {
			if (maxDim < p.getDim()) {
				maxDim = p.getDim();
			}
		}
		return maxDim;
	}

	public PointND getByID(int ID) {
		for (PointND p : this) {
			if (p.getID() == ID) {
				return p;
			}
		}
		return null;
	}

	/**
	 * This divides the point set into numerous convex shells that point to their
	 * child and parent shells.
	 * 
	 * @param d
	 * 
	 * @return the outermost shell of the point set that conatins all other shells
	 */

	public Shell toShells(DistanceMatrix d) {
		PointSet copy = (PointSet) this.clone();
		Shell rootShell = null, currShell = null;
		while (copy.size() > 0) {
			Shell hull = null;
			// makes the first shell
			if (rootShell == null) {
				rootShell = new Shell();
				currShell = rootShell;
			}
			// makes a new child shell for the currShell
			else {
				Shell nextShell = new Shell(currShell, null);
				currShell.setChild(nextShell);
				currShell = nextShell;
			}
			DistanceMatrix d1 = new DistanceMatrix(copy, d);
			assert (d1.getZero() == d.getZero());
			assert (d1.getMaxDist() == d.getMaxDist());
			assert (d1.size() == copy.size());
			// if (copy.getLargestDim() == 2) {
			// REEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE

			// https://en.wikipedia.org/wiki/Isoperimetric_inequality#In_Rn
			// https://en.wikipedia.org/wiki/Mean_squared_displacement
			// maybe we want to take the mean squared distnce of every angle to pi
			// https://link.springer.com/article/10.1007/s10851-015-0618-4
			// https://en.wikipedia.org/wiki/Gamma_function
			// Gamma(n/2 + 1)/((n+2)*pi/N)^(N/2) * lambda_N(S)/trace(sigma)^(n/2)
			// mu is the mean of S
			// sigma = 1/N * sum((x_i - mu)(x_i-mu)^T)
			// lambda_N(s) = Lebesgue measure (n-d volume) lie=nes do not have volume in
			// nd space so it seems like this method will not work
			// https://stackoverflow.com/questions/65185721/fitting-a-sphere-to-3d-points
			// fit a sphere to the points and look att the error
			// distance to the surface of the sphere is the abs(distancee to the centroid -
			// radius)
			// https://jekel.me/2015/Least-Squares-Sphere-Fit/
			// https://web.mat.upc.edu/sebastia.xambo/santalo2016/pdf/LD/LD4.pdf
			// https://commons.apache.org/proper/commons-math/userguide/leastsquares.html
			// alternativeley find the centroid, average the distance to the centroid and
			// then find the error in the distances
			// multiply the error by the length of the segments like they are also points,
			// but you'd neeed to be able to integrate over that line in the distance to the
			// edge
			// you can think of each segmetn as a triangle starting from the centroid and
			// going to the bouding circle cut by the segment. get the angle of that
			// triangle and convert that to area of a circle and then subtract out the area
			// of the triangle
			double max = java.lang.Double.MAX_VALUE;
			// PointND minp = null;
			PointND centroid = d1.findCentroid();
			// maybbe instead of using giftwrapping algo, buuild path based on varience of
			// sphere to begin with
			// start with the segment (n^2 op) that has the leeast varience when compared to
			// the centroid for the whole set
			// Shell temphull = findMaxAngleMinDistancePaths(copy, d1, p, centroid);
			Shell temphull = copy.findMinVariencePath(copy, d1);
			// temphull = copy.minimizeVarianceOfSphere(temphull, copy, d1);
			System.out.println("reee: " + " " + temphull.getVarienceOfSphere(copy, d1) + " " + temphull.toString());
			if (temphull.getVarienceOfSphere(copy, d1) < max) {
				max = temphull.getVarienceOfSphere(copy, d1);
				hull = temphull;
				// minp = p;
			}

			System.out.println("++++++++++ " + "  " + max + " " + hull);
			/*
			 * } else { hull = convexHullND(copy); }
			 */

			currShell.addAll(hull);
			for (PointND p : hull) {
				copy.remove(p);
			}

			// make sure that the convex hulls are in reduced forms(this is guaranteed in 2D
			// but not in higher dimensions).
			/*
			 * Shell reducedShell = Shell.collapseReduce(currShell, new Shell(), 0);
			 * currShell.removeAll(currShell); currShell.addAll(reducedShell);
			 */
		}

		// might want to maximize this number
		System.out.println(rootShell.getLengthRecursive());

		rootShell.updateOrder();
		assert (rootShell.sizeRecursive() == this.size())
				: "Found size: " + rootShell.sizeRecursive() + " Expected size: " + this.size();

		return rootShell;
	}

	public PointSet getAllDummyNodesAndParents() {
		PointSet result = new PointSet();
		for (PointND pt : this) {
			if (pt.isDummyNode()) {
				result.add(pt);
				result.add(pt.getDummyParents().first);
				result.add(pt.getDummyParents().last);
			}
		}
		return result;
	}

	public double SumAnglesToPoint(PointND p, DistanceMatrix d) {
		double sum = 0.0;
		for (PointND pt : this) {
			for (PointND pt2 : this) {
				if (!pt.equals(pt2) && !pt.equals(p) && !pt2.equals(p)) {
					sum += Vectors.findAngleSegments(pt2, pt, p, d);
				}
			}
		}
		return sum;
	}

	public double SumDistancesToPoint(PointND p, DistanceMatrix d) {
		double sum = 0.0;
		for (PointND pt : this) {
			if (!pt.equals(p)) {
				sum += d.getDistance(pt, p);
			}
		}
		return sum;
	}

	/**
	 * Does the 2d gift-wrapping/javis march algorithm to find the convex hull of a
	 * set of points and add those points
	 * 
	 * @param ps
	 * @param d
	 * @returnthe convex hull of the set of points in 2 dimensions
	 */
	public Shell findMaxAngleMinDistancePaths(PointSet ps, DistanceMatrix d, PointND lastRight,
			PointND behindLastRight) {
		Shell outerShell = new Shell();

		// System.out.println(ps.get(0).getID());
		if (ps.size() <= 1) {
			outerShell.addAll(ps);
			return outerShell;
		}

		outerShell.add(lastRight);
		double maxAngle = -1;
		PointND maxPoint = behindLastRight;
		for (PointND p : ps) {
			double angle = Vectors.findAngleSegments(behindLastRight, lastRight, p, d);
			if (angle > maxAngle && !outerShell.contains(p) && !lastRight.equals(p) && !behindLastRight.equals(p)) {

				maxAngle = angle;
				maxPoint = p;
			}
		}
		if (!maxPoint.equals(behindLastRight)) {
			outerShell.add(maxPoint);
			behindLastRight = lastRight;
			lastRight = maxPoint;

		}

		boolean breakFlag = false;

		PointND lastLeft = behindLastRight;

		PointND behindLastLeft = lastRight;

		/*
		 * SEE size 10 Rot 26 TODO Seems to mess up when multiple dummy points are in
		 * play either one or the other needs to be reversed i cant tell if this is a
		 * problem with the shell creation process or the distance matrix calculations
		 * should make a seeries of tests with many dummy points and low numbber of
		 * other points
		 */

		// Creates the next convex shell
		while (!breakFlag) {

			maxAngle = 0;
			maxPoint = null;
			boolean left = true;
			ArrayList<PointWrapper> angles = new ArrayList<PointWrapper>();
			// System.out.println("lastLeft: " + lastLeft.getID() + "\nbehindLastLeft: " +
			// behindLastLeft.getID());

			// System.out.println("lastRight: " + lastRight.getID() + "\nbehindLastRight: "
			// + behindLastRight.getID());
			for (PointND nextPoint : ps) {
				// TODO figure out whats happeninng here

				if ((nextPoint.equals(lastLeft)) || (!outerShell.contains(nextPoint) && !lastRight.equals(nextPoint)
						&& !behindLastRight.equals(nextPoint))) {
					java.lang.Double rightAngle = Vectors.findAngleSegments(behindLastRight, lastRight, nextPoint, d);
					PointWrapper rightPoint = new PointWrapper(rightAngle, nextPoint, false);
					angles.add(rightPoint);
					// System.out.println("Adding Right Point: " + nextPoint.getID() + " Angle: " +
					// (180*rightAngle/Math.PI));
				}

				if ((nextPoint.equals(lastRight)) || (!outerShell.contains(nextPoint) && !lastLeft.equals(nextPoint)
						&& !behindLastLeft.equals(nextPoint))) {
					java.lang.Double leftAngle = Vectors.findAngleSegments(nextPoint, lastLeft, behindLastLeft, d);
					PointWrapper leftPoint = new PointWrapper(leftAngle, nextPoint, true);
					angles.add(leftPoint);
					// System.out.println("Adding Left Point: " + nextPoint.getID() + " Angle: " +
					// (180*leftAngle/Math.PI));
				}
			}

			while (true) {
				PointWrapper maxPointWrap = null;
				if (angles.size() > 0) {
					Collections.sort(angles);
					maxPointWrap = angles.get(angles.size() - 1);
				}
				if (maxPointWrap == null || maxPointWrap.p.equals(lastLeft) || maxPointWrap.p.equals(lastRight)) {
					breakFlag = true;
					break;
				}

				outerShell.add(maxPointWrap.p);
				if (!Shell.isReduced(outerShell, d)) {
					angles.remove(maxPointWrap);
					outerShell.remove(maxPointWrap.p);
				} else {
					if (maxPointWrap.left) {
						outerShell.remove(maxPointWrap.p);
						behindLastLeft = lastLeft;
						lastLeft = maxPointWrap.p;
						outerShell.add(0, lastLeft);

					} else {
						outerShell.remove(maxPointWrap.p);
						behindLastRight = lastRight;
						lastRight = maxPointWrap.p;
						outerShell.add(lastRight);
					}

					break;

				}

			}
		}
		assert (Shell.isReduced(outerShell, d));
		return outerShell;
	}

	/**
	 * IDEA: make a 2d array of all segment variences, use dijkstras to connect the
	 * anode
	 * and its anode remove those points, do it again now you have a loop
	 * 
	 * stupid no garuntee that it surround the centroid
	 * 
	 * IDEA: go in order of distance to the centroid and find the minimum varience
	 * place to put it in, continue till there is no place that will decrease the
	 * varience of the subset
	 * 
	 * will work in 2d dubious in higher dim
	 * 
	 * IDEA: start with the set of all segments, throw away the largest varience
	 * segment until
	 * the varience of the subset does not decrease
	 * 
	 * IDEA:each vertex is like an umbrella,precompute the two smallest varience
	 * segments,
	 * start with two smallest varience sgements idk what next this is just greedy
	 * algo
	 * 
	 * get the set of segments tighten the restriction on varience until the only
	 * segments
	 * left form a single loop and are <= n can sort by segment varience
	 * 
	 * 
	 * @param ps
	 * @param d
	 * @returnthe convex hull of the set of points in 2 dimensions
	 */
	public Shell findMinVariencePath(PointSet ps, DistanceMatrix d) {
		Shell shell = new Shell();
		// PointND centroid = d.findNSphereCenter();
		// double radius = d.getnSphereRadius();

		shell.addAll(ps);
		System.out.println(ps);
		if (shell.size() <= 2) {
			return shell;
		}

		ArrayList<PointDistanceWrapper<Segment>> segments = new ArrayList<PointDistanceWrapper<Segment>>();
		/* */
		PointSet centroids = new PointSet();
		PointSet nd = d.toPointSet();

		// centroids.addAll(nd);
		// centroids.add(nd.get(0));
		// HashMap<Segment, PointND> midpoints = d.findMidPoints(nd);
		// centroids.add(midpointCenter);

		// centroids.add(d.findCentroid(nd));
		centroids.add(d.findNSphereCenter(nd));

		for (int k = 0; k < centroids.size(); k++) {
			PointND centroid = centroids.get(k);
			double radius = d.getDistance(findAnoid(nd, centroid, d), centroid);

			for (int i = 0; i < ps.size(); i++) {
				for (int j = 0; j < ps.size(); j++) {
					if (i != j) {
						PointND one = ps.get(i);
						PointND two = ps.get(j);
						Segment s = new Segment(one, two);

						if (!(one.equals(centroid) || two.equals(centroid))) {
							double var = Vectors.getDifferenceToSphere(one, two, centroid, radius, d);
							// double var = d.getDistance(s.first, s.last);
							// need to actually get distance to sphere
							// double var = Vectors.getDifferenceToSphereFromMidpoint(midpoints.get(s),
							// centroid, radius);
							PointDistanceWrapper<Segment> p = new PointDistanceWrapper<Segment>(var, s);
							if (!segments.contains(p)) {
								segments.add(p);
							} else {
								int idx = segments.indexOf(p);
								segments.get(idx).distance = var;
							}
						}
					}
				}
			}
		}
		segments.sort(null);

		System.out.println();

		// Could we possiby reformat these segments as points and then iterate until
		// there are no points left
		// or is therrer some more complicated way of choosing
		// first go through and find all of the pairs and then tack on the leftovers to
		// wherever
		// Needs to be a full statement of this its gonna go between. This and this
		// otherwise delete segment its a loop, using bucket system
		ArrayList<Shell> shells = new ArrayList<Shell>();
		HashMap<Integer, Integer> locs = new HashMap<Integer, Integer>();
		ArrayList<PointND> inserted = new ArrayList<PointND>();
		ArrayList<PointND> singletons = new ArrayList<PointND>(ps);
		ArrayList<Segment> pairs = getKnownPairs(segments);
		for (int i = 0; i < pairs.size(); i++) {
			Segment s = pairs.get(i);
			Shell nShell = new Shell();
			nShell.add(s.first);
			nShell.add(s.last);
			shells.add(nShell);
			locs.put(s.first.getID(), shells.size() - 1);
			locs.put(s.last.getID(), shells.size() - 1);
			// bucket.remove(s);
			inserted.add(s.first);
			inserted.add(s.last);
		}
		for (int i = 0; i < ps.size(); i++) {
			PointND p = ps.get(i);
			if (!inserted.contains(p)) {
				Shell nShell = new Shell();
				nShell.add(p);
				shells.add(nShell);
				locs.put(p.getID(), shells.size() - 1);
				inserted.add(p);
			}
		}

		Bucket bucket = new Bucket();
		for (int i = 0; i < segments.size(); i++) {
			bucket.add(segments.get(i).s, shells);
		}

		System.out.println(shells);
		System.out.println(bucket);

		ArrayList<Segment> matches = bucket.getMatches(shells);
		System.out.println(matches);
		int idx = 0;
		int last = 0;

		while (getShellsWithKeys(shells, locs).size() > 3) {
			System.out.println(
					"========================================NEXT ROUND ========================================="
							+ bucket.size());
			if (bucket.deepsize() == last) {
				System.out.println("im gonna cum:");
				System.out.println(bucket);
				System.out.println();
			}
			if(idx >= 6){
				printShellsWithKeys(shells, locs);
			}
			assert(idx < 6);
			assert (bucket.size() != last);
			// Find Knots
			ArrayList<PointND> knotPoints = new ArrayList<PointND>();
			ArrayList<Segment> knotList = new ArrayList<Segment>();
			ArrayList<Segment> twoKnotList = new ArrayList<Segment>();
			ArrayList<ThreeKnot> threeKnotList = new ArrayList<ThreeKnot>();
			System.out.println("\n matchlist\n" + matches + "\n");
			for (Segment s : matches) {
				ArrayList<Segment> knotSegments = bucket.checkPopMatches(s);
				if (knotSegments.size() == 2) {
					knotSegments
							.add(Segment.findFourthSegment(s, knotSegments.get(0), knotSegments.get(1)));
					knotSegments.add(s);
					System.out.println(
							"\n-----------------------Knot Found -------------------------------------------");

					System.out.println(knotSegments);
					System.out.println(s);
					ArrayList<PointND> knotEndpoints = flattenSegmentList(knotSegments);
					System.out.println("Knot Endpoints " + knotEndpoints);
				}
				if (bucket.isTwoKnot(s, matches)) {
					if (knotPoints.contains(s.first) && knotPoints.contains(s.last)) {
						ThreeKnot threeKnot = new ThreeKnot(bucket);
						threeKnot.add(s);
						System.out.println("-----------------------3Knot Found------------------");
						ArrayList<Segment> removelList = new ArrayList<Segment>();
						for (Segment knot : twoKnotList) {
							if (knot.contains(s.first) || knot.contains(s.last)) {
								removelList.add(knot);
							}
						}
						twoKnotList.removeAll(removelList);
						for (Segment k : removelList) {
							threeKnot.add(k);
						}
						threeKnotList.add(threeKnot);
					} else {
						twoKnotList.add(s);
					}
					if (!knotPoints.contains(s.first)) {
						knotPoints.add(s.first);
					}
					if (!knotPoints.contains(s.last)) {
						knotPoints.add(s.last);
					}
					knotList.add(s);
				}
			}

			System.out.println("Knot List: " + knotPoints);

			boolean match = false;
			ArrayList<Segment> toRemove = new ArrayList<Segment>();

			// Find Matches
			for (int i = 0; i < matches.size(); i++) {
				Segment s = matches.get(i);
				if (!knotPoints.contains(s.first) && !knotPoints.contains(s.last)) {
					if (!inserted.contains(s.first) && !inserted.contains(s.last)) {
						Shell nShell = new Shell();
						nShell.add(s.first);
						nShell.add(s.last);
						shells.add(nShell);
						locs.put(s.first.getID(), shells.size() - 1);
						locs.put(s.last.getID(), shells.size() - 1);
						bucket.remove(s);
						inserted.add(s.first);
						inserted.add(s.last);
						singletons.remove(s.last);
						singletons.remove(s.first);
					} else if (inserted.contains(s.first) || inserted.contains(s.last)) {
						// steps
						// check if the other side of the shell is also in the match list

						// repeat for other side of segment
						// if its not in the match list hold off for now
						Set<Integer> locset = new HashSet<Integer>();
						locset.addAll(locs.values());

						if (locset.size() <= 3) {// this is retarded you are a retard, if it gets down tol this just
													// comput
													// the best route fucktard
							int floc = locs.get(matches.get(i).first.getID());
							Shell fShell = shells.get(floc);
							Shell lShell = shells.get(locs.get(matches.get(i).last.getID()));
							Segment s1 = matches.get(i);
							fShell.addAllAtSegment(s1.first, s1.last, lShell);
							for (PointND p : lShell) {
								locs.put(p.getID(), floc);
							}
							bucket.remove(s1);
							bucket.removeAllInternal(fShell, matches);
							if (!fShell.isEndpoint(s1.first)) {
								bucket.removeAll(s1.first);
								matches.removeIf(n -> (n.contains(s1.first)));
							}
							if (!fShell.isEndpoint(s1.last)) {
								bucket.removeAll(s1.last);
								matches.removeIf(n -> (n.contains(s1.last)));
							}
							System.out.println("MATCH!!: " + s1 + " ");
							continue;
						}

						int floc = locs.get(s.first.getID());
						Shell fShell = shells.get(floc);
						System.out.println(s);
						PointND fOpp = fShell.getOppositeOutside(s.first);
						System.out.println("match " + s + " f " + s.first.getID() + " fOpp " + fOpp.getID()
								+ " first shell " + fShell);
						int lloc = locs.get(s.last.getID());
						Shell lShell = shells.get(lloc);
						PointND lOpp = lShell.getOppositeOutside(s.last);
						System.out.println(
								"match " + s + " l " + s.last.getID() + " lOpp " + lOpp.getID() + "last shell "
										+ lShell);
						ArrayList<PointND> endpoints = new ArrayList<PointND>();
						endpoints.add(s.first);
						endpoints.add(s.last);
						endpoints.add(lOpp);
						// TODO reexamine skip flag
						// somehow need to say that if you are a single point you have two matches for
						// knots
						Pair<PointND, ArrayList<Segment>> pair1 = bucket.checkMatchExcludeEndpoints(fOpp, endpoints,
								singletons);
						PointND p1 = pair1.getFirst();
						endpoints.remove(lOpp);
						endpoints.add(fOpp);
						ArrayList<PointND> copy = new ArrayList<PointND>(endpoints);
						Pair<PointND, ArrayList<Segment>> pair3 = bucket.checkMatchExcludeEndpoints(lOpp, copy,
								singletons);

						if ((p1 != null && pair3.getFirst() != null)) {

							endpoints.add(p1);
							if (p1 != null) {
								endpoints.addAll(shells.get(locs.get(p1.getID())));
							}
							Pair<PointND, ArrayList<Segment>> pair2 = bucket.checkMatchExcludeEndpoints(lOpp, endpoints,
									singletons);
							copy.add(pair3.getFirst());
							if (pair3.getFirst() != null) {
								copy.addAll(shells.get(locs.get(pair3.getFirst().getID())));
							}
							copy.add(lOpp);
							copy.remove(fOpp);
							System.out.println(copy);
							System.out.println(endpoints);
							Pair<PointND, ArrayList<Segment>> pair4 = bucket.checkMatchExcludeEndpoints(fOpp, copy,
									singletons);
							PointND p2 = pair2.getFirst();

							if (p1 != null && p2 != null && locs.get(p1.getID()) == locs.get(p2.getID())) {
								System.out.println("GREER");

							}

							if ((p2 != null && p1 != null && pair3.getFirst() != null && pair4.getFirst() != null
									&& pair3.getSecond().get(1).equals(pair2.getSecond().get(1)))) {
								mergeMatch(bucket, locs, singletons, matches, s, floc, fShell, lShell);
								match = true;

								System.out.println(fShell);
								printShellsWithKeys(shells, locs);
								System.out.println(matches);

							} else {
								// note that in true pairs there is no handedness, i.e. pair1 + pair2 = pair3 +
								// pair4
								/*
								 * if (!pair2.getSecond().get(0).equals(pair2.getSecond().get(1))) {
								 * toRemove.add(pair2.getSecond().get(1));
								 * }
								 * if (!pair4.getSecond().get(0).equals(pair4.getSecond().get(1))) {
								 * toRemove.add(pair4.getSecond().get(1));
								 * }
								 */

							}
						} else {
							/*
							 * if(!pair1.getSecond().get(0).equals(pair1.getSecond().get(1))){
							 * toRemove.add(pair1.getSecond().get(1));
							 * }
							 * if(!pair3.getSecond().get(0).equals(pair3.getSecond().get(1))){
							 * toRemove.add(pair3.getSecond().get(1));
							 * }
							 */
						}
						System.out.println();
					}
				}
			}
				// unravel 2knots
				System.out.println("2-Knot List: " + twoKnotList);
				for (Segment knot : twoKnotList) {
					System.out.println("---------------------------------------------Unravel: " + knot);
					ArrayList<PointND> endpoints = new ArrayList<PointND>();
					endpoints.add(knot.first);
					endpoints.add(knot.last);
					int n = 4;
					ArrayList<Segment> prospectiveMatches = bucket.getProspectiveMatches(shells);
					ArrayList<Segment> bucket1 = bucket.getNBucketExcludeList(knot.first, bucket.get(knot.first),
							endpoints,
							n);
					ArrayList<Group> group1 = bucket.getNGroupsExcludeList(knot.first, bucket.get(knot.first),
							endpoints, matches,
							2);

					group1 = bucket.checkPointsToKnotFilter(group1, endpoints);

					ArrayList<Segment> bucket2 = bucket.getNBucketExcludeList(knot.last, bucket.get(knot.last),
							endpoints,
							n);
					ArrayList<Group> group2 = bucket.getNGroupsExcludeList(knot.last, bucket.get(knot.last), endpoints,
							matches,
							2);
							
					group2 = bucket.checkPointsToKnotFilter(group2, endpoints);
					ArrayList<PointND> prospects = new ArrayList<PointND>(Group.flattenToPoints(group1));
					prospects.addAll(Group.flattenToPoints(group2));
					ArrayList<Group> groups = new ArrayList<>(group1);
					for (Group g : group2) {
						if (!groups.contains(g)) {
							groups.add(g);
						}
					}

					/*
					 * 1. figure out what your three points are outside the knot hereafter refered
					 * to a, b and c
					 */
					prospects.remove(knot.first);
					prospects.remove(knot.last);
					System.out.println("prospects: " + prospects);
					System.out.println("Knot: " + knot.first + " first in list " + bucket1 + " Groups: " + group1);
					System.out.println("Knot: " + knot.last + " first in list " + bucket2 + " Groups: " + group2);
					if (groups.size() <= 2) {
						System.out.println("----------------Go ahead and unravel! 2-Knot: " + knot);
						/*
						 * 2. find if the two points are grouped together the two grouped points wolg [a
						 * b] should want to match together but
						 * can't
						 */

						/*
						 * 3. find the connection in their group that matches somewhere else (exclude
						 * all points in 3knot and in group)
						 * (you also may need to exclude the "point in common" g* if the match leads to
						 * a 2 knot)
						 * check that its match has a match otherwise quit for now
						 * 
						 */
						ArrayList<PointND> excludeList = new ArrayList<PointND>();
						for (PointND p : prospects) {
							if (!excludeList.contains(p)) {
								excludeList.add(p);
							}
						}
						if(groups.size() <= 1){
							continue;
						}
						if(groups.get(0).match == null || groups.get(1).match == null){
							continue;
						}
						excludeList.add(knot.first);
						excludeList.add(knot.last);
						PointND g1 = groups.get(0).outside1;
						PointND g2 = groups.get(0).outside2;
						Segment s1 = bucket.getNotInList(g1, bucket.get(g1), excludeList);
						Segment s2 = bucket.getNotInList(g2, bucket.get(g2), excludeList);
						PointND g3 = groups.get(1).outside1;
						PointND g4 = groups.get(1).outside2;
						Segment s3 = bucket.getNotInList(g3, bucket.get(g3), excludeList);
						Segment s4 = bucket.getNotInList(g4, bucket.get(g4), excludeList);

						// now check that either has a match and assign the endpoints

						PointND endpoint1;
						PointND endpoint2;
						ArrayList<Segment> mergeList = new ArrayList<Segment>();


						System.out.println("g1: "+ g1 +" S1: " + s1 + " " + excludeList);
						System.out.println("g2: "+ g2 +" S2: " + s2 + " " + excludeList);
						System.out.println("g3: "+ g3 +" S3: " + s3 + " " + excludeList);
						System.out.println("g4: "+ g4 +" S4: " + s4 + " " + excludeList);
						if (bucket.checkMatch(g1, s1, excludeList)) {
							System.out.println("non-endpoint: " + g1);
							// mergeList.add(s1);
							endpoint1 = groups.get(0).match2;
						} else if (bucket.checkMatch(g2, s2, excludeList)) {
							System.out.println("non-endpoint: " + g2);
							// mergeList.add(s2);
							endpoint1 = groups.get(0).match1;
						} else {
							System.out.println("No Match found on outside, indeterminate :(");
							continue;
						}

						if (bucket.checkMatch(g3, s3, excludeList)) {
							System.out.println("non-endpoint: " + g3);
							// mergeList.add(s3);
							endpoint2 = groups.get(1).match2;
						} else if (bucket.checkMatch(g4, s4, excludeList)) {
							System.out.println("non-endpoint: " + g4);
							// mergeList.add(s4);
							endpoint2 = groups.get(1).match1;
						} else {
							System.out.println("No Match found on outside, indeterminate :(");
							continue;
						}
						/*
						 * 4. lets say b had the outside connection, and is matching store its match
						 * points a and c are now the endpoints of the 3knot
						 * 5. check all permutations with a and c as endpoints (3 possibilities)
						 */
						System.out.println("ep1: " + endpoint1 + " ep2: " + endpoint2);
						mergeList.addAll(knot.permute(endpoint1, endpoint2));
						System.out.println("mergeList: " + mergeList);
						for (Segment segment : mergeList) {
							mergeMatch(bucket, shells, locs, singletons, matches, segment);
						}

					}

					/*
					 * System.out.println("Unravel: " + s);
					 * Segment fbucket = bucket.getNotInList(s.first, bucket.get(s.first),
					 * knotPoints);
					 * PointND fOpp = fbucket.getOtherPoint(s.first);
					 * System.out.println("Knot: " + s + " first in list " + fbucket);
					 * ArrayList<PointND> endpoints = new ArrayList<PointND>();
					 * endpoints.add(s.first);
					 * endpoints.add(s.last);
					 * 
					 * boolean knotmatch = bucket.checkMatch(s.first, fbucket);
					 * System.out.println(knotmatch);
					 * if (knotmatch) {
					 * ArrayList<PointND> knotEndpoints = new ArrayList<PointND>();
					 * knotEndpoints.add(s.first);
					 * knotEndpoints.add(s.last);
					 * Pair<PointND, ArrayList<Segment>> pair1 =
					 * bucket.checkMatchExcludeEndpoints(fOpp, knotEndpoints,
					 * singletons);
					 * 
					 * PointND p1 = pair1.getFirst();
					 * if (p1 != null) {
					 * System.out.println("KNOT MATCH!!!!!" + fbucket);
					 * mergeMatch(bucket, shells, locs, singletons, matches, fbucket);
					 * continue;
					 * }
					 * }
					 * 
					 * Segment lbucket = bucket.getNotInList(s.last, bucket.get(s.last),
					 * knotPoints);
					 * PointND lOpp = lbucket.getOtherPoint(s.last);
					 * System.out.println("Knot: " + s + " second in list " + lbucket);
					 * 
					 * knotmatch = bucket.checkMatch(s.last, lbucket);
					 * 
					 * if (knotmatch) {
					 * ArrayList<PointND> knotEndpoints = new ArrayList<PointND>();
					 * knotEndpoints.add(s.first);
					 * knotEndpoints.add(s.last);
					 * Pair<PointND, ArrayList<Segment>> pair1 =
					 * bucket.checkMatchExcludeEndpoints(fOpp, knotEndpoints,
					 * singletons);
					 * 
					 * PointND p1 = pair1.getFirst();
					 * if (p1 != null) {
					 * System.out.println("KNOT MATCH!!!!!" + lbucket);
					 * mergeMatch(bucket, shells, locs, singletons, matches, lbucket);
					 * }
					 * }
					 * System.out.println(knotmatch);
					 */
				}

				// unravel 3-knots
				System.out.println("3-Knot List: " + threeKnotList);

				for (ThreeKnot knot : threeKnotList) {
					System.out.println("Unravel: " + knot);
					ArrayList<PointND> endpoints = new ArrayList<PointND>();
					endpoints.add(knot.p1);
					endpoints.add(knot.p2);
					endpoints.add(knot.p3);
					ArrayList<Segment> bucket1 = bucket.getNBucketExcludeList(knot.p1, bucket.get(knot.p1), endpoints,
							3);
					ArrayList<Segment> bucket2 = bucket.getNBucketExcludeList(knot.p2, bucket.get(knot.p2), endpoints,
							3);
					ArrayList<Segment> bucket3 = bucket.getNBucketExcludeList(knot.p3, bucket.get(knot.p3), endpoints,
							3);
					ArrayList<PointND> prospects = new ArrayList<PointND>();
					for (int i = 0; i < 3; i++) {
						PointND p1 = bucket1.get(i).getOtherPoint(knot.p1);
						if (!prospects.contains(p1)) {
							prospects.add(p1);
						}
						PointND p2 = bucket2.get(i).getOtherPoint(knot.p2);
						if (!prospects.contains(p2)) {
							prospects.add(p2);
						}
						PointND p3 = bucket3.get(i).getOtherPoint(knot.p3);
						if (!prospects.contains(p3)) {
							prospects.add(p3);
						}
					}
					/*
					 * 1. figure out what your three points are outside the knot hereafter refered
					 * to a, b and c
					 */
					prospects.remove(knot.p1);
					prospects.remove(knot.p2);
					prospects.remove(knot.p3);
					System.out.println("prospects: " + prospects);
					System.out.println("Knot: " + knot.p1 + " first in list " + bucket1);
					System.out.println("Knot: " + knot.p2 + " first in list " + bucket2);
					System.out.println("Knot: " + knot.p3 + " first in list " + bucket3);
					//now that we have the potential matches check that they want to match with the knot

					

					if (prospects.size() <= 4) {
						System.out.println("----------------Go ahead and unravel! 3-Knot" + knot);
						/*
						 * 2. find the two grouped points wolg [a b] should want to match together but
						 * can't
						 */
						Segment groupsSegment = new Segment(prospects.get(0), prospects.get(1));
						PointND singleton = prospects.get(2);
						if (!matches.contains(groupsSegment)) {
							groupsSegment = new Segment(prospects.get(0), prospects.get(2));
							singleton = prospects.get(1);
							if (!matches.contains(groupsSegment)) {
								groupsSegment = new Segment(prospects.get(1), prospects.get(2));
								singleton = prospects.get(0);
								if (!matches.contains(groupsSegment)) {
									continue;
								}
							}
						}
						System.out.println("groupSegment: " + groupsSegment);

						/*
						 * 3. find the connection in their group that matches somewhere else (exclude
						 * all points in 3knot and in group)
						 * (you also may need to exclude the "point in common" g* if the match leads to
						 * a 2 knot)
						 * check that its match has a match otherwise quit for now
						 * 
						 */
						ArrayList<PointND> excludeList = new ArrayList<PointND>(knotPoints);
						if (!excludeList.contains(singleton)) {
							excludeList.add(singleton);
						}
						if (!excludeList.contains(groupsSegment.first)) {
							excludeList.add(groupsSegment.first);
						}
						if (!excludeList.contains(groupsSegment.last)) {
							excludeList.add(groupsSegment.last);
						}
						Segment s1 = bucket.getNotInList(groupsSegment.first, bucket.get(groupsSegment.first),
								excludeList);
						Segment s2 = bucket.getNotInList(groupsSegment.last, bucket.get(groupsSegment.last),
								excludeList);
						excludeList.remove(groupsSegment.last);
						excludeList.remove(groupsSegment.first);

						// now check that either has a match and assign the endpoints

						PointND endpoint1;
						PointND endpoint2;
						ArrayList<Segment> mergeList = new ArrayList<Segment>();
						if (bucket.checkMatch(groupsSegment.first, s1, excludeList)) {
							System.out.println("non-endpoint: " + groupsSegment.first);
							mergeList.add(s1);
							endpoint1 = groupsSegment.last;
							endpoint2 = singleton;
						} else if (bucket.checkMatch(groupsSegment.last, s2, excludeList)) {
							System.out.println("non-endpoint: " + groupsSegment.last);
							mergeList.add(s2);
							endpoint1 = groupsSegment.first;
							endpoint2 = singleton;
						} else {
							System.out.println("No Match found on outside, indeterminate :(");
							continue;
						}
						/*
						 * 4. lets say b had the outside connection, and is matching store its match
						 * points a and c are now the endpoints of the 3knot
						 * 5. check all permutations with a and c as endpoints (3 possibilities)
						 */
						System.out.println("mergeList: " + mergeList);
						System.out.println("ep1: " + endpoint1 + " ep2 " + endpoint2);
						mergeList.addAll(knot.permute(endpoint1, endpoint2));
						System.out.println("mergeList: " + mergeList);
						for (Segment segment : mergeList) {

							mergeMatch(bucket, shells, locs, singletons, matches, segment);

						}

					}

				
			}

			Set<Integer> locset = new HashSet<Integer>();
			locset.addAll(locs.values());
			if (locset.size() > 1) {
				matches = bucket.getMatches(shells);
				if (!match) {
					System.out.println("REEEEEEEEEE--------------------------------------------------" + idx);
					System.out.println(toRemove);
					System.out.println(matches);
					System.out.println(bucket);
					System.out.println(toRemove);
					for (Segment s : toRemove) {
						bucket.remove(s);
						matches.remove(s);
					}
					// todo need to check how maany matches removing each segment will generate and
					// solve the triangles that arise
					toRemove = new ArrayList<Segment>();
					ArrayList<Segment> toAdd = new ArrayList<Segment>();

					PointND freePoint = null;
					PointND freePointOther = null;
					PointND freePointOtherMatch = null;

					Segment out1 = null;
					Segment out2 = null;
					Segment singleseg = null;

					PointND knotSolvePoint = null;
					double knotSolveMinDist = java.lang.Double.MAX_VALUE;
					boolean skipFlag = false;
					for (Segment s : matches) {
						if (!skipFlag) {
							ArrayList<Segment> knotSegments = bucket.checkPopMatches(s);
							if (knotSegments.size() == 2) {
								knotSegments
										.add(Segment.findFourthSegment(s, knotSegments.get(0), knotSegments.get(1)));
								knotSegments.add(s);
								System.out.println(
										"\n-----------------------Knot Found -------------------------------------------");

								System.out.println(knotSegments);
								System.out.println(s);
								ArrayList<PointND> knotEndpoints = flattenSegmentList(knotSegments);
								System.out.println("Knot Endpoints " + knotEndpoints);
								// knotEndpoints.addAll(flattenSegmentList(matches));

								System.out.println("Knot Endpoints + matches" + knotEndpoints);
								System.out.println("first " + s.first);
								Pair<PointND, ArrayList<Segment>> pair = bucket.checkMatchExcludeEndpoints(s.first,
										knotEndpoints, singletons);

								System.out.println("Knot Endpoints " + knotEndpoints);
								System.out.println("pair1" + pair.getFirst());
								PointND endpoint1 = pair.getFirst();
								if (endpoint1 == null) {
									// wth is this even trying to do
									Segment outerseg = pair.getSecond().get(1);
									System.out.println("outerseg: " + outerseg);
									if (singletons.contains(outerseg.first) || singletons.contains(outerseg.last)) {
										System.out.println(pair.getSecond());
										if (bucket.checkMatch(outerseg.first, pair.getSecond().get(1))) {
											singleseg = pair.getSecond().get(1);
											break;
										} else {
											System.out.println("Big Retard");
										}
									}
									if (matches.contains(outerseg)) {
										System.out.println("outerseg: " + outerseg);
										out1 = bucket.getFirstInList(outerseg.first, knotEndpoints);
										out2 = bucket.getFirstInList(outerseg.last, knotEndpoints);
										break;
									}
									System.out.println("continue");
									continue;
								}
								PointND endpoint2 = shells.get(locs.get(pair.getFirst().getID()))
										.getOppositeOutside(endpoint1);

								System.out.println("nearest group endpoint 1: " + endpoint1.getID());
								System.out.println("nearest group endpoint 2: " + endpoint2.getID());

								int loc = endpoint1.getID();
								ArrayList<Segment> sfu = bucket.get(loc);
								knotEndpoints = flattenSegmentList(knotSegments);
								Segment sr = bucket.getNotInList(endpoint1, sfu, knotEndpoints);
								System.out.println(endpoint1 + "  " + sr);
								freePoint = null;
								freePointOther = null;
								freePointOtherMatch = null;
								// check if the segment matches
								if (bucket.checkMatch(endpoint1, sr)) {
									freePoint = endpoint2;
									freePointOther = endpoint1;
									freePointOtherMatch = sr.getOtherPoint(endpoint1);

									System.out.println("match" + sr);
								}

								loc = endpoint2.getID();
								sfu = bucket.get(loc);
								sr = bucket.getNotInList(endpoint2, sfu, knotEndpoints);
								System.out.println(endpoint2 + "  " + sr);
								if (bucket.checkMatch(endpoint2, sr)) {
									freePoint = endpoint1;
									freePointOther = endpoint2;
									freePointOtherMatch = sr.getOtherPoint(endpoint2);

									System.out.println("match" + sr);
								}
								if (freePoint == null) {
									continue;
								}
								System.out.println("freepoint: " + freePoint);
								// connect the freepoint to each knot endpoint,
								knotSolvePoint = null;
								knotSolveMinDist = java.lang.Double.MAX_VALUE;
								for (PointND knotConnectionPoint : knotEndpoints) {
									System.out.println(
											"+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
									System.out.println("endpoint11: " + knotConnectionPoint);
									// measure freepoint to endpoint11
									double freepEnd11length = knotConnectionPoint.distance(freePoint);
									// find what endpoint12 wants to connect to
									PointND endpoint12 = shells.get(locs.get(knotConnectionPoint.getID()))
											.getOppositeOutside(knotConnectionPoint);
									System.out.println("endpoint12: " + endpoint12);
									ArrayList<PointND> exclList = new ArrayList<PointND>();
									exclList.add(freePoint);
									exclList.add(freePointOther);
									exclList.add(knotConnectionPoint);
									exclList.add(freePointOtherMatch);
									Pair<PointND, ArrayList<Segment>> pair2 = bucket.checkMatchExcludeEndpoints(
											endpoint12,
											exclList,
											singletons);
									System.out.println("endpoint21: " + pair2.getFirst());
									System.out.println(pair2.getSecond());
									// measure endpoint12 to endpoint21
									PointND endpoint21 = pair2.getFirst();
									if (endpoint21 == null) {
										continue;
									}
									PointND endpoint22 = shells.get(locs.get(endpoint21.getID()))
											.getOppositeOutside(endpoint21);

									System.out.println("endpoint22: " + endpoint22);
									double end12End21length = endpoint12.distance(endpoint21);

									// find what endpoint 22 wants to connect to
									exclList.add(endpoint12);
									exclList.add(endpoint21);

									exclList.addAll(flattenSegmentList(matches));
									// check match exclude freepoint other and its match
									// if not match fuck off
									System.out.println("exclusion List " + exclList);
									Pair<PointND, ArrayList<Segment>> pair3 = bucket.checkMatchExcludeEndpoints(
											endpoint22,
											exclList,
											singletons);
									System.out.println(pair3.getFirst());
									if (pair3.getFirst() == null) {
										continue;
									}
									// measure endpoint22 to its match
									double end22matchLength = endpoint22.distance(pair3.getFirst());
									double addedDist = freepEnd11length + end12End21length + end22matchLength;
									if (addedDist < knotSolveMinDist) {
										knotSolveMinDist = addedDist;
										knotSolvePoint = knotConnectionPoint;
									}

									toAdd.addAll(knotSegments);
								}
							}

							if (knotSolvePoint != null) {
								skipFlag = true;
							}

							// compare valid scenarios length

							System.out.println(
									"--------------------------------------------------------------------------------------\n");
						}
					}
					if (singleseg != null) {
						System.out.println("singleton match: " + singleseg);
						// make the solve point and free point kiss :)
						mergeMatch(bucket, shells, locs, singletons, matches, singleseg);
					}

					if (out1 != null) {
						System.out.println("The truely based segment, God wills it: " + out1);
						mergeMatch(bucket, shells, locs, singletons, matches, out1);

						System.out.println("The truely based segment, God wills it: " + out2);
						mergeMatch(bucket, shells, locs, singletons, matches, out2);
					}

					if (knotSolvePoint != null) {
						System.out.println("The truely based point, God wills it: " + knotSolvePoint);
						// make the solve point and free point kiss :)
						Segment s1 = new Segment(freePoint, knotSolvePoint);
						mergeMatch(bucket, shells, locs, singletons, matches, s1);

						System.out.println("Joker point: " + freePointOther);
						Segment s2 = new Segment(freePointOther, freePointOtherMatch);
						mergeMatch(bucket, shells, locs, singletons, matches, s2);
					}
					for (Segment s : toRemove) {
						bucket.remove(s);
						matches.remove(s);
					}
					System.out.println("huge rreeee" + toAdd);
					// matches.addAll(toAdd);
					System.out.println("BIG REEE: " + matches);
				}
			} else {
				return shells.get(locs.get(0));
			}

			System.out.println(matches);
			System.out.println(bucket.getLeftovers(Segment.collectPoints(matches)));
			printShellsWithKeys(shells, locs);
			System.out.println("reeee");
			idx++;
			last = bucket.deepsize();
		}
		ArrayList<Shell> shellsLeft = getShellsWithKeys(shells, locs);
		ArrayList<Segment> mergeList = bucket.permute(shellsLeft);
		for (Segment segment : mergeList) {
			mergeMatch(bucket, shells, locs, singletons, matches, segment);
		}
		shellsLeft = getShellsWithKeys(shells, locs);

		return shellsLeft.get(0);

	}

	private void mergeMatch(Bucket bucket, HashMap<Integer, Integer> locs, ArrayList<PointND> singletons,
			ArrayList<Segment> matches, Segment s, int floc, Shell fShell, Shell lShell) {
		fShell.addAllAtSegment(s.first, s.last, lShell);
		for (PointND p : lShell) {
			locs.put(p.getID(), floc);
		}
		bucket.remove(s);
		bucket.removeAllInternal(fShell, matches);
		if (!fShell.isEndpoint(s.first)) {
			bucket.removeAll(s.first);
			matches.removeIf(n -> (n.contains(s.first)));
		}
		if (!fShell.isEndpoint(s.last)) {
			bucket.removeAll(s.last);
			matches.removeIf(n -> (n.contains(s.last)));
		}
		bucket.get(fShell.getFirst()).shell = fShell;
		bucket.get(fShell.getLast()).shell = fShell;
		singletons.remove(s.last);
		singletons.remove(s.first);
		System.out.println("MATCH!!: " + s + " skipFlag: " + " ");

	}

	private void mergeMatch(Bucket bucket, ArrayList<Shell> shells, HashMap<Integer, Integer> locs,
			ArrayList<PointND> singletons, ArrayList<Segment> matches, Segment singleseg) {
		int floc = locs.get(singleseg.first.getID());
		Shell fShell = shells.get(floc);
		Shell lShell = shells.get(locs.get(singleseg.last.getID()));
		fShell.addAllAtSegment(singleseg.first, singleseg.last, lShell);
		for (PointND p : lShell) {
			locs.put(p.getID(), floc);
		}
		bucket.remove(singleseg);
		bucket.removeAllInternal(fShell, matches);
		Segment s1 = new Segment(singleseg.first, singleseg.last);
		if (!fShell.isEndpoint(s1.first)) {
			bucket.removeAll(s1.first);
			matches.removeIf(n -> (n.contains(s1.first)));
		}
		if (!fShell.isEndpoint(s1.last)) {
			bucket.removeAll(s1.last);
			matches.removeIf(n -> (n.contains(s1.last)));
		}
		bucket.get(fShell.getFirst()).shell = fShell;
		bucket.get(fShell.getLast()).shell = fShell;
		singletons.remove(singleseg.last);
		singletons.remove(singleseg.first);
		System.out.println("MATCH!!: " + singleseg + " ");
		System.out.println(fShell);
	}

	private void printShellsWithKeys(ArrayList<Shell> shells, HashMap<Integer, Integer> locs) {
		Set<Integer> set = new HashSet<Integer>();
		System.out.println("Shells {------------------------------------- ");
		set.addAll(locs.values());
		for (Integer i : set) {
			System.out.println(shells.get(i));
		}
		System.out.println("}---------------------------------------------");
	}

	private ArrayList<Shell> getShellsWithKeys(ArrayList<Shell> shells, HashMap<Integer, Integer> locs) {
		Set<Integer> set = new HashSet<Integer>();
		ArrayList<Shell> retVal = new ArrayList<Shell>();
		set.addAll(locs.values());
		for (Integer i : set) {
			retVal.add(shells.get(i));
		}
		return retVal;
	}

	public ArrayList<Segment> getKnownPairs(ArrayList<PointDistanceWrapper<Segment>> segments) {
		ArrayList<Segment> result = new ArrayList<Segment>();
		ArrayList<PointND> inserted = new ArrayList<PointND>();
		for (int i = 0; i < segments.size(); i++) {
			Segment s = segments.get(i).s;
			if (!inserted.contains(s.first) && !inserted.contains(s.last)) {
				inserted.add(s.first);
				inserted.add(s.last);
				result.add(s);
				break;
			}
		}
		result.remove(result.size() - 1);
		return result;
	}

	public ArrayList<PointND> flattenSegmentList(ArrayList<Segment> segments) {
		ArrayList<PointND> result = new ArrayList<PointND>();
		for (Segment s : segments) {
			if (!result.contains(s.first)) {
				result.add(s.first);
			}
			if (!result.contains(s.last)) {
				result.add(s.last);
			}
		}
		return result;
	}

	/**
	 * Does the 2d gift-wrapping/javis march algorithm to find the convex hull of a
	 * set of points and add those points
	 * 
	 * @param ps
	 * @param d
	 * @returnthe convex hull of the set of points in 2 dimensions
	 */
	public Shell minimizeVarianceOfSphere(Shell shell, PointSet allPoints, DistanceMatrix d) {

		double varience = shell.getVarienceOfSphere(allPoints, d);
		boolean breakFlag = false;
		while (!breakFlag) {

			double minV = varience;
			PointND pointToAdd = null;
			int loc = -1;
			boolean remove = false;
			boolean swap = false;

			for (PointND p : allPoints) {
				if (!shell.contains(p)) {
					for (int i = 0; i <= shell.size(); i++) {
						shell.add(i, p);
						double newV = shell.getVarienceOfSphere(allPoints, d);
						if (newV < minV) {
							pointToAdd = p;
							minV = newV;
							loc = i;
						}
						shell.remove(i);
					}
				}
			}
			for (int i = 0; i < shell.size(); i++) {
				PointND removed = shell.remove(i);
				double newV = shell.getVarienceOfSphere(allPoints, d);
				if (newV < minV) {
					pointToAdd = removed;
					remove = true;
					minV = newV;
					loc = i;
				}
				shell.add(i, removed);
			}
			/*
			 * for (PointND p : allPoints) {
			 * if(!shell.contains(p)) {
			 * for(int i = 0; i < shell.size(); i++) {
			 * PointND removed = shell.remove(i);
			 * shell.add(i, p);
			 * double newV = shell.getVarienceOfSphere(allPoints, d);
			 * if(newV < minV) {
			 * pointToAdd = p;
			 * swap = true;
			 * minV = newV;
			 * loc = i;
			 * }
			 * shell.remove(i);
			 * shell.add(i, removed);
			 * }
			 * }
			 * }
			 */
			if (pointToAdd != null) {
				if (swap) {
					shell.remove(loc);
					shell.add(loc, pointToAdd);
				}
				if (remove) {
					shell.remove(loc);
				} else {
					shell.add(loc, pointToAdd);
				}
				varience = minV;
			} else {
				break;
			}
		}
		return shell;
	}

	public Shell removeNonMinimal(Shell shell, PointSet allPoints, DistanceMatrix d) {

		double varience = shell.getVarienceOfSphere(allPoints, d);
		boolean breakFlag = false;
		while (!breakFlag) {

			double minV = varience;
			PointND pointToAdd = null;
			int loc = -1;
			boolean remove = false;
			boolean swap = false;

			for (int i = 0; i < shell.size(); i++) {
				PointND removed = shell.remove(i);
				double newV = shell.getVarienceOfSphere(allPoints, d);
				if (newV < minV) {
					pointToAdd = removed;
					remove = true;
					minV = newV;
					loc = i;
				}
				shell.add(i, removed);
			}
			if (pointToAdd != null) {
				if (remove) {
					shell.remove(loc);
				}
				varience = minV;
			} else {
				break;
			}
		}
		return shell;
	}

	/**
	 * Finds the anoid of the pointset ps
	 * 
	 * @param ps
	 * @param centroid
	 * @return the anoid
	 */
	public static PointND findAnoid(PointSet ps, PointND centroid, DistanceMatrix d) {
		double maxDist = -1;
		PointND anoid = null;

		for (PointND p : ps) {
			double dist = d.getDistance(p, centroid);
			if (dist > maxDist) {
				maxDist = dist;
				anoid = p;
			}
		}
		return anoid;
	}

	@Override
	public String toString() {
		String str = "PointSet[";
		for (int i = 0; i < this.size(); i++) {
			if (this.get(i).getID() != -1) {
				str += this.get(i).getID();
			} else {
				str += this.get(i).toString();
			}
			if (i < this.size() - 1) {
				str += ", ";
			}
		}

		str += "]";

		return str;
	}

	@Override
	public boolean add(PointND e) {
		if (!this.contains(e)) {
			super.add(e);
			return true;
		}
		return false;

	}

	@Override
	public boolean addAll(Collection<? extends PointND> c) {
		for (PointND p : c) {
			assert (!this.contains(p));
		}
		super.addAll(c);
		return true;
	}

	public String toStringCoords() {
		String str = "PointSet[";
		for (int i = 0; i < this.size(); i++) {

			str += this.get(i).toString();
			if (i < this.size() - 1) {
				str += ", \n";
			}
		}

		str += "]";

		return str;
	}

	public int getMaxDim() {
		int max = 0;
		for (PointND p : this) {
			if (p.getDim() > max) {
				max = p.getDim();
			}
		}
		return max;
	}

}
