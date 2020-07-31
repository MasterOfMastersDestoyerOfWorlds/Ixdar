package shellCopy;

import java.awt.geom.Point2D;
import java.util.HashSet;

public class PointSet extends HashSet<Point2D>{
	private static final long serialVersionUID = 6129018674280186123L;
	
	/*
	*Method 1: Shells to PointSet where does toPointSet goes in Shell.java
	*Method 2: PointSet to AdjacencyMatrixes should be nxn where n is points
	*
	*
	*/
	
	public Shell toShells() {
		PointSet copy = (PointSet) this.clone();
		Shell rootShell = null, currShell = null;
		
		while(copy.size() > 0) {
			Point2D A = findCentroid(copy);
			Point2D B = findAnoid(copy, A);
			Point2D start = B;
			
			if(rootShell == null) {
				rootShell = new Shell(null, null, this);
				currShell = rootShell;
			}
			else {
				Shell nextShell = new Shell(currShell, null, this);
				currShell.setChild(nextShell);
				currShell = nextShell;
			}
			
			PointSet outerShellPointSet= new PointSet();
			
			
			boolean breakFlag = false;
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
	public static Point2D findCentroid(PointSet ps) {
		int averageX = 0, averageY = 0;
		
		for(Point2D p : ps) {
			averageX += p.getX();
			averageY += p.getY();
		}
		return new Point2D.Double(averageX/ps.size(), averageY/ps.size());
	}
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
