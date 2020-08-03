package shellCopy;

import java.util.ArrayList;

/**
 * A class that represents the distances between all points in the pointset
 */
public class DistanceMatrix {

    private double[][] matrix;
    private ArrayList<PointND> points;
    private double maxDist = 0;

    /**
     * Creates a distance matrix that represents the distance between every point in the pointset
     * @param pointset
     */
    /*public DistanceMatrix(PointSet pointset){
        matrix = new double[pointset.size()][pointset.size()];
        for(PointND p : pointset){
            points.add(p);
        }
        for(int i = 0; i < matrix.length; i++){
            for(int j = 0; j < matrix.length; j++){
                double dist = points.get(i).distance(points.get(j));
                if(dist > maxDist){
                    maxDist = dist;
                }
                matrix[i][j] = dist;
            }
        }
    }*/

    /**
     * Creates a new distance matrix given a 2d array of values and a list of points
     * @param matrix
     * @param points
     */
    public DistanceMatrix(double[][] matrix, ArrayList<PointND> points){
        this.matrix = matrix;
        this.points = points;
    }

    /**
     * Adds a dummy node to the matrix with infinite distance to all points except start and end which is zero distance away
     * @param start
     * @param end
     * @return A new distance matrix with the dummy node added on
     */
    public DistanceMatrix addDummyNode(PointND start, PointND end) {
        double[][] temp = new double[matrix.length + 1][matrix.length + 1];
        int startIndex = points.indexOf(start);
        int endIndex = points.indexOf(end);
        for(int i = 0; i < temp.length; i++){
            for(int j = 0; j < temp.length; j++){
                if(i == matrix.length || j == matrix.length){
                    if(i == startIndex || j == startIndex || i == endIndex || j == endIndex){
                        temp[i][j] = 0;
                    }else{
                        temp[i][j] = Double.MAX_VALUE;
                    }
                }else{
                    temp[i][j] = matrix[i][j];
                }
            }
        }
        return new DistanceMatrix(temp, points);
    }


}
