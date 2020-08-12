package shell;

import java.util.ArrayList;

import resources.SWIGTYPE_p_boolT;
import resources.SWIGTYPE_p_coordT;
import resources.SWIGTYPE_p_int;
import resources.qhull;

/**
 * A set of all of the points in the current TSP problem
 */
public class PointSet extends ArrayList<PointND> {
	private static final long serialVersionUID = 6129018674280186123L;

	static {
		try {
			System.loadLibrary("qhull");
		}catch(Exception e) {
			System.out.println("name of shared lib should be: " + System.mapLibraryName("qhull"));
			System.out.println(e);
			
		}
		
	}

	/**
	 * make sure to see resources/swig.sh for details on compiling qhull to work with java also set the 
	 * native library to Users/user/Library/Java/Extensions in the build path so that
	 * eclipse can find the dylib
	 * @return the convex hull of the set of points in n dimensions according to qhull
	 */
	public Shell convexHull() {
		System.out.println(qhull.qh_rand());
		return null;
	}

	/**
	 * This divides the point set into numerous convex shells that point to their
	 * child and parent shells.
	 * 
	 * @return the outermost shell of the point set that conatins all other shells
	 */

	public Shell toShells() {
		PointSet copy = (PointSet) this.clone();
		Shell rootShell = null, currShell = null;

		while (copy.size() > 0) {
			PointND A = findCentroid(copy);
			PointND B = findAnoid(copy, A);
			PointND start = B;

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

			PointSet outerShellPointSet = new PointSet();

			boolean breakFlag = false;
			// Creates the next convex shell
			while (!breakFlag) {

				double maxAngle = 0;
				PointND maxPoint = null;
				int count = 0;
				for (PointND p : copy) {
					double angle = Vectors.findAngleSegments(A, B, p);
					if (angle > maxAngle && !outerShellPointSet.contains(p) && !A.equals(p) && !B.equals(p)) {

						maxAngle = angle;
						maxPoint = p;
					}
					count++;
				}
				if (maxPoint == null || maxPoint.equals(start)) {
					breakFlag = true;

					currShell.add(start);
					outerShellPointSet.add(start);
				} else {
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
	 * 
	 * @param ps
	 * @return the centroid
	 */
	public static PointND findCentroid(PointSet ps) {

		int maxDim = -1;
		for (PointND p : ps) {
			if (p.getDim() > maxDim) {
				maxDim = p.getDim();
			}
		}
		double[] ds = new double[maxDim];

		for (PointND p : ps) {
			double[] coordList = p.getCoordList();
			for (int i = 0; i < coordList.length; i++) {
				ds[i] += coordList[i] / ps.size();
			}
		}
		return new PointND.Double(ds);
	}

	/**
	 * Finds the anoid of the pointset ps
	 * 
	 * @param ps
	 * @param centroid
	 * @return the anoid
	 */
	public static PointND findAnoid(PointSet ps, PointND centroid) {
		double maxDist = -1;
		PointND anoid = null;

		for (PointND p : ps) {
			double dist = p.distance(centroid);
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

}
