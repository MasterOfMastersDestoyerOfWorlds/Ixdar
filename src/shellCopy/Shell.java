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
 * ==============================================
 * Scheduling:
 * ==============================================
 * 
 * Andrew: working on the weekends and sometimes at night after work
 * 
 * Ares: pretty much the same
 * 
 * meetup every saturday
 * 
 * ==============================================
 * Ownership rules:
 * ==============================================
 * 
 * super shares?
 * 
 * ownershares are worth zero dollars and constitute one vote
 * 
 * one entity can have one vote
 * 
 * can destroy ownershare at anytime for no financial compensation
 * 
 * to create and ownershare everyone must agree
 * 
 * public shares are normal but don't constitute board membership
 * 
 * everyones shares get split during splitting ( no one man gets fucked policy)
 * 
 * trade secrets including algorithms must be kept secret until death and beyond
 * 
 * 
 * 
 * ================================================
 * Paper Credits
 * ================================================
 * 
 * how do recognize Clayton and how much does he deserve: 
 * definitely on the paper but is he a special thanks or last author?
 * 
 * Andrew Wollack and Ares Shackleford are first co-authors 
 * 
 * Special thanks to: Clayton Chu, Edward Wollack, idk who else yet
 * 
 * ================================================
 * Monetization Plan:
 * ================================================
 * Should we even tell people about this
 * 
 * Ryan and Alec to look at this? how are we willing to offer, how do we establish trust, 
 * 
 * 
 * given the current recession and virus situation is it prudent to  release our findings to the public or pursue 
 * business ventures given the lack of capital available?
 * 
 * will we go to jail for releasing this?
 * 
 * do we know the full implications of P=NP?
 * 
 * should we release the code or just the general algorithm? is it any different?
 * 
 * 
 * Sectors:
 *  * security: steal things?
 *  * networking: verizon
 *  * stock market:  how to stop, trade secret?
 *  * delivery routing amazon?
 *  * neural network architecture replacement:
 *  
 *  https://cacm.acm.org/magazines/2009/9/38904-the-status-of-the-p-versus-np-problem/fulltext
 *  
 *  * Finding a DNA sequence that best fits a collection of fragments of the sequence (see Gusfield20).
 *	* Finding a ground state in the Ising model of phase transitions (see Cipra8).
 *	* Finding Nash Equilbriums with specific properties in a number of environments (see Conitzer9).
 *	* Finding optimal protein threading procedures.26
 *	* Determining if a mathematical statement has a short proof (follows from Cook10).
 * 
 *  example:
 *  *Google pagerank is not that complicated the business moat is number of people using hte platform.
 *  
 *  Patent: could we patent and would it be worth it? would we have to make a product that relies on 
 *  the algorithm to patent
 *  
 *  how to prove:?
 *  Two cases:
 *  A shell is a SHell of S then A is a TSP Path of A
 *  
 *  Does a shell collapse onto a Tsp path result in another tsp path if the tsp path was enclosing the shell
 *  
 *  How to generalize in higher dimensions? is this natural from the algorithm we have currently?
 *  
 * =========================================
 * Definitions:
 * =========================================
 * Collapse function - takes all of A and inserts each point into the closest neighboring segment of B 
 * where the number of segments in B grows with each insert (Clayton)
 * 
 * Reduce function - takes a Shell and makes single replacements until the shell is in a minimal state
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
 * =======================================
 * Equations:
 * =======================================
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
 * G dosen't necissarily equal H
 * 
 * 3333333333333333333333333333333333333333333
 * 
 * Least comfortable with this one
 * 
 * If you have a path J constructed by ((A1 <X A2) <X ...) <X AN where AN is the order 0 Shell of S and A1 is
 * the maximal shell, Then J is a TSP path so long as the shell order from one vertex to the next never changes by more
 * than 1. 
 * 
 * NOT TURE
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
 * it is unclear if this works for more than three shells( i think it does)
 * 
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
 * 
 * It is  interesting to note that while the property that the order from one vertex to the next never changes by more
 * than 1 does not hold for all TSP Paths, each shell can be thought of as dividing the TSP Path into  into sections where 
 * each vertex of the shell owns a  part of the TSP PAth that leads to the next vertex of the shell in the clockwise or
 *  counter clockwise direction however you can not say this about any ordered group of points on the path.
 * 
 * ^ this is common but false in general
 * I am pretty sure that the points will not always be in a clockwise or counter clockwise order with respect to each 
 * other, but they will always be in the same order when merged with the shells around them can this even possibly be true?
 * 
 * WRONG WALL 
 * 
 * =======================================================================================
 * OPEN QUESTIONS:
 * =======================================================================================
 * Q:	Are there any collapse and reduce functions that could possibly be used to  maintain tsppath without doing the ]
 * 		consensus algorithm on the induction step?
 * 
 * A:	So far no, any collapse/reduce function that I have come up with cannot be used to do simple induction without 
 * 		a consensus function.
 * 
 * =======================================================================================
 * Q: 	Is the consensus function the same as collapsing every shell onto its neighbors and doing consensus on those 
 * 		collapsed shells?
 * 
 * A:	I think that this is the case and is the reason why you can't just collapse and reduce the parent shell onto its
 * 		children recursively.
 * 
 * ======================================================================================
 * Q: 	Is the algorithm a good optimizer or does it actually solve TSP?
 * 
 * A:	There is no way to know this without a proof that the algorithm optimally solves TSP, but my intuition is that
 * 		it does solve TSP.
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
	
	public double getLength() {
		Point2D first = null, last = null;
		double length = 0.0;
		for(Point2D p: this) {
			if(first == null) {
				last = p;
				first = p;
			}
			else {
				length += last.distance(p);
				last = p;
			}
		}
		length += last.distance(first);
		return length;
		
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
	 *  this is where the real problem lies i think the issue is that the line version does not work
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
	
	/**
	 * TODO fix this shit its broken. 
	 * 
	 * two possible cases since i think that the actual reduce part is correct
	 * 1. we also need to reduce lines during the consensus algorithm. not sure how to do this or why my previous aproach didnt work
	 * 2. we need to combine the reduce and the collapse functions into one function. i can see why this would be the case, but it would make me not happy.
	 * @param result
	 * @param isLine
	 */
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
					//im pretty sure this is where the problem lies. cuases self crossing
					if(!currPoint.equals(p) && !lastPoint.equals(p)) {
						//the line above is fine, but this line does not fully consider the distance lost or gained by connecting p's neighbors
						//distance changed = old distance between segments - new distance between segments
						double distanceChanged = Vectors.distanceChanged(lastPoint, currPoint, p) + (result.distanceBetweenNeighbors(p) - result.distanceToNeighbors(p) );
						if (distanceChanged < minDist && distanceChanged < 0) {
							minDist = distanceChanged;
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
	
	
	public Shell consensusWithChildren(boolean reduce) {
		if (this.isMinimal()) {
			return this;
		} else if (this.child.isMinimal()) {
			return this.collapseChildOntoShell();
		}
		Shell A = this.copy();
		Shell B = this.child.copy();
		Shell C = this.child.child.copy();
		Shell AB = collapseBOntoA(A, B, false, reduce);
		Shell BC = collapseBOntoA(C, B, false, reduce);
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
				} 
				else {
					Point2D prev = s.first, next = s.last;
	
					Shell line = new Shell(null, null, ps);
					line.add(prev);

					line.addAll(ABsections.get(s));
					line.add(next);
	
					Shell items = new Shell(null, null, ps);
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
		
		return collapseBOntoA(result, leftOvers, false, reduce);

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
		int idx = 0;
		for(Point2D p: temp) {
			firstTemp.add(idx, p);
			idx++;
		}
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
