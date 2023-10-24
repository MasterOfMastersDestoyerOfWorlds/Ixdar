package shell;

import java.awt.geom.Path2D;

/**
 * The optimal tsp path in a pointset ps
 */
public class PointSetPath {
	public PointSet ps;
	public Path2D path;
	public Shell tsp;

	/**
	 * Initializes the path and pointset variables
	 * @param ps
	 * @param path
	 */
	public PointSetPath(PointSet ps, Path2D path, Shell tsp) {
		this.path = path;
		this.ps = ps;
		this.tsp = tsp;
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
