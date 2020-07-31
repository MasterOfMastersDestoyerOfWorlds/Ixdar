package shellCopy;

import java.awt.geom.Point2D;
import java.util.HashSet;

/**
 * A set of all of the points in the current TSP problem
 */
public class PointSet extends HashSet<Point2D>{
	private static final long serialVersionUID = 6129018674280186123L;


	/**
	 * This divides the point set into numerous convex shells that point to their child and parent shells.
	 * @return the outermost shell of the point set that conatins all other shells
	 */
	public Shell toShells() {
		PointSet copy = (PointSet) this.clone();
		Shell rootShell = null, currShell = null;
		
		while(copy.size() > 0) {
			Point2D A = findCentroid(copy);
			Point2D B = findAnoid(copy, A);
			Point2D start = B;

			//makes the first shell
			if(rootShell == null) {
				rootShell = new Shell(null, null, this);
				currShell = rootShell;
			}
			//makes a new child shell for the currShell
			else {
				Shell nextShell = new Shell(currShell, null, this);
				currShell.setChild(nextShell);
				currShell = nextShell;
			}
			
			PointSet outerShellPointSet= new PointSet();
			
			
			boolean breakFlag = false;
			//Creates the next convex shell
			while(!breakFlag) {
				
				double maxAngle = 0;
				Point2D maxPoint = null;
				int count = 0;
				for(Point2D p : copy) {
					double angle = Vectors.findAngleSegments(A, B, p);
					if(angle > maxAngle && !outerShellPointSet.contains(p) && !A.equals(p) && !B.equals(p)) {
						
						maxAngle = angle;
						maxPoint = p;
					}
					count ++;
				}
				if(maxPoint == null || maxPoint.equals(start) ) {
					breakFlag = true;

					currShell.add(start);
					outerShellPointSet.add(start);
				}	
				else {
					A = B;
					B = maxPoint;
					outerShellPointSet.add(B);
					currShell.add(B);

				}
			}
			
			copy.removeAll(outerShellPointSet);
		}
		rootShell.updateOrder();
		return rootShell;
	}

	/**
	 * Finds the centroid of the pointset ps
	 * @param ps
	 * @return the centroid
	 */
	public static Point2D findCentroid(PointSet ps) {
		int averageX = 0, averageY = 0;
		
		for(Point2D p : ps) {
			averageX += p.getX();
			averageY += p.getY();
		}
		return new Point2D.Double(averageX/ps.size(), averageY/ps.size());
	}

	/**
	 * Finds the anoid of the pointset ps
	 * @param ps
	 * @param centroid
	 * @return the anoid
	 */
	public static Point2D findAnoid(PointSet ps, Point2D centroid) {
		double maxDist = 0;
		Point2D anoid = null;
		
		for(Point2D p : ps) {
			double dist = p.distance(centroid);
			if(dist > maxDist) {
				maxDist = dist;
				anoid = p;
			}
		}
		return anoid;
	}

}
