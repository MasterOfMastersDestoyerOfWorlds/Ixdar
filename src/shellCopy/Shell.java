package shellCopy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Point2D.Double;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.swing.JComponent;

/*NOTES:
 * 
 * 
 * Definitions==========================
 * Collapse function - takes all of A and inserts each point into the closest neighboring segment of B 
 * where the number of segments in B grows with each insert
 *  
 * X> = collapse function i.e. AX>B =collapse of A onto B and A <X B = collapse of B onto A
 * NOTE: the path on the left always encloses the path on the right
 * 
 * Solve Set - the collection of points to solve TSP on. Abbreviated as S
 * 
 * Shell - the minimal closed polygon with vertices that are in the Solve Set  that encloses the Solve Set.
 * 
 * Shell Function - F(S) = Shell of S
 * 
 * Related Shells - R=
 * A = F(S)
 * S2 = S - A
 * B = F(S2)
 * A and B are Related Shells and this relationship is associative so if (A R= B) and (B R= C) then (A R= C)
 * 
 * Shell Order -how many internal Related Shells a Shell has  in the previous example A has 2 internal shells, 
 * B has 1, and C has 0, so A has Order 2, B has Order 1 etc.
 * 
 * TSP Path - A closed polygon with every point in S in TSP Path such that the distance is minimized
 * 
 * Equations============================
 * 
 * I believe these to be true:
 * 
 * 
 * 1111111111111111111111111111111111111111111
 * A is a Shell of S then A is a TSP Path of A
 * 
 * let A and B be Related Shells where the order of A is larger than the order of B
 * 
 * C = A <X B
 * 
 * C is a TSP Path and not a Shell
 * 
 * 2222222222222222222222222222222222222222222
 * let D and E be TSP Paths
 * 
 * G = D X> E
 * 
 * H = E X> D
 * 
 * G != H
 * 
 * 3333333333333333333333333333333333333333333
 * 
 * Least comfortable with this one
 * 
 * If you have a path J constructed by ((A1 <X A2) <X ...) <X AN where AN is the order 0 Shell of S and A1 is
 * the maximal shell, Then J is a TSP path so long as the  order from one vertex to the next never changes by more
 * than 1. 
 * 
 * 4444444444444444444444444444444444444444444
 * 
 * We can make 3 related shells B,C and Enclosing TSP Path A into a TSP Path via the following consensus algorithm
 * 
 * Unsure if A can be a TSP Path or if only works for three related shells, if not i dont know what to do.
 * 
 * let D be A <X B with shell order (A + B )/2
 * 
 * let E be B X> C  with shell order (B + C)/2
 * 
 * 
 * let TSPSol  = D & E
 * for example A = [ 1 , 2, 3 ]
 * 			   B = [ 4 , 5, 6 ]
 * 			   C = [ 7 , 8, 9 ] 
 *
 * say that    D = [1, 2, 4, 5, 3, 6]
 * and that    E = [4, 7, 8, 9, 5, 6]
 *    
 * then        TSPSOL = [1, 2, 4, 7, 8, 9, 5, 3, 6]
 *   
 * this is an example with no conflicts
 *   
 * still need to figure out how to resolve conflicts
 *   
 * Conflict example:
 *   
 * say that D = [1, 4, 2, 5, 3, 6]
 * and that E = [4, 7, 8, 9, 5, 6]
 *    
 * how would we sort 2, 7, 8, and 9
 * 
 * by their distance to 4 and 5?
 * 
 * or
 * 
 * collapse of 2,7,8,9 onto the line segment formed by 4,5
 * 
 * or 
 * 
 * idfk
 * 
 * it is unclear if this works for more than three shells
 * 
 * 555555555555555555555555555555555555555555555555555
 * 
 * 
 * let A be a TSPPath such that A encloses minimal  Shell B
 * 
 * then A <X B is TSPPath
 * 
 * this is probably false unless???
 * 
 * 666666666666666666666666666666666666666666666666666
 * It is  interesting to note that while the property that the order from one vertex to the next never changes by more
 * than 1 does not hold for all TSP Paths, each shell can be thought of as dividing the TSP Path into  into sections where 
 * each vertex of the shell owns a  part of the TSP PAth that leads to the next vertex of the shell in the clockwise or
 *  counter clockwise direction however you can not say this about any ordered group of points on the path.
 * 
 * ^ this is common but false in general
 * I am pretty sure that the points will not always be in a clockwise or counter clockwise order with respect to each 
 * other, but they will always be in the same order when merged with the shells around them can this even possibly be true?
 * 
 * 
 * 
 */
