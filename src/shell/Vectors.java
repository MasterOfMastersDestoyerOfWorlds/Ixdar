package shell;

import java.awt.geom.Point2D;

public class Vectors {
	
	public static double findAngleSegments(Point2D A, Point2D B, Point2D C) {
		Point2D BC = new Point2D.Double(C.getX()-B.getX(), C.getY()-B.getY());
		Point2D AB = new Point2D.Double(A.getX()-B.getX(), A.getY()-B.getY());
		double magAB = magnitude(AB), magBC = magnitude(BC);
		double dot = findDotProduct(AB,BC);
		return Math.acos(dot/(magAB*magBC))*180/Math.PI;
	}
	public static double magnitude(Point2D A) {
		return A.distance(new Point2D.Double(0, 0));
	}
	public static double findDotProduct(Point2D AB, Point2D BC){
		
		return  AB.getX()*BC.getX() + AB.getY()*BC.getY();
		
	}
	public static double distanceChanged(Point2D lastPoint, Point2D currPoint, Point2D q) {
		Point2D AC = new Point2D.Double(q.getX()-lastPoint.getX(), q.getY()-lastPoint.getY());
		Point2D AB = new Point2D.Double(currPoint.getX()-lastPoint.getX(), currPoint.getY()-lastPoint.getY());
		Point2D BC = new Point2D.Double(q.getX()-currPoint.getX(), q.getY()-currPoint.getY());

		return magnitude(AC) + magnitude(BC) -magnitude(AB);
	}
	public static double distanceToSegment(Point2D lastPoint, Point2D currPoint, Point2D q) {
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
	public static double getProjectionScalar(Point2D v, Point2D u) {
		// TODO Auto-generated method stub
		return findDotProduct(v, u)/Math.pow(magnitude(u), 2);
	}

}
