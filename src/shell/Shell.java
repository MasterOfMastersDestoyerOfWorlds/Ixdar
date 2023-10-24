package shell;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Random;

import javax.swing.JComponent;

import shell.PointND.Double;

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

	/**
	 * Initializes a new shell with no parent or child; a blank slate
	 * @param points
	 */
	public Shell(Double... points) {
		this.updateOrder();
		if (!this.isMaximal()) {
			parent.updateOrder();
		}
		for(int i = 0; i < points.length; i ++) {
			this.add(points[i]);
		}
	}

	public Shell(PointND... points) {
		this.updateOrder();
		if (!this.isMaximal()) {
			parent.updateOrder();
		}
		for(int i = 0; i < points.length; i ++) {
			this.add(points[i]);
		}
	}

	/**
	 * Initializes a new shell with
	 * 
	 * @param parent
	 * @param child
	 */
	public Shell(Shell parent, Shell child) {
		this.parent = parent;
		this.child = child;
		this.updateOrder();
		if (!this.isMaximal()) {
			parent.updateOrder();
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
	
	public PointND greedyAdd(PointND p, PointND anchor, DistanceMatrix d) {
		PointND p1 = this.nextPoint(anchor);
		double dist1 = d.getDistance(anchor, p) + d.getDistance(p1, p) - d.getDistance(anchor, p1);
		PointND p2 = this.prevPoint(anchor);
		double dist2 = d.getDistance(anchor, p) + d.getDistance(p2, p)  - d.getDistance(anchor, p2);

		if(dist1 < dist2) {
			this.addAfter(anchor, p);
			return p1;
		}else {
			this.addAfter(p2, p);
			return p2;
		}
	}
	
	/**
	 * Get the length of the shell
	 * @param anoid 
	 * 
	 * @return the length of the path between all points in the shell
	 */
	//you can think of each segmetn as a triangle starting from the centroid and going to
	//the bounding circle cut by the segment. get the angle of that triangle and convert 
	//that to area of a circle and then subtract out the area of the triangle

	public double getVarienceOfSphere(PointSet allPoints,  DistanceMatrix d) {
		PointND centroid = d.findCentroid();
		PointND anoid = PointSet.findAnoid(allPoints, centroid, d);
		double radius = d.getDistance(centroid, anoid);
		if(this.size() <= 2) {
			return Math.PI*Math.pow(radius, 2);
		}
		PointSet ps = this.copyShallow().toPointSet();
		DistanceMatrix d1 = new DistanceMatrix(ps, d);
		centroid = d1.findCentroid();
		double differenceSum = 0.0;
		for(PointND p : ps) {
			PointND next = this.nextPoint(p);
			differenceSum += Vectors.getDifferenceToSphere(p, next, centroid, radius, d1); 
		}
		//assert(Math.abs(sumAngle - Math.PI*2) < 0.1) : sumAngle;
		//assert(Math.abs(sumArea - Math.PI*Math.pow(radius, 2)) < 0.1) : sumArea + " "+ Math.PI*Math.pow(radius, 2);
		return differenceSum;

	}
	
	/**
	 * Get the length of the shell
	 * 
	 * @return the length of the path between all points in the shell
	 */
	public double getLengthRecursive() {
		Shell currShell = this;
		double length = currShell.getLength();
		while(currShell.getChild() != null) {
			currShell = currShell.getChild();
			length += currShell.getLength();
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
		if (!this.isMinimal() && drawChildren) {
			child.drawShell(frame, g2, drawChildren, c, ps);
		}
	}

	/**
	 * Finds the minimal shell of the pointset
	 * 
	 * @return the central most shell that does not have any children
	 */
	public Shell getMinimalShell() {
		if (this.isMinimal()) {
			return this;
		} else {
			return child.getMinimalShell();
		}
	}

	/**
	 * Determines whether the current shell is the outermost shell
	 * 
	 * @return true if current shell is the outermost shell otherwise false
	 */
	public boolean isMaximal() {
		return parent == null;
	}

	/**
	 * Determines whether the current shell is the innermost shell
	 * 
	 * @return true if current shell is the innermost shell otherwise false
	 */
	public boolean isMinimal() {
		return child == null || child.size() == 0;
	}

	/**
	 * Gets the child shell of the current shell
	 * 
	 * @return the shell immediately inside of the current shell
	 */
	public Shell getChild() {
		return child;
	}

	/**
	 * Updates the order of the current shell to reflect how many shells are inside
	 * of it
	 * 
	 * @return the number of shells inside the current shell + 1
	 */
	public int updateOrder() {
		if (!this.isMinimal()) {
			this.ORDER = child.updateOrder() + 1;
		} else {
			this.ORDER = 1;
		}
		return this.ORDER;
	}

	/**
	 * Updates what shell is considered the child of this shell
	 * 
	 * @param child new child shell of current shell
	 */
	public void setChild(Shell child) {
		this.child = child;
		minimal = false;
		this.updateOrder();
		if (parent != null) {
			parent.updateOrder();
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

		return d.getDistance(p,prevP) + d.getDistance(p,nextP);

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

		return d.getDistance(nextP,prevP);

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



	/**
	 * Gives the shell, the barrier shell n levels below, and the first shell after
	 * the barrier
	 * 
	 * @param firstN number of shells to split after
	 * @return an arraylist where index 0 is this, index 1 is the shell n levels
	 *         below this, and index 2 is the child of index 1
	 */
	public ArrayList<Shell> split(int firstN) {

		Shell A = this.copyRecursive();

		Shell B = null, C = null, curr = A;

		for (int i = 0; i < firstN - 1; i++) {
			curr = curr.getChild();
		}
		B = curr.getChild().copyShallow();

		C = curr.getChild().getChild().copyRecursive();

		curr.child = null;

		ArrayList<Shell> result = new ArrayList<Shell>();
		result.add(A);
		result.add(B);
		result.add(C);

		return result;

	}

	/**
	 * Finds and removes the innermost shell
	 * 
	 * @return an arraylist where index 0 is the new innermost shell and index 1 is
	 *         the old innermost shell
	 */
	public ArrayList<Shell> popMin() {

		Shell A = this.copyRecursive();

		Shell B = null, curr = A;

		int order = A.updateOrder();
		for (int i = 0; i < order - 2; i++) {
			curr = curr.getChild();
		}
		B = curr.getChild().copyShallow();
		curr.child = null;

		ArrayList<Shell> result = new ArrayList<Shell>();
		result.add(A);
		result.add(B);

		return result;

	}
	public PointSet getAllDummyNodes() {
		PointSet result = new PointSet();
		for(PointND pt: this) {
			if(pt.isDummyNode()) {
				result.add(pt);
			}
		}
		return result;
	}

	/**
	 * Collapses all shells into one shell that is the tsp path
	 * 
	 * @return one shell that represents the optimal tsp path
	 */
	public Shell collapseAllShells(DistanceMatrix d) {


		int order = this.updateOrder();
		if (this.isMinimal()) {
			return this;
		}
		int size = this.sizeRecursive();
		Shell A = this.copyRecursive();
		Shell B = this.getChild().copyRecursive();
		
		System.out.println("Collapsing: " + this.toStringRecursive());
		
		Shell collapsed = collapseReduce(A, B, d);
		System.out.println("Collapsed: " + collapsed);
		//System.out.println(this.toStringRecursive());
		//System.out.println(collapsed.toStringRecursive());
		assert(collapsed.sizeRecursive() == size) : "Shell was size: " + collapsed.sizeRecursive() + " Supposed to be size: " + size;
		Shell consensus = consensus(collapsed,B, d);
		System.out.println("Consensus: " + consensus);
		//System.out.println("DM: " + d);
		
		
//		System.out.println("start : " + this.toStringRecursive());
//		System.out.println("colapse : " + collapsed.toStringRecursive());
//		System.out.println("rreturn : " + consensus.toStringRecursive());
		assert(consensus.sizeRecursive() == size) : "Shell was size: " + consensus.sizeRecursive() + " Supposed to be size: " + size;
		//assert(consensus.getLength() <= collapsed.getLength() || consensus.getLength()- collapsed.getLength() < 0.001) :" collapsed: " + collapsed.getLength() + " " + collapsed + "\n consensus: " + consensus.getLength() + " "+ consensus + "\n B: " + B ;
		return consensus.collapseAllShells(d);

	}

	/**
	 * Collapses shell B onto shell A and reduces the tsp path
	 * 
	 * @param A
	 * @param B the child shell of A
	 * @param d 
	 * @param maxDist 
	 * @return one shell that represents the optimal tsp path for all points in
	 *         shells A and B
	 */
	public static Shell collapseReduce(Shell A, Shell B, DistanceMatrix d) {

		Shell result = A.copyRecursive();
		Shell copy = B.copyRecursive();

		boolean notConfirmed = true;
		

		// once there is no change to result then the loop will exit
		// this will only happen once all points from copy are in result
		// and all points in result cannot be rearranged to form a shorter path

		int idx =0;
		while (notConfirmed) {

			PointND pointChosen = null, lastpc = null, currpc = null;
			int chosenParent = 0;
			boolean first = true, changed = false;
			PointND lastPoint = null, currPoint = null;
			double minDist = java.lang.Double.MAX_VALUE;
			for (int i = 0; i < result.size(); i++) {
				lastPoint = currPoint;
				currPoint = result.get(i);
				if (first) {
					lastPoint = result.getLast();
					first = false;
				}
				//assert(!lastPoint.equals(currPoint));
				for (PointND q : copy) {
					double dist = Vectors.distanceChanged(lastPoint, currPoint, q, d);
					// store which point in b fits best between the two current points in result
					if (dist < minDist) {
						minDist = dist;
						pointChosen = q;
						chosenParent = i;
						changed = true;
					}
				}
				for (PointND p : result) {
					//PointND next = nextPoint(p), prev = prevPoint(p);
					
					if (!currPoint.equals(p) && !lastPoint.equals(p)) {
						
						double distanceChanged = java.lang.Double.MAX_VALUE;

						
						distanceChanged = Vectors.distanceChanged(lastPoint, currPoint, p,d)
								+ ( result.distanceBetweenNeighbors(p,d) -result.distanceToNeighbors(p,d)); // why this
																										// second line
						
						// store which point if any already in result fits better in between curr and
						// last points
						// instead of where it currently is
						if (distanceChanged < minDist && distanceChanged < -0) {
							lastpc = lastPoint;
							currpc = currPoint;
							minDist = distanceChanged;
							pointChosen = p;
							chosenParent = i;
							changed = true;
						}
					}
				}

			}
			// update result to add the closest point from B or to reduce result into a
			// better tsp path
			if (changed) {
				double ol = result.getLength();


				result.remove(pointChosen);

				//PointND parent = result.get(chosenParent);
				result.add(chosenParent, pointChosen);
				copy.remove(pointChosen);
				idx++;
				/*System.out.println("*******");
				System.out.println(parent.getID());
				System.out.println(pointChosen.getID());
				System.out.println(result.distanceToNeighbors(pointChosen,d)- result.distanceBetweenNeighbors(pointChosen,d));
				System.out.println();
				
				System.out.println(result);
				System.out.println(d.getDistance(parent, pointChosen));
				System.out.println(minDist);*/
				
			}

			notConfirmed = changed;
		}
		result.child = copy.child;
//		System.out.println("--------");
//		System.out.println(d);
		return result;
	}
	
	/**
	 * Checks if shell A is greedily reduced
	 * 
	 * @param A
	 * @param d 
	 * @return is A in reduced form
	 */
	public static boolean isReduced(Shell A, DistanceMatrix d) {
		Shell result = A.copyShallow();
		boolean notConfirmed = true;

		// loops through every segment on the path and every point to see if there are any greedy replacements.

		boolean first = true;
		PointND lastPoint = null, currPoint = null;
		double minDist = java.lang.Double.MAX_VALUE;

		for (int i = 0; i < result.size(); i++) {
			lastPoint = currPoint;
			currPoint = result.get(i);
			if (first) {
				lastPoint = result.getLast();
				first = false;
			}
			for (PointND p : result) {
				if (!currPoint.equals(p) && !lastPoint.equals(p)) {
					double distanceChanged = java.lang.Double.MAX_VALUE;

					
					distanceChanged = Vectors.distanceChanged(lastPoint, currPoint, p, d)
							+ (result.distanceBetweenNeighbors(p, d) - result.distanceToNeighbors(p, d)); // why this
																									// second line
					
					// store which point if any already in result fits better in between curr and
					// last points
					// instead of where it currently is
					if (distanceChanged < minDist && distanceChanged < 0) {
						return false;
					}
				}
			}

		}

		return true;
	}

	/**
	 * currently causes an infinite loop idk why lol
	 * 
	 * I think the best way forward is to develop some unit tests from the inputs and outputs of the original method
	 * and then debug to see if we get the same results
	 * 
	 * @param s
	 * @param A
	 * @param B
	 * @return
	 */
	public static Shell solveBetweenEndpoints(Segment s, Shell A, Shell B, DistanceMatrix d) {
		PointSet ps = new PointSet();
		
		
		assert(!s.first.equals(s.last));

		ps.add(s.first);
		if(!s.first.equals(s.last)) {
			ps.add(s.last);
		}
		ps.addAll(A);
		ps.addAll(B);
		DistanceMatrix d1 = new DistanceMatrix(ps, d);
		PointND dummy = d1.addDummyNode(s);
		ps.add(dummy);
		Shell answer = new Shell();
		answer.add(s.first);
		answer.addAll(A.copyShallow());
		answer.add(s.last);
		answer.add(dummy);
		Shell result = ps.toShells(d1);

		System.out.println("answer: " +answer + " varience: " + answer.getVarienceOfSphere(ps, d1));
		
		assert(d1.getZero() != 0);
		
		
		
		//assert(isReduced(result, d1));
		assert(d1.getMaxDist()/2 <= d1.getZero()): "Zero: "+ d1.getZero() + " MaxDist: " + d1.getMaxDist();
		//assert(result.contains(dummy)): "Expected " + dummy.getID() + " to be in top layer of:\n" + result.toStringRecursive();
		//assert(result.contains(s.first)) : "Expected " + s.first.getID() + " to be in top layer of:\n" + result.toStringRecursive();
		//assert(result.contains(s.last)) : "Expected " + s.last.getID() + " to be in top layer of:\n" + result.toStringRecursive();
		//assert(result.contains(dummy)) : result.toStringRecursive();
		assert(result.sizeRecursive() == ps.size() ) : "Size was " + result.sizeRecursive() + " Expected: " + ps.size();

		result = result.collapseAllShells(d1);
		
		assert(result.sizeRecursive() == ps.size() ) : "Size was " + result.sizeRecursive() + " Should have been " + ps.size();
		System.out.println(result);
		System.out.println(result.getVarienceOfSphere(ps, d1));
		ps.remove(dummy);
		result = result.removeRotate(ps);
		if(!result.get(0).equals(s.first)) {
			result = result.reverse();
		}
		///System.out.println(d1);
		
		assert((result.get(0).equals(s.first) && result.get(result.size() -1).equals(s.last))):
			"first: "+s.first.getID() + " last: "  + s.last.getID() + " dummy: " + dummy.getID() + "\n" + result.toStringRecursive();
		return result;

	}
	
	public static Shell replaceByID(Shell A, PointSet ps) {
		Shell result = new Shell();
		for(PointND p: A) {
			result.add(ps.getByID(p.getID()));
		}
		// TODO Auto-generated method stub
		return result;
	}

	public Shell removeRotate(PointSet ps) {
		
		Shell before = new Shell(), after = new Shell();

		//find the dummy node and take it out of the Shell unwrapping at the dummy.
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
		//reverse the set if need be to match the input segment s
		after.addAll(before);
		
		
		assert(after.size() == this.size()-1);
		
		return after;
	}
	

	public void rotateTo(PointND p1, PointND p2) {
		Shell before = new Shell(), after = new Shell();

		//find the dummy node and take it out of the Shell unwrapping at the dummy.
		boolean isBeforePoint = true;
		for (PointND p : this) {
			if ((p.equals(p1) && this.nextPoint(p).equals(p2))|| (p.equals(p2) && this.nextPoint(p).equals(p1)) ) {
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


	
	public static Shell consensus(Shell AB, Shell B, DistanceMatrix d) {

		AB = AB.copyRecursive();
		B = B.copyRecursive();
		
		
		ArrayList<Segment> ABKeys = new ArrayList<Segment>();


		HashMap<Segment, Shell> ABsections = AB.splitBy(B, ABKeys);
		
		
		System.out.println(AB);
		System.out.println(ABsections);
		Shell result = new Shell(null, B.child);
		System.out.println(numBuckets(ABsections, ABKeys));
		
		//checks that there is more that one unsorted segment 
		if(numBuckets(ABsections, ABKeys) > 1) {
			for (Segment s : ABKeys) {
				Shell line = null;
				line = solveBetweenEndpoints(s, ABsections.get(s), new Shell(), d);
				//need to pack and unpack dummy point as single entity? no needs to work independent of knowledge of endpoints
				if(!s.first.equals(line.get(0))) {
					line = line.reverse();

					assert(s.first.equals(line.get(0)));
					assert(s.last.equals(line.getLast()));
				}
				line.remove(s.first);

				result.addAll(line);
				//maybe also check that each segment shouldnt be flipped
			}
		}
		else {
			result = AB;
		}
		result.setChild(AB.child);
		return result;

	}
	private static int numBuckets(HashMap<Segment, Shell> ABsections, ArrayList<Segment> ABKeys) {
		int count = 0;
		for (Segment s : ABKeys) {
			if(ABsections.get(s).size() > 1) {
				count++;
			}
		}
		
		return count;
		
	}

	/**
	 * Finds all points in common between the shells AB and BC
	 * 
	 * @param AB
	 * @param BC
	 * @return a shell that has all of the points in common between AB and BC
	 */
	private static Shell pointsInCommon(Shell AB, Shell BC) {

		Shell result = new Shell();

		for (PointND p : AB) {
			if (BC.contains(p)) {
				result.add(p);
			}
		}
		return result;
	}

	/**
	 * Recursively copies a shell so that all of its children appear in the copy
	 * 
	 * @return a shell that represents a complete copy of the current shell
	 */
	public Shell copyRecursive() {
		Shell copy = null;
		if (!isMinimal()) {
			copy = new Shell(this.parent, this.child.copyRecursive()); // is parent shallow copied here could that cause
																		// problems
		} else {
			copy = new Shell(this.parent, null);
		}
		for (PointND q : this) {
			copy.add(q);
		}
		return copy;
	}

	/**
	 * Shallow copies a shell so that it does not point to any childern
	 * 
	 * @return a copy of the current shell with no references to its children
	 */
	public Shell copyShallow() {
		Shell copy = new Shell(this.parent, null);

		for (PointND q : this) {
			copy.add(q);
		}
		return copy;
	}

	/**
	 * Creates a mapping from segments in B to shells that represent all points
	 * inbetween the endpoints of the segment
	 * 
	 * @param b
	 * @param keys
	 * @return A hash map of segments in B to shells that represent all points in
	 *         this that lie inbetween the endpoints of the segment
	 */
	private HashMap<Segment, Shell> splitBy(Shell b, ArrayList<Segment> keys) {
		HashMap<Segment, Shell> result = new HashMap<Segment, Shell>();
		int index = 0;
		Shell firstTemp = new Shell();
		PointND lastB = null, firstB = null, prevB = null, nextB = null;
		boolean first = true;
		Shell temp = new Shell();
		int count = 0;
		for (PointND p : this) {
			if (b.contains(p)) {
				count++;
				if (first) {
					firstB = p;
					prevB = p;
					nextB = prevB;
					first = false;
				} else {
					if (count == b.size()) {
						lastB = p;
					}

					prevB = nextB;
					nextB = p;
					Segment s = new Segment(prevB, nextB);
					result.put(s, temp);
					keys.add(s);
					temp = new Shell();
				}
			} else { // is this guaranteed to work?
				if (first) {
					firstTemp.add(p);
				} else {
					temp.add(p);
				}
			}
		}
		int idx = 0;
		for (PointND p : temp) {
			firstTemp.add(idx, p);
			idx++;
		}
		if(lastB == null) {
			lastB = firstB;
		}
		Segment s = new Segment(lastB, firstB);
		result.put(s, firstTemp);
		keys.add(s);
		return result;
	}

	/**
	 * Creates a mapping from each point in B to a shell that represents all points
	 * in this that come after the key and before the next point in b
	 * 
	 * @param b
	 * @param startPoints all of the points in b
	 * @return A hashmap from points in b to shells that represents points in this
	 *         between the key and the next key
	 */
	private HashMap<PointND, Shell> splitInHalf(Shell b, ArrayList<PointND> startPoints) {
		HashMap<PointND, Shell> result = new HashMap<PointND, Shell>();
		int index = 0;
		Shell firstTemp = new Shell();
		PointND lastB = null, firstB = null, prevB = null, nextB = null;
		boolean first = true;
		Shell temp = new Shell();
		int count = 0;
		for (PointND p : this) {
			if (b.contains(p)) {
				count++;
				if (first) {
					firstB = p;
					prevB = p;
					nextB = prevB;
					first = false;
				} else {
					if (count == b.size()) {
						lastB = p;
					}

					prevB = nextB;
					nextB = p;
					Segment s = new Segment(prevB, nextB);
					result.put(s.first, temp);
					startPoints.add(s.first);
					temp = new Shell();
				}
			} else {
				if (first) {
					firstTemp.add(p);
				} else {
					temp.add(p);
				}
			}
		}
		int idx = 0;
		for (PointND p : temp) {
			firstTemp.add(idx, p);
			idx++;
		}
		Segment s = new Segment(lastB, firstB);
		result.put(s.first, firstTemp);
		startPoints.add(s.first);
		return result;
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
		for(PointND p : this) {
			result.addFirst(p);
		}
		return result;
	}
	
	public double getAngleSum(DistanceMatrix d) {
		double sum = 0.0;
		for(PointND p : this) {
			PointND next = this.nextPoint(p);
			PointND last = this.prevPoint(p);
			double angle =  Vectors.findAngleSegments(last, p, next, d);
			System.out.println(angle);
			sum += angle;
		}
		return sum;
	}
	
	public double getAngleRatio(DistanceMatrix d) {
		return this.getAngleSum(d)/this.size();
	}
	public int getObtuseAngles(DistanceMatrix d) {
		int sum = 0;
		for(PointND p : this) {
			PointND next = this.nextPoint(p);
			PointND last = this.prevPoint(p);
			double angle =  Vectors.findAngleSegments(last, p, next, d);
			System.out.println(angle);
			if(angle > Math.PI/2) {
				sum ++;
			}
		}
		return sum;
	}
	
	@Override
	public String toString() {
		String str = "Shell[";
		for(int i = 0; i < this.size(); i++) {
			PointND p = this.get(i);
			if(p.isDummyNode()) {
				Segment s = p.getDummyParents();
				str += "("+ s.first.getID()+" <=> "+ s.last.getID() + ")";
			}
			else if(p.getID() != -1) {
				str += p.getID();
			}
			else {
				str += p.toString();
			}
			if(i < this.size() - 1) {
				str += ", ";
			}
		}
		
		return str+"]";
	}
	public String toStringRecursive() {
		String str = "Shell[\n";
		Shell curr = this;
		while(!curr.isMinimal()) {
			str += "\tOrder: " + curr.updateOrder() + " ," + curr + "\n";
			curr = curr.child;
		}
		
		return str + "\tOrder: " + curr.updateOrder() + " ," + curr +"\n\t]";
	}
	public int sizeRecursive() {
		
		Shell curr = this;
		int ret = curr.size();
		while(!curr.isMinimal()) {
			
			ret += curr.child.size();
			curr = curr.child;
		}
		
		return ret;
	}
	
	public static String compareTo(Shell A, Shell B) {
		String str = "Shell A[";
		for(int i = 0; i < A.size() -1; i++) {
			str += (i) + ", ";
		}
		str += A.size()-1 + "]";
		
		str += "\nShell B[";
		for(int i = 0; i < B.size() -1; i++) {
			str += (A.indexOf(B.get(i))) + ", ";
		}
		str += (A.indexOf(B.get(B.size() -1)))+ "]";
		
		return str;
		
	}
	
	@Override
	public boolean add(PointND e){
		assert(!this.contains(e));
		super.add(e);
		return true;
		
	}
	@Override
    public boolean addAll(Collection<? extends PointND> c) {
    	for(PointND p : c) {
    		//assert(!this.contains(p)): this.toString() + " " + c.toString();
    	}
    	super.addAll(c);
        return true;
    }
	public boolean addAllFirst(Collection<? extends PointND> c) {
		Object[] points =  c.toArray();
    	for(int i = points.length-1; i>= 0; i--) {
    		this.addFirst((PointND) points[i]);
    	}
        return true;
    }

	public void addAfter(PointND contained, PointND insert) {
		//assert(!this.contains(insert));
		super.add(this.indexOf(contained)+1, insert);
	}
	
	public void addOutside(PointND contained, PointND insert) {
		//assert(!this.contains(insert));
		assert(this.getLast().equals(contained) || this.getFirst().equals(contained)) : insert.getID()+ " " + contained.getID() + " " + this.toString();
		super.add(this.indexOf(contained)+1, insert);
		if(this.getLast().equals(contained)) {
			this.rotateTo(this.getFirst(), insert);
		}
		else {
			this.rotateTo(this.getLast(), insert);
		}
	}
	
	public void addAllAtSegment(PointND contained, PointND connector, Shell other) {
		if(this.getLast().equals(contained)) {
			if(other.getLast().equals(connector)) {
				Shell reverse = other.reverse();
				this.addAll(reverse);
			}else {
				this.addAll(other);
			}
		}
		else {
			if(other.getLast().equals(connector)) {
				this.addAllFirst(other);
			}else {
				Shell reverse = other.reverse();
				this.addAllFirst(reverse);
			}
		}
	}
	
	public PointND getOppositeOutside(PointND endpoint) {
		//assert(!this.contains(insert));
		assert(this.getLast().equals(endpoint) || this.getFirst().equals(endpoint)) : endpoint.getID();
		if(this.getLast().equals(endpoint)) {
			return this.getFirst();
		}
		else {
			return this.getLast();
		}
	}

    public boolean isEndpoint(PointND p) {
        return p.equals(this.getLast()) || p.equals(this.getFirst());
    }

	public boolean containsID(int id) {
		for (PointND pointND : this) {
			if(pointND.getID() == id){
				return true;
			}
		}
		return false;
	}


}
