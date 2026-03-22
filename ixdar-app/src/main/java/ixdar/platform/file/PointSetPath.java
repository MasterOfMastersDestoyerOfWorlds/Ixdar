package ixdar.platform.file;

import java.util.ArrayList;

import ixdar.geometry.point.Grid;
import ixdar.geometry.point.PointSet;
import ixdar.geometry.shell.DistanceMatrix;
import ixdar.geometry.shell.Shell;

/**
 * The optimal tsp path in a pointset ps
 */
public class PointSetPath {
    public PointSet ps;
    public Shell tsp;
    public DistanceMatrix d;
    public ArrayList<String> comments;
    public Grid grid;

    /**
     * Initializes the path and pointset variables
     * 
     * @param comments
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
