package shell.file;

import java.awt.geom.Path2D;
import java.util.ArrayList;

import shell.DistanceMatrix;
import shell.PointSet;
import shell.point.Grid;
import shell.shell.Shell;

/**
 * The optimal tsp path in a pointset ps
 */
public class PointSetPath {
	public PointSet ps;
	public Path2D path;
	public Shell tsp;
	public DistanceMatrix d;
	public ArrayList<String> comments;
	public Grid grid;

	/**
	 * Initializes the path and pointset variables
	 * 
	 * @param comments
	 */
	public PointSetPath(PointSet ps, Path2D path, Shell tsp, DistanceMatrix d, 
			ArrayList<String> comments, Grid grid) {
		this.path = path;
		this.ps = ps;
		this.tsp = tsp;
		this.d = d;
		this.comments = comments;
		this.grid = grid;
	}

}
