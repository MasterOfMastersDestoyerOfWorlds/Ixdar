package ixdar.platform.file;

import java.util.ArrayList;

import ixdar.geometry.point.Grid;
import ixdar.geometry.point.PointSet;
import ixdar.geometry.shell.DistanceMatrix;
import ixdar.geometry.shell.Shell;

/**
 * The optimal tsp path in a pointset ps.
 */
public class PointSetPath {
    public PointSet ps;
    public Shell tsp;
    public DistanceMatrix d;
    public ArrayList<String> comments;
    public Grid grid;

    /**
     * Initializes the path and pointset variables.
     *
     * @param ps points read from the file
     * @param tsp ordered tour through {@code ps} (the answer / TSP path)
     * @param d cached distance matrix, or null if none was loaded
     * @param comments {@code //}-prefixed comment lines preserved from the source file
     * @param grid grid (cartesian / hex) the points belong to
     */
    public PointSetPath(PointSet ps, Shell tsp, DistanceMatrix d,
            ArrayList<String> comments, Grid grid) {
        this.ps = ps;
        this.tsp = tsp;
        this.d = d;
        this.comments = comments;
        this.grid = grid;
    }

}
