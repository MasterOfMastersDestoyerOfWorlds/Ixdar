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
			//REEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE
			
			//https://en.wikipedia.org/wiki/Isoperimetric_inequality#In_Rn
			//https://en.wikipedia.org/wiki/Mean_squared_displacement
			//maybe we want to take the mean squared distnce of every angle to pi
			//https://link.springer.com/article/10.1007/s10851-015-0618-4
			//https://en.wikipedia.org/wiki/Gamma_function
			//Gamma(n/2 + 1)/((n+2)*pi/N)^(N/2) * lambda_N(S)/trace(sigma)^(n/2)
			//mu is the mean of S
			// sigma = 1/N  * sum((x_i - mu)(x_i-mu)^T)
			//lambda_N(s) = Lebesgue measure (n-d volume) lie=nes do not have volume in 
			// nd space so it seems like this method will not work
			//https://stackoverflow.com/questions/65185721/fitting-a-sphere-to-3d-points
			//fit a sphere to the points and look att the error
			//distance to the surface of the sphere is the abs(distancee to the centroid - radius)
			//https://jekel.me/2015/Least-Squares-Sphere-Fit/
			//https://web.mat.upc.edu/sebastia.xambo/santalo2016/pdf/LD/LD4.pdf
			//https://commons.apache.org/proper/commons-math/userguide/leastsquares.html
			//alternativeley find the centroid, average the distance to the centroid and then find the error in the distances
			//multiply the error by the length of the segments like they are also points, but you'd neeed to be able to integrate over that line in the distance to the edge
			//you can think of each segmetn as a triangle starting from the centroid and going to the bouding circle cut by the segment. get the angle of that triangle and convert that to area of a circle and then subtract out the area of the triangle
			double max = java.lang.Double.MAX_VALUE;
			//PointND minp = null;
			PointND centroid = d1.findCentroid();
				//maybbe instead of using giftwrapping algo, buuild path based on varience of sphere to begin with
				//start with the segment (n^2 op) that has the leeast varience when compared to the centroid for the whole set
				//Shell temphull = findMaxAngleMinDistancePaths(copy, d1, p, centroid);
				Shell temphull = copy.findMinVariencePath(copy, d1);
				//temphull = copy.minimizeVarianceOfSphere(temphull, copy, d1);
				System.out.println("reee: " + " " + temphull.getVarienceOfSphere(copy, d1) + " " + temphull.toString());
				if(temphull.getVarienceOfSphere(copy, d1) < max) {
					max = temphull.getVarienceOfSphere(copy, d1);
					hull = temphull;
					//minp = p;
				}
			
			System.out.println("++++++++++ "  + "  " + max + " " + hull);
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
		
		//might want to maximize this number
		System.out.println(rootShell.getLengthRecursive());

		rootShell.updateOrder();
		assert (rootShell.sizeRecursive() == this.size())
				: "Found size: " + rootShell.sizeRecursive() + " Expected size: " + this.size();

		return rootShell;
	}
	
	public PointSet getAllDummyNodesAndParents() {
		PointSet result = new PointSet();
		for(PointND pt: this) {
			if(pt.isDummyNode()) {
				result.add(pt);
				result.add(pt.getDummyParents().first);
				result.add(pt.getDummyParents().last);
			}
		}
		return result;
	}
	public double SumAnglesToPoint(PointND p, DistanceMatrix d) {
		double sum = 0.0;
		for(PointND pt: this) {
			for(PointND pt2: this) {
				if(!pt.equals(pt2) && !pt.equals(p) && !pt2.equals(p)) {
					sum += Vectors.findAngleSegments(pt2, pt, p, d);
				}
			}
		}
		return sum;
	}
	public double SumDistancesToPoint(PointND p, DistanceMatrix d) {
		double sum = 0.0;
		for(PointND pt: this) {
			if(!pt.equals(p)) {
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
	public Shell findMaxAngleMinDistancePaths(PointSet ps, DistanceMatrix d, PointND lastRight, PointND behindLastRight) {
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
	 * IDEA: make a 2d array of all segment variences, use dijkstras to connect the anode 
	 * and its anode remove those points, do it again now you have a loop
	 * 
	 * stupid no garuntee that it surround the centroid
	 * 
	 * IDEA: go in order of distance to the centroid and find the minimum varience 
	 * place to  put it in, continue till there is no place that will decrease the 
	 * varience of the subset
	 * 
	 * will work in 2d dubious in higher dim
	 * 
	 * IDEA: start with the set of all segments, throw away the largest varience segment until
	 * the varience of the subset does not decrease
	 * 
	 * IDEA:each vertex is like an umbrella,precompute the two smallest varience segments, 
	 * start with two smallest varience sgements idk what next this is just greedy algo
	 * 
	 * get the set of segments tighten the restriction on varience until the only segments
	 *  left form a single loop and are <= n can sort by segment varience 
	 * 
	 * 
	 * @param ps
	 * @param d
	 * @returnthe convex hull of the set of points in 2 dimensions
	 */
	public Shell findMinVariencePath(PointSet ps, DistanceMatrix d) {
		Shell shell = new Shell();
		//PointND centroid = d.findNSphereCenter();
		//double radius = d.getnSphereRadius();
		
		shell.addAll(ps);
		System.out.println(ps);
		if(shell.size() <= 2) {
			return shell;
		}
		
		ArrayList<PointDistanceWrapper<Segment>> segments = new ArrayList<PointDistanceWrapper<Segment>>();
		PointSet centroids = new PointSet();
		PointSet nd = d.toPointSet();

		//centroids.addAll(nd);
		//centroids.add(nd.get(0));
		//HashMap<Segment, PointND> midpoints = d.findMidPoints(nd);
		//centroids.add(midpointCenter);
		
		//centroids.add(d.findCentroid(nd));
		centroids.add(d.findNSphereCenter(nd));
		
		
		for(int k = 0; k < centroids.size(); k ++) {
			PointND centroid = centroids.get(k);
			double radius = d.getDistance(findAnoid(nd, centroid, d),centroid);
			
			for (int i = 0; i < ps.size(); i++) {
				for (int j = 0; j < ps.size(); j++) {
					if(i!=j) {
						PointND one = ps.get(i);
						PointND two = ps.get(j);
						Segment s = new Segment(one, two);
						
						if(!(one.equals(centroid) || two.equals(centroid))){
							double var = Vectors.getDifferenceToSphere(one, two, centroid, radius, d);
							//double var = d.getDistance(s.first, s.last);
							// need to actually get distance to sphere
							//double var = Vectors.getDifferenceToSphereFromMidpoint(midpoints.get(s), centroid, radius);
							PointDistanceWrapper<Segment> p = 
									new PointDistanceWrapper<Segment>(var, s);
							if(!segments.contains(p)){
								segments.add(p);	
							}else {
								int idx = segments.indexOf(p);
								segments.get(idx).distance = var;
							}
						}
					}
				}
			}
		}
		segments.sort(null);
		Bucket bucket = new Bucket();
		for(int i = 0; i< segments.size(); i++ ) {
			bucket.add(segments.get(i).s);
		}
		System.out.println(bucket);
		System.out.println(segments);
		ArrayList<Segment> matches = bucket.getMatches(); 
		System.out.println();
		
		//Could we possiby reformat these segments as points and then iterate until there are no points left
		//or is therrer some more complicated way of choosing
		//first go through and find all of the pairs and then tack on the leftovers to wherever
		//Needs to be a full statement of this its gonna go between. This and this otherwise delete segment its a loop, using bucket system
		ArrayList<Shell> shells = new ArrayList<Shell>(); 
		HashMap<Integer, Integer> locs = new HashMap<Integer, Integer>();
		ArrayList<PointND> inserted = new ArrayList<PointND>();
		int idx = 0;
		while (idx < 4) {
			for(int i = 0; i < matches.size(); i++){
				Segment s = matches.get(i);
				if(!inserted.contains(s.first) && !inserted.contains(s.last)) {
					Shell nShell = new Shell();
					nShell.add(s.first);
					nShell.add(s.last);
					shells.add(nShell);
					locs.put(s.first.getID(), shells.size() - 1);
					locs.put(s.last.getID(), shells.size() - 1);
					bucket.remove(s);
					inserted.add(s.first);
					inserted.add(s.last);
				}else if (inserted.contains(s.first) || inserted.contains(s.last)){
					// steps 
					// check if the other side of the shell is  also in the match list
					
					//repeat for other side of segment
					//if its not in the match list hold off for now
					int floc = locs.get(s.first.getID());
					Shell fShell = shells.get(floc);
					PointND fOpp = fShell.getOppositeOutside(s.first);
					System.out.println("f "+ s.first.getID() + " fOpp " + fOpp.getID() + " first shell " + fShell + " first in bucket " + bucket.get(fOpp.getID()).get(0));
					int lloc = locs.get(s.last.getID());
					Shell lShell = shells.get(lloc);
					PointND lOpp =lShell.getOppositeOutside(s.last);
					System.out.println("l "+ s.last.getID() + " lOpp " + lOpp.getID() + "last shell " + lShell + " first in bucket " + bucket.get(lOpp.getID()).get(0));
					ArrayList<PointND> endpoints = new ArrayList<PointND>();
					endpoints.add(s.first);
					endpoints.add(s.last);
					endpoints.add(lOpp);
					if(bucket.checkMatchExcludeEndpoints(fOpp, endpoints)){
						endpoints.remove(lOpp);
						endpoints.add(fOpp);
						if(bucket.checkMatchExcludeEndpoints(lOpp, endpoints)){
							
							fShell.addAllAtSegment(s.first, s.last, lShell);
							for(PointND p: lShell) {
								locs.put(p.getID(), floc);
							}
							bucket.removeAll(s);
							System.out.println("MATCH!!");
							System.out.println(fShell);
						}
					}
					System.out.println();
				}
			}
			matches = bucket.getMatches(); 
			System.out.println(matches);
			System.out.println(shells);
			System.out.println(bucket);
			idx ++;
		}
		int fun =1/0;
		return null;

	}

	public ArrayList<Segment> getKnownPairs(ArrayList<PointDistanceWrapper<Segment>> segments){
		ArrayList<Segment> result = new ArrayList<Segment>();
		ArrayList<PointND> inserted = new ArrayList<PointND>();
		for (int i = 0; i < segments.size(); i++) {
			Segment s = segments.get(i).s;
			if(!inserted.contains(s.first) && !inserted.contains(s.last)) {
				inserted.add(s.first);
				inserted.add(s.last);
				result.add(s);
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
		boolean  breakFlag = false;
		while (!breakFlag) {

			double minV = varience;
			PointND pointToAdd = null;
			int loc = -1;
			boolean remove = false;
			boolean swap = false;
			
			for (PointND p : allPoints) {
				if(!shell.contains(p)) {
					for(int i = 0; i <= shell.size(); i++) {
						shell.add(i, p);
						double newV = shell.getVarienceOfSphere(allPoints, d);
						if(newV < minV) {
							pointToAdd = p;
							minV = newV;
							loc = i;
						}
						shell.remove(i);
					}
				}
			}
			for(int i = 0; i < shell.size(); i++) {
					PointND removed = shell.remove(i);
					double newV = shell.getVarienceOfSphere(allPoints, d);
					if(newV < minV) {
						pointToAdd = removed;
						remove = true;
						minV = newV;
						loc = i;
					}
					shell.add(i, removed);
			}
			/*for (PointND p : allPoints) {
				if(!shell.contains(p)) {
					for(int i = 0; i < shell.size(); i++) {
						PointND removed = shell.remove(i);
						shell.add(i, p);
						double newV = shell.getVarienceOfSphere(allPoints, d);
						if(newV < minV) {
							pointToAdd = p;
							swap = true;
							minV = newV;
							loc = i;
						}
						shell.remove(i);
						shell.add(i, removed);
					}
				}
			}*/
			if(pointToAdd != null) {
				if(swap) {
					shell.remove(loc);
					shell.add(loc, pointToAdd);
				}
				if(remove) {
					shell.remove(loc);
				}
				else {
					shell.add(loc, pointToAdd);
				}
				varience = minV;
			}
			else {
				break;
			}
		}
		return shell;
	}
	
	public Shell removeNonMinimal(Shell shell, PointSet allPoints, DistanceMatrix d) {

		double varience = shell.getVarienceOfSphere(allPoints, d);
		boolean  breakFlag = false;
		while (!breakFlag) {

			double minV = varience;
			PointND pointToAdd = null;
			int loc = -1;
			boolean remove = false;
			boolean swap = false;
			
			for(int i = 0; i < shell.size(); i++) {
					PointND removed = shell.remove(i);
					double newV = shell.getVarienceOfSphere(allPoints, d);
					if(newV < minV) {
						pointToAdd = removed;
						remove = true;
						minV = newV;
						loc = i;
					}
					shell.add(i, removed);
			}
			if(pointToAdd != null) {
				if(remove) {
					shell.remove(loc);
				}
				varience = minV;
			}
			else {
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
		if(!this.contains(e)) {
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
