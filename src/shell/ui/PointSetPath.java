package shell.ui;

import java.awt.geom.Path2D;

import shell.DistanceMatrix;
import shell.PointSet;
import shell.shell.Shell;

/**
 * The optimal tsp path in a pointset ps
 */
public class PointSetPath {
	public PointSet ps;
	public Path2D path;
	public Shell tsp;
	public DistanceMatrix d;
	public boolean manifold;
	public int kp1;
	public int cp1;
	public int kp2;
	public int cp2;


	/**
	 * Initializes the path and pointset variables
	 */
	public PointSetPath(PointSet ps, Path2D path, Shell tsp, DistanceMatrix d, boolean manifold, int kp1, int cp1, int kp2, int cp2) {
		this.path = path;
		this.ps = ps;
		this.tsp = tsp;
		this.d = d;
		this.manifold = manifold;
		this.kp1 = kp1;
		this.cp1 = cp1;
		this.kp2 = kp2;
		this.cp2 = cp2;
	}

	/* All of this is currently unused but keeping it for history and future use purposes
	public PointSet getPs() {
		return ps;
	}
	public void setPs(PointSet ps) {
		this.ps = ps;
	}
	public Path2D getPath() {
		return path;
	}
	public void setPath(GeneralPath path) {
		this.path = path;
	}
	*/
}
