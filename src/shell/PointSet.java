package shell;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import resources.SWIGTYPE_p_coordT;
import resources.SWIGTYPE_p_p_char;
import resources.qhull;

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
	public Shell convexHull(PointSet ps) {
		/*
		 * Run 1: convex hull
		 */
		SWIGTYPE_p_p_char NULL = qhull.new_Stringp();
		qhull.Stringp_assign(NULL, null);
		try {

			File in = new File("in"), out = new File("out"), err = new File("err");
			qhull.qh_init_A(new FileOutputStream(in), new FileOutputStream(out), new FileOutputStream(err), 0, NULL);
			int exitcode = qhull.setjmp_wrap();
			if(exitcode == 0) {
				qhull.setNOerrexit();
				qhull.qh_initflags("qhull s");
				
				int maxDim = ps.getLargestDim();
				SWIGTYPE_p_coordT points = qhull.new_coordT_array(maxDim*ps.size());
				System.out.println();
				for(int i = 0; i < ps.size(); i++) {
					PointND p = ps.get(i);
					for(int j = 0; j < p.getDim(); j++) {
						qhull.coordTset(points, maxDim*i + j, (float) 2);
					}
				}
				qhull.qh_init_B(points, ps.size(), maxDim, false);
			}
			else {
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
	  System.out.println("ree");
	  System.out.println("reee");
//	    printf( "\n========\ncompute triangulated convex hull of cube after rotating input\n");
//	    makecube(array[0], SIZEcube, DIM);
//	    fflush(NULL);
//	    qh_init_B(array[0], SIZEcube, DIM, ismalloc);
//	    qh_qhull();
//	    qh_check_output();
//	    qh_triangulate();  /* requires option 'Q11' if want to add points */
//	    print_summary();
//	    if (qh VERIFYoutput && !qh FORCEoutput && !qh STOPadd && !qh STOPcone && !qh STOPpoint)
//	      qh_check_points();
//	    fflush(NULL);
//	    printf( "\nadd points in a diamond\n");
//	    adddiamond(array[0], SIZEcube, SIZEdiamond, DIM);
//	    qh_check_output();
//	    print_summary();
//	    qh_produce_output();  /* delete this line to help avoid io.c */
//	    if (qh VERIFYoutput && !qh FORCEoutput && !qh STOPadd && !qh STOPcone && !qh STOPpoint)
//	      qh_check_points();
//	    fflush(NULL);
//	  qh NOerrexit= True;
//	#ifdef qh_NOmem
//	  qh_freeqhull(qh_ALL);
//	#else
//	  qh_freeqhull(!qh_ALL);
//	  qh_memfreeshort(&curlong, &totlong);
//	  if (curlong || totlong)
//	    fprintf(stderr, "qhull warning (user_eg2, run 1): did not free %d bytes of long memory (%d pieces)\n",
//	          totlong, curlong);
//	#endif
		return null;
	}

	private int getLargestDim() {
		int maxDim = 0;
		for(PointND p : this) {
			if(maxDim < p.getDim()) {
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