public class Shell extends LinkedList<Point2D> {
	private static final long serialVersionUID = -5904334592585016845L;
	private int ORDER = 0;
	private boolean maximal, minimal;
	private Shell parent, child;
	private PointSet ps;

	public Shell(Shell parent, Shell child, PointSet ps) {
		this.parent = parent;
		this.child = child;
		this.updateOrder();
		if (!this.isMaximal()) {
			parent.updateOrder();
		}
		this.ps = ps;
	}

	public void drawShell(JComponent frame, Graphics2D g2, Random colorSeed, boolean drawChildren) {
		Main.drawPath(frame, g2, shellToPath(this),
				new Color(colorSeed.nextFloat(), colorSeed.nextFloat(), colorSeed.nextFloat()), ps, true, false, false);
		if (!this.isMinimal() && drawChildren) {
			child.drawShell(frame, g2, colorSeed, drawChildren);
		}
	}

	public Shell getMinimalShell() {
		if (this.isMinimal()) {
			return this;
		} else {
			return child.getMinimalShell();
		}
	}

	public Shell getMaximalShell() {
		if (this.isMaximal()) {
			return this;
		} else {
			return child.getMaximalShell();
		}
	}

	public boolean isMaximal() {
		return parent == null;
	}

	public boolean isMinimal() {
		return child == null;
	}

	public Shell getParent() {
		return parent;
	}

	public Shell getChild() {
		return child;
	}

	public int updateOrder() {
		if (!this.isMinimal()) {
			this.ORDER = child.updateOrder() + 1;
		} else {
			this.ORDER = 0;
		}
		return this.ORDER;
	}

	public void setChild(Shell child) {
		this.child = child;
		minimal = false;
		this.updateOrder();
		if (parent != null) {
			parent.updateOrder();
		}
	}

	public Shell collapseChildOntoShell() {
		if (this.isMinimal()) {
			return this;
		}
		Shell result = collapseBOntoA(this, this.child, false, true);
		result.child = result.child.child;
		return result;
	}

	public Shell collapseShellOntoParent() {
		if (this.isMaximal()) {
			return this;
		}
		Shell result = collapseBOntoA(this.parent, this, false, true);
		result.child = result.child.child;
		return result;
	}
	
	public double distanceToNeighbors( Point2D p) {
		Point2D prevP = prevPoint(p), nextP = nextPoint(p);
		
		return p.distance(prevP.getX(), prevP.getY()) + p.distance(nextP.getX(), nextP.getY());
		
	}
	public double distanceBetweenNeighbors( Point2D p) {
		Point2D prevP = prevPoint(p), nextP = nextPoint(p);
		
		return prevP.distance(nextP.getX(), nextP.getY());
		
	}
	
	public Point2D prevPoint(Point2D p) {
		int i  = this.indexOf(p), before = 0;
		if(i == 0) {
			before = this.size() - 1;
		}
		else {
			before = i - 1;
		}
		return this.get(before);
	}
	public Point2D nextPoint(Point2D p) {
		int i  = this.indexOf(p), after = 0;
		if(i == this.size() - 1) {
			after = 0;
		}
		else {
			after = i + 1;
		}
		return this.get(after);
	}

