package shell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

import resources.SWIGTYPE_p_coordT;
import resources.SWIGTYPE_p_p_char;
import resources.qhull;
import shell.PointND.Double;

/**
 * A set of all of the points in the current TSP problem
 */
public class PointSet extends ArrayList<PointND> {
	private static final long serialVersionUID = 6129018674280186123L;

	static {
		try {
			System.loadLibrary("qhull");
		} catch (Exception e) {
			System.out.println("name of shared lib should be: " + System.mapLibraryName("qhull"));
			System.out.println(e);

		}

	}

	/**
	 * make sure to see resources/swig.sh for details on compiling qhull to work
	 * with java also set the native library to Users/user/Library/Java/Extensions
	 * in the build path so that eclipse can find the dylib
	 * http://www.swig.org/Doc1.3/Library.html this code should copy the process
	 * from resources/src/libqhull/unix.c i think anyway doing nconvexhull
	 * 
	 * @return the convex hull of the set of points in n dimensions according to
	 *         qhull
	 */
	public PointSet convexHullND(PointSet ps) {
		/*
		 * Run 1: convex hull
		 */
		SWIGTYPE_p_p_char NULL = qhull.new_Stringp();
		qhull.Stringp_assign(NULL, null);

		PointSet hull = new PointSet();
		try {

			FileOutputStream in = new FileOutputStream("in"), out = new FileOutputStream("out"), err = new FileOutputStream("err");
			qhull.qh_init_A(in, out, err, 0, NULL);
			int exitcode = qhull.setjmp_wrap();
			if (exitcode == 0) {
				qhull.setNOerrexit();
				qhull.qh_initflags("qhull s p");

				int maxDim = ps.getLargestDim();
				if(ps.size() <= maxDim) {
					return ps;
				}
				SWIGTYPE_p_coordT points = qhull.new_coordT_array(maxDim * ps.size());
				System.out.println();
				//TODO: need to check that the number of points is more than maxDim + 1 so that the initial simplex can be formed other wise they are just a convex hull i think?
				//also need to update the point constructor to not keep the padded zeros
				for (int i = 0; i < ps.size(); i++) {
					PointND p = ps.get(i);
					for (int j = 0; j < p.getDim(); j++) {
						qhull.coordTset(points, maxDim * i + j, p.getCoord(j));
					}
				}
				qhull.qh_init_B(points, ps.size(), maxDim, false);
				qhull.qh_qhull();
				qhull.qh_check_output();
				qhull.qh_produce_output();
				qhull.delete_coordT_array(points);
				qhull.delete_Stringp(NULL);
				BufferedReader reader;
				try {
					reader = new BufferedReader(new FileReader("out"));
					String line = reader.readLine();
					int dim = Integer.parseInt(line);

					line = reader.readLine();
					int numPoints = Integer.parseInt(line);
					for (int i = 0; i < numPoints; i++) {
						line = reader.readLine();
						String[] coordsStr = line.split("\\s+");
						double[] coords = new double[dim];
						int k = 0;
						for (int j = 0; j < coordsStr.length; j++) {
							if(!coordsStr[j].isEmpty()) {
								coords[k] = java.lang.Double.parseDouble(coordsStr[j]);
								k++;
							}
						}
						hull.add(ps.get(ps.indexOf(new PointND.Double(coords))));
					}

					reader.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				in.close();
				out.close();
				err.close();
			} else {
				throw new Exception("setjmp failed!");
			}
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return hull;
	}

	private int getLargestDim() {
		int maxDim = 0;
		for (PointND p : this) {
			if (maxDim < p.getDim()) {
				maxDim = p.getDim();
			}
		}
		return maxDim;
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
			PointSet hull;
			if(copy.getLargestDim() == 2) {
				hull = convexHull2D(copy);
			}
			else { 
				hull = convexHullND(copy);
			}
			
			currShell.addAll(hull);
			copy.removeAll(hull);


			//make sure that the convex hulls are in reduced forms(this is guaranteed in 2D but not in higher dimensions).
			Shell.collapseReduce(currShell, new Shell());
			Shell reducedShell = Shell.collapseReduce(currShell, new Shell());
			currShell.removeAll(currShell);
			currShell.addAll(reducedShell);
		}
		rootShell.updateOrder();
		return rootShell;
	}

	/**
	 * Does the 2d gift-wrapping/javis march algorithm to find the convex hull of a set of points and add those points
	 * @param ps
	 * @returnthe convex hull of the set of points in 2 dimensions
	 */
	public PointSet convexHull2D(PointSet ps) {
		PointND A = findCentroid(ps);
		PointND B = findAnoid(ps, A);
		PointND start = B;



		PointSet outerShellPointSet = new PointSet();

		boolean breakFlag = false;
		// Creates the next convex shell
		while (!breakFlag) {

			double maxAngle = 0;
			PointND maxPoint = null;
			int count = 0;
			for (PointND p : ps) {
				double angle = Vectors.findAngleSegments(A, B, p);
				if (angle > maxAngle && !outerShellPointSet.contains(p) && !A.equals(p) && !B.equals(p)) {

					maxAngle = angle;
					maxPoint = p;
				}
				count++;
			}
			if (maxPoint == null || maxPoint.equals(start)) {
				breakFlag = true;
				outerShellPointSet.add(start);
			} else {
				A = B;
				B = maxPoint;
				outerShellPointSet.add(B);

			}
		}
		return outerShellPointSet;
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
