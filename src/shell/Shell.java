package shell;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
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
	private boolean maximal, minimal;
	private Shell parent, child;

	/**
	 * Initializes a new shell with no parent or child; a blank slate
	 * @param double2 
	 * @param double1 
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

	/**
	 * Draws the Shell and its children if drawChildren is true
	 * 
	 * @param frame        where to draw the shell
	 * @param g2           graphics object for frame
	 * @param colorSeed    only used if color is set to null in order to get a
	 *                     random color for the Shell drawing
	 * @param drawChildren whether or not to draw child shells
	 * @param c            the color to draw the shell (set to null to get a random
	 *                     color)
	 */
	public void drawShell(JComponent frame, Graphics2D g2, boolean drawChildren, Color c) {
		if (c == null) {
			Random colorSeed = new Random();
			Main.drawPath(frame, g2, toPath(this),
					new Color(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat()), this.toPointSet(),
					true, false, false);
		} else {
			Main.drawPath(frame, g2, toPath(this), c, this.toPointSet(), true, false, false);
		}
		if (!this.isMinimal() && drawChildren) {
			child.drawShell(frame, g2, drawChildren, c);
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
	 * @return the sum of the distance from p to the prev point in the shell and the
	 *         distance from p to the next point in the shell
	 */
	public double distanceToNeighbors(PointND p) {
		PointND prevP = prevPoint(p), nextP = nextPoint(p);

		return p.distance(prevP) + p.distance(nextP);

	}

	/**
	 * Gets the distance from the point previous to p and the point after p in the
	 * shell
	 * 
	 * @param p
	 * @return the sum of the distance from the prev point in the shell to the next
	 *         point in the shell
	 */
	public double distanceBetweenNeighbors(PointND p) {
		PointND prevP = prevPoint(p), nextP = nextPoint(p);

		return prevP.distance(nextP);

	}

	/**
	 * Gets the distance from the point previous to p and the point after p on the
	 * line
	 * 
	 * @param p
	 * @return the sum of the distance from the prev point on the line to the next
	 *         point on the line
	 */
	public double distanceToNeighborsOnLine(PointND p) {
		PointND prevP = prevPointOnLine(p), nextP = nextPointOnLine(p);

		return p.distance(prevP) + p.distance(nextP);

	}

	/**
	 * Gets the distance from the point previous to p and the point after p in the
	 * shell
	 * 
	 * @param p
	 * @return the sum of the distance from the prev point in the shell to the next
	 *         point in the shell
	 */
	public double distanceBetweenNeighborsOnLine(PointND p) {
		PointND prevP = prevPointOnLine(p), nextP = nextPointOnLine(p);

		return prevP.distance(nextP);

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

	// these methods are duplicate and we should comment them out and replace the
	// calls

	/**
	 * Finds the next point on the line, duplicates nextPoint
	 * 
	 * @param p reference point
	 * @return the point that comes before p on the line
	 */
	private PointND nextPointOnLine(PointND p) {
		int i = this.indexOf(p), after = 0;
		if (i == this.size() - 1) {
			after = i;
		} else {
			after = i + 1;
		}
		return this.get(after);
	}

	/**
	 * Finds the previous point on the line, duplicates prevPoint
	 * 
	 * @param p reference point
	 * @return the point that comes before p on the line
	 */
	private PointND prevPointOnLine(PointND p) {
		int i = this.indexOf(p), before = 0;
		if (i == 0) {
			before = i;
		} else {
			before = i - 1;
		}
		return this.get(before);
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

	/**
	 * Collapses all shells into one shell that is the tsp path
	 * 
	 * @return one shell that represents the optimal tsp path
	 */
	public Shell collapseAllShells() {
		int order = this.updateOrder();
		if (this.isMinimal()) {
			return this;
		}
		// the even case where we pop the min shell and collapse all shells other than
		// that
		// before collapsing the min one at the end
		if (order % 2 == 0) {
			ArrayList<Shell> popList = this.popMin();

			Shell A = popList.get(0).collapseAllShells();
			Shell B = popList.get(1);
			return collapseReduce(A, B);
		}
		// the odd case where we split the remaining shells in half and collapse shells
		// on both sides of the barrier shell
		// before collapsing both sides onto the barrier shell and calling the consensus
		// function
		else {
			int splitVal = (this.updateOrder() - 1) / 2;

			if (splitVal % 2 == 0) {
				splitVal = splitVal + 1;
			}
			ArrayList<Shell> splitList = this.split(splitVal);
			Shell A = splitList.get(0).collapseAllShells();
			Shell B = splitList.get(1);
			Shell C = splitList.get(2).collapseAllShells();
			Shell AB = collapseReduce(A, B);
			Shell BC = collapseReduce(C, B);
			return consensus(AB, BC);

		}
	}

	/**
	 * Collapses shell B onto shell A and reduces the tsp path
	 * 
	 * @param A
	 * @param B the child shell of A
	 * @return one shell that represents the optimal tsp path for all points in
	 *         shells A and B
	 */
	public static Shell collapseReduce(Shell A, Shell B) {
		Shell result = A.copyRecursive();
		Shell copy = B.copyRecursive();
		boolean notConfirmed = true;

		// once there is no change to result then the loop will exit
		// this will only happen once all points from copy are in result
		// and all points in result cannot be rearranged to form a shorter path
		while (notConfirmed) {
			PointND pointChosen = null;
			int chosenParent = 0;
			boolean first = true, changed = false;
			PointND lastPoint, currPoint = null;
			double minDist = java.lang.Double.MAX_VALUE;
			for (int i = 0; i < result.size(); i++) {
				lastPoint = currPoint;
				currPoint = result.get(i);
				if (first) {
					lastPoint = result.getLast();
					first = false;
				}
				for (PointND q : copy) {
					double dist = Vectors.distanceChanged(lastPoint, currPoint, q);
					// store which point in b fits best between the two current points in result
					if (dist < minDist) {
						minDist = dist;
						pointChosen = q;
						chosenParent = i;
						changed = true;
					}
				}
				for (PointND p : result) {
					if (!currPoint.equals(p) && !lastPoint.equals(p)) {
						double distanceChanged = java.lang.Double.MAX_VALUE;

						distanceChanged = Vectors.distanceChanged(lastPoint, currPoint, p)
								+ (result.distanceBetweenNeighbors(p) - result.distanceToNeighbors(p)); // why this
																										// second line

						// store which point if any already in result fits better in between curr and
						// last points
						// instead of where it currently is
						if (distanceChanged < minDist && distanceChanged < 0) {
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
				result.remove(pointChosen);
				result.add(chosenParent, pointChosen);
				copy.remove(pointChosen);
			}

			notConfirmed = changed;
		}

		return result;
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
	public static Shell solveBetweenEndpoints(Segment s, Shell A, Shell B) {
		PointSet ps = new PointSet();
		
		ps.add(s.first);
		ps.add(s.last);
		ps.addAll(A);
		ps.addAll(B);

		

		DistanceMatrix D = new DistanceMatrix(ps);
		D = D.addDummyNode(s.first, s.last);
		PointSet linePS = D.toPointSet();

		PointND dummyPoint = linePS.get(linePS.size() - 1);
		

		Shell lineShells = linePS.toShells();
		Shell currShell = lineShells;
		while(currShell != null) {
			Shell reducedShell = Shell.collapseReduce(currShell, new Shell());
			currShell.removeAll(currShell);
			currShell.addAll(reducedShell);
			currShell = currShell.getChild();
		}
		Shell copy = lineShells.copyRecursive();

		lineShells = lineShells.collapseAllShells();

		Shell before = new Shell(), after = new Shell();

		boolean isBeforeDummy = true;
		for (PointND p : lineShells) {
			if (p.equals(dummyPoint)) {
				isBeforeDummy = false;
			} else {
				PointND pointInPS = ps.get(linePS.indexOf(p));
				if (isBeforeDummy) {
					before.add(pointInPS);
				} else {
					after.add(pointInPS);
				}
			}
		}

		after.addAll(before);
		if(after.get(0).equals(s.last) || after.getLast().equals(s.first)) {
			after = after.reverse();
		}
		System.out.println("\n\n");
		System.out.println(s);
		System.out.println(ps);
		System.out.println(copy);
		System.out.println(after);
		return after;

	}

	/**
	 * Collapse B onto A just within the segment s
	 * 
	 * @param s
	 * @param A
	 * @param B
	 * @return a shell that is the optimal tsp path of the points in A and B between
	 *         the endpoints of segment s
	 */

	public static Shell solveBetweenEndpointsOld(Segment s, Shell A, Shell B) {
		PointSet ps = new PointSet();
		ps.addAll(A);
		ps.addAll(B);
		System.out.println("s: " + s);
		System.out.println("AB: " + ps);
		
		Shell result = new Shell();
		Shell copy = new Shell();

		result.add(s.first);
		result.addAll(A);
		result.add(s.last);
		copy.addAll(B);

		boolean notConfirmed = true;

		// once there is no change to result then the loop will exit
		// this will only happen once all points from copy are in result
		// and all points in result cannot be rearranged to form a shorter path
		while (notConfirmed) {
			PointND pointChosen = null;
			int chosenParent = 0;
			boolean first = true, changed = false;
			PointND lastPoint, currPoint = null;
			double minDist = java.lang.Double.MAX_VALUE;
			for (int i = 0; i < result.size(); i++) {
				lastPoint = currPoint;
				currPoint = result.get(i);
				if (first) {
					lastPoint = currPoint;
					first = false;
					i++;
					currPoint = result.get(i);
				}
				for (PointND q : copy) {
					if (!s.first.equals(q) && !s.last.equals(q)) {
						double dist = Vectors.distanceChanged(lastPoint, currPoint, q);
						// +(copy.distanceBetweenNeighborsOnLine(q) -
						// copy.distanceToNeighborsOnLine(q));
						// store which point in b fits best between the two current points in result
						if (dist < minDist) {
							minDist = dist;
							pointChosen = q;
							chosenParent = i;
							changed = true;
						}
					}
				}
				for (PointND p : result) {
					if (!currPoint.equals(p) && !lastPoint.equals(p)) {
						double distanceChanged = Vectors.distanceChanged(lastPoint, currPoint, p)
								+ (result.distanceBetweenNeighborsOnLine(p) - result.distanceToNeighborsOnLine(p));
						// store which point if any already in result fits better in between curr and
						// last points
						// instead of where it currently is
						if (distanceChanged < minDist && distanceChanged < 0) {
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
				result.remove(pointChosen);
				result.add(chosenParent, pointChosen);
				copy.remove(pointChosen);
			}

			notConfirmed = changed;
		}

			System.out.println("result: " + result);
			System.out.println("=====================================");
			return result;

		

	}

	/**
	 * Finds a consensus between the merged shells AB and BC Determines how to order
	 * points from A and C that come between the same two points in B
	 * 
	 * @param AB
	 * @param BC
	 * @return a shell that represents the optimal tsp path through shells A, B, and
	 *         C
	 */
	public static Shell consensus(Shell AB, Shell BC) {


		AB = AB.copyRecursive();
		BC = BC.copyRecursive();
		Shell B = pointsInCommon(AB, BC);
		ArrayList<Segment> ABKeys = new ArrayList<Segment>(), BCKeys = new ArrayList<Segment>(), keys = new ArrayList<Segment>();

		HashMap<Segment, Shell> ABsections = AB.splitBy(B, ABKeys);
		HashMap<Segment, Shell> BCsections = BC.splitBy(B, BCKeys);

		Shell result = new Shell(null, BC.child);
		for (Segment s : ABKeys) {
			// if the segment is in AB and not BC then add all non endpoints on the segment
			if (!BCsections.containsKey(s)) {
				// TODO: set start and end to be where they connect to the B points
				PointND point = AB.nextPoint(s.first);
				while (!point.equals(s.last)) {
					result.add(point);
					point = AB.nextPoint(point);
				}
				result.add(s.last);
			} // otherwise do collapse reduce line to get a consensus between points from A
				// and C that fit between the same points in B
			else {
				if (BCsections.containsKey(s) && ABsections.get(s).size() == 0 && BCsections.get(s).size() == 0) {
					result.add(s.last);
				} else {

					Shell line = solveBetweenEndpoints(s, ABsections.get(s), BCsections.get(s));

					line.remove(s.first);
					result.addAll(line);

				}
			}
		}
		// split by the leftover keys and then do the collapse reduce above on the
		// leftovers

		ArrayList<Segment> leftOverKeys = new ArrayList<Segment>();

		for (Segment s : BCKeys) {
			if (!ABsections.containsKey(s)) {
				leftOverKeys.add(s);

			}
		}

		// split by the leftover keys and then do the collapse reduce above on the
		// leftovers
		for (Segment s : leftOverKeys) {
			Shell leftOverShell = new Shell();
			leftOverShell.add(s.first);
			leftOverShell.add(s.last);
			HashMap<PointND, Shell> resultSections = result.splitInHalf(leftOverShell, new ArrayList<PointND>());
			PointND minIndex = null;
			double minLengthChange = java.lang.Double.MAX_VALUE;
			Shell minShell = null;

			for (PointND first : resultSections.keySet()) {
				Segment s1 = new Segment(null, null);
				if (first.equals(s.first)) {
					s1.first = s.first;
					s1.last = s.last;
				} else {
					s1.last = s.first;
					s1.first = s.last;
				}

				Shell beforeLine = new Shell();
				beforeLine.add(s1.first);
				beforeLine.addAll(resultSections.get(first));
				beforeLine.add(s1.last);

				double firstLength = beforeLine.getLength();

				Shell line = solveBetweenEndpoints(s, resultSections.get(first), BCsections.get(s));
				double changedLength = line.getLength();
				if (changedLength - firstLength < minLengthChange) {
					minLengthChange = changedLength - firstLength;
					minIndex = first;
					minShell = line;
				}
			}

			result = new Shell(null, BC.child);
			for (PointND first : resultSections.keySet()) {

				if (first.equals(minIndex)) {
					result.addAll(minShell);
				} else {
					result.addAll(resultSections.get(first));
				}
			}

		}
		System.out.println();
		System.out.println(AB);
		System.out.println(BC);
		System.out.println(ABKeys);
		System.out.println(result);

		return result;

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
	
	@Override
	public String toString() {
		String str = "Shell[";
		for(int i = 0; i < this.size(); i++) {
			if(this.get(i).getID() != -1) {
				str += this.get(i).getID();
			}
			else {
				str += this.get(i).toString();
			}
			if(i < this.size() - 1) {
				str += ", ";
			}
		}
		
		return str+"]";
	}
	
	public static String compareTo(Shell A, Shell B) {
		String str = "Shell A[";
		for(int i = 0; i < A.size() -1; i++) {
			str += (i) + ", ";
		}
		str += A.size() + "]";
		
		str += "\nShell B[";
		for(int i = 0; i < B.size() -1; i++) {
			str += (A.indexOf(B.get(i))) + ", ";
		}
		str += (A.indexOf(B.get(B.size() -1)))+ "]";
		
		return str;
		
	}

}