	/*
	 * A onto B
	 * 
	 * TODO: change so that keeps collapsing onto self until last self  = self
	 */
	public static Shell collapseBOntoA(Shell A, Shell B, boolean isLine, boolean reduce) {
		Shell result = A.copy();
		Shell copy = B.copy();
		while (copy.size() > 0) {
			Point2D pointChosen = null;
			int chosenParent = 0;
			boolean first = true;
			Point2D lastPoint, currPoint = null;
			double minDist = java.lang.Double.MAX_VALUE;
			for (int i = 0; i < result.size(); i++) {
				lastPoint = currPoint;
				currPoint = result.get(i);
				if (first && !isLine) {
					lastPoint = result.getLast();
					first = false;
				}
				else if(first && isLine) {
					lastPoint = currPoint;
					first = false;
					i++;
					currPoint = result.get(i);
				}
				for (Point2D q : copy) {
					double dist = Vectors.distanceChanged(lastPoint, currPoint, q);
					if (dist < minDist) {
						minDist = dist;
						pointChosen = q;
						chosenParent = i;
					}
				}

			}
			result.add(chosenParent, pointChosen);
			copy.remove(pointChosen);
		}
		if(reduce) {
			reduceShell(result, isLine);
		}
		return result;
	}
	
	public static void reduceShell(Shell result, boolean isLine) {
		boolean notConfirmed = true;
		while (notConfirmed) {
			Point2D pointChosen = null;
			int chosenParent = 0;
			boolean first = true, changed = false;
			Point2D lastPoint, currPoint = null;
			double minDist = java.lang.Double.MAX_VALUE;
			for (int i = 0; i < result.size(); i++) {
				lastPoint = currPoint;
				currPoint = result.get(i);
				if (first && !isLine) {
					lastPoint = result.getLast();
					first = false;
				}
				else if(first && isLine) {
					lastPoint = currPoint;
					first = false;
					i++;
					currPoint = result.get(i);
				}
				for (Point2D p : result) {
					if(!currPoint.equals(p) && !lastPoint.equals(p)) {
						double dist = Vectors.distanceChanged(lastPoint, currPoint, p);
						if (dist < minDist && dist < (result.distanceToNeighbors(p) - result.distanceBetweenNeighbors(p) )) {
							minDist = dist;
							pointChosen = p;
							chosenParent = i;
							changed = true;
						}
					}
				}

			}
			if(changed) {
				result.remove(pointChosen);
				result.add(chosenParent, pointChosen);
			}
			notConfirmed = changed;
		}
	}

	public Shell copy() {
		Shell copy = null;
		if (!isMinimal()) {
			copy = new Shell(this.parent, this.child.copy(), this.ps);
		} else {
			copy = new Shell(this.parent, null, this.ps);
		}
		for (Point2D q : this) {
			copy.add(q);
		}
		return copy;
	}
	
	/*public static Set<Point2D> getPatternValues(Shell AB, Shell BC, Shell B) {
		Set<Segment> segAB = new HashSet<Segment>();
		Set<Segment> segBC = new HashSet<Segment>();

		Set<Segment> tempResult = new HashSet<Segment>();
		Set<Point2D> result = new HashSet<Point2D>();
		
		Point2D firstAB = null, lastAB = null;
		boolean first = true;
		for(Point2D p: AB) {
			if(B.contains(p)) {
				if(first) {
					firstAB = p;
					lastAB = p;
					first = false;
				}
				else {
					segAB.add(new Segment(lastAB, p));
					lastAB = p;
				}
			}
		}
		segAB.add(new Segment(lastAB, firstAB));
		
		Point2D firstBC = null, lastBC = null;
		boolean first2 = true;
		for(Point2D p: BC) {
			if(B.contains(p)) {
				
				if(first2) {
					firstBC = p;
					lastBC = p;
					first2 = false;
				}
				else {
					segBC.add(new Segment(lastBC, p));
					lastBC = p;
				}
			}
		}
		segBC.add(new Segment(lastBC, firstBC));

		System.out.println("\n+++++++++++++++++\n");
		System.out.println(segAB);
		System.out.println("\n++++++++++++++++\n");
		System.out.println(segBC);
		for(Segment s : segAB) {
			if(segBC.contains(s)) {
				tempResult.add(s);
			}
		}
		for(Segment s : segBC) {
			if(segAB.contains(s)) {
				tempResult.add(s);
			}
		}		
		System.out.println("\n--------------------\n");
		System.out.println(tempResult);
		System.out.println("\n--------------------\n");
		for(Segment s: tempResult) {
			result.add(s.first);
			result.add(s.last);
		}
		System.out.println(result);
		System.out.println("\n--------------------\n");
		return result;
		
	}*/
	
