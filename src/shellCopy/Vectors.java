package shellCopy;

import java.awt.geom.Point2D;



/**
 * This class is used to do distance and angle calculations between points in space
 */
public class Vectors {

	/**
	 * @param A
	 * @param B
	 * @param C
	 * @return returns the angle ABC
	 */
	public static double findAngleSegments(Point2D A, Point2D B, Point2D C) {
		Point2D BC = new Point2D.Double(C.getX()-B.getX(), C.getY()-B.getY());
		Point2D AB = new Point2D.Double(A.getX()-B.getX(), A.getY()-B.getY());
		double magAB = magnitude(AB), magBC = magnitude(BC);
		double dot = findDotProduct(AB,BC);
		return Math.acos(dot/(magAB*magBC))*180/Math.PI;
	}

	/**
	 * @param A
	 * @return the distance from the origin to A
	 */
	public static double magnitude(Point2D A) {
		return A.distance(new Point2D.Double(0, 0));
	}

	/**
	 * @param AB
	 * @param BC
	 * @return the dot product of AB and BC
	 */
	public static double findDotProduct(Point2D AB, Point2D BC){
		
		return  AB.getX()*BC.getX() + AB.getY()*BC.getY();
		
	}

	/**
	 * Calculates the change in distance when adding q into the path between currPoint and lastPoint.
	 *  First it calculates the the distance between currPoint and last Point equal to AC.
	 *  Then it calcualtes the distance from currPoint to q equal to AB and distance from q to lastPoint equal to BC
	 * @param lastPoint
	 * @param currPoint
	 * @param q 2d point in between lastPoint and currPoint
	 * @return AB + BC - AC
	 */
	public static double distanceChanged(Point2D lastPoint, Point2D currPoint, Point2D q) {
		
		// i think this is wrong or is being used incorrectly because if q is embedded in the 
		// path already then you also have to consider the distance to its neighbors
		Point2D AB = new Point2D.Double(q.getX()-lastPoint.getX(), q.getY()-lastPoint.getY());
		Point2D AC = new Point2D.Double(currPoint.getX()-lastPoint.getX(), currPoint.getY()-lastPoint.getY());
		Point2D BC = new Point2D.Double(q.getX()-currPoint.getX(), q.getY()-currPoint.getY());

		return magnitude(AB) + magnitude(BC) -magnitude(AC);
	}


	/*This is currently not used, but keeping for history and potential future use

	public static double getProjectionScalar(Point2D v, Point2D u) {
		// TODO Auto-generated method stub
		return findDotProduct(v, u)/Math.pow(magnitude(u), 2);
	}

	public static double distanceToSegment(Point2D lastPoint, Point2D currPoint, Point2D q) {
		// this is a poor metric and should probably not be used for optimization
		Point2D AC = new Point2D.Double(q.getX()-lastPoint.getX(), q.getY()-lastPoint.getY());
		Point2D AB = new Point2D.Double(currPoint.getX()-lastPoint.getX(), currPoint.getY()-lastPoint.getY());
		double y1 =lastPoint.getY(), y2 = currPoint.getY(), y0 = q.getY(),
				x1 =lastPoint.getX(), x2 = currPoint.getX(), x0 =q.getX();
		double projScalar = getProjectionScalar(AC, AB);
		if(projScalar >= 1) {
			return currPoint.distance(q);
		}
		else if(projScalar <= 0) {
			return lastPoint.distance(q);
		}
		else {
			return Math.abs((y2 - y1)*x0 - (x2 - x1)*y0 + x2*y1 - y2*x1)/Math.sqrt(Math.pow((y2-y1), 2) + Math.pow((x2-x1), 2));
		}
	}
	*/

}