	public Shell consensusWithChildren() {
		if (this.isMinimal()) {
			return this;
		} else if (this.child.isMinimal()) {
			return this.collapseChildOntoShell();
		}
		Shell A = this.copy();
		Shell B = this.child.copy();
		Shell C = this.child.child.copy();
		Shell AB = collapseBOntoA(A, B, false, true);
		Shell BC = collapseBOntoA(C, B, false, true);
		ArrayList<Segment> ABKeys = new ArrayList<Segment>(), BCKeys = new ArrayList<Segment>();
		
		HashMap<Segment, Shell> ABsections = AB.splitBy(B, ABKeys);
		HashMap<Segment, Shell> BCsections = BC.splitBy(B, BCKeys);

		Shell leftOvers = new Shell(null, null, ps);
		
		Shell result = new Shell(null, BC.child, ps); 
		for(Segment s : ABKeys) {
			if(!BCsections.containsKey(s)) {
				//TODO: set start and end to be where they connect to the B points
				Point2D point = AB.nextPoint(s.first);
				while(!point.equals(s.last)) {
					result.add(point);
					point = AB.nextPoint(point);
				}
				result.add(s.last);
			}
			else {
				if (BCsections.containsKey(s) && ABsections.get(s).size() == 0 && BCsections.get(s).size() == 0) {
					result.add(s.last);
				} else {
					Point2D prev = s.first, next = s.last;
	
					Shell line = new Shell(null, null, ps);
					line.add(prev);
					line.add(next);
	
					Shell items = new Shell(null, null, ps);
					items.addAll(ABsections.get(s));
					if(BCsections.containsKey(s)) {
						items.addAll(BCsections.get(s));
					}
					line = collapseBOntoA(line, items, true, false);
	
					line.remove(s.first);
					result.addAll(line);
	
				}
			}
		}
		
		for(Segment s : BCKeys) {
			if(!ABsections.containsKey(s)) {
				leftOvers.addAll(BCsections.get(s));
			}
		}
		
		return collapseBOntoA(result, leftOvers, false, true);

	}

	private Collection<? extends Point2D> reverse(Shell shell) {
		// TODO Auto-generated method stub
		
		for(int i = 0; i < shell.size(); i ++) {
			Point2D first = shell.pop();
			shell.add(first);
			
		}
		return shell;
	}

	private HashMap<Segment, Shell> splitBy(Shell b, ArrayList<Segment> keys) {
		HashMap<Segment, Shell> result = new HashMap<Segment, Shell>();
		int index = 0;
		Shell firstTemp = new Shell(null, null, ps);
		Point2D lastB = null, firstB = null, prevB = null, nextB = null;
		boolean first = true;
		Shell temp = new Shell(null, null, ps);
		int count = 0;
		for (Point2D p : this) {
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
					temp = new Shell(null, null, ps);
				}
			} else {
				if (first) {
					firstTemp.add(p);
				} else {
					temp.add(p);
				}
			}
		}
		firstTemp.addAll(temp);
		Segment s = new Segment(lastB, firstB);
		result.put(s, firstTemp);
		keys.add(s);
		return result;
	}

	public static Path2D shellToPath(Shell shell) {
		Path2D path = new GeneralPath();
		boolean first = true;
		for (Point2D p : shell) {
			if (first) {
				path.moveTo(p.getX(), p.getY());
				first = false;
			} else {
				path.lineTo(p.getX(), p.getY());
			}

		}
		return path;

	}

}
