package shell.cuts;

import java.util.ArrayList;

import shell.knot.Knot;
import shell.knot.Segment;

/**
 * A class that represents the distances between all points in the pointset
 */
public class CutMatchDistanceMatrix {

    public double[][] matrix;
    public ArrayList<ArrayList<Edge>> negativeEdges;
    public ArrayList<ArrayList<Edge>> positiveEdges;

    /**
     * Creates a distance matrix that represents the distance between every point in
     * the pointset
     * 
     * @param pointset
     */
    public CutMatchDistanceMatrix(Knot k) {
        int size = k.size();
        matrix = new double[size][2 * size];
        negativeEdges = new ArrayList<ArrayList<Edge>>(size);
        positiveEdges = new ArrayList<ArrayList<Edge>>(size);
        for (int i = 0; i < size; i++) {
            Knot vp = k.knotPointsFlattened.get(i);
            ArrayList<Edge> negativeEdgesI = new ArrayList<>();
            ArrayList<Edge> positiveEdgesI = new ArrayList<>();
            negativeEdges.add(i, negativeEdgesI);
            positiveEdges.add(i, positiveEdgesI);
            for (int j = 0; j < size; j++) {
                int prevJ = j == 0 ? size - 1 : j - 1;
                int nextJ = j == size - 1 ? 0 : j + 1;
                Knot vp2 = k.knotPointsFlattened.get(j);
                Knot prevNeighbor = k.knotPointsFlattened.get(prevJ);
                Knot nextNeighbor = k.knotPointsFlattened.get(nextJ);
                if (i != j) {
                    if (i != prevJ) {
                        Segment acrossSegment = vp.getSegment(prevNeighbor);
                        Segment cutSegment = vp2.getSegment(prevNeighbor);
                        double distance = acrossSegment.distance - cutSegment.distance;
                        matrix[i][2 * j] = distance;
                        if (distance < 0) {
                            negativeEdgesI.add(new Edge(j, false, acrossSegment, cutSegment));
                        } else {
                            positiveEdgesI.add(new Edge(j, false, acrossSegment, cutSegment));
                        }
                    }
                    if (i != nextJ) {
                        Segment acrossSegment = vp.getSegment(nextNeighbor);
                        Segment cutSegment = vp2.getSegment(nextNeighbor);

                        double distance = acrossSegment.distance - cutSegment.distance;
                        matrix[i][2 * j + 1] = distance;
                        if (distance < 0) {
                            negativeEdgesI.add(new Edge(j, true, acrossSegment, cutSegment));
                        } else {
                            positiveEdgesI.add(new Edge(j, true, acrossSegment, cutSegment));
                        }
                    }
                }
            }
        }
    }

    /**
     * Gets the distance matrix
     * 
     * @return the distance matrix
     */
    public double[][] getMatrix() {
        return matrix;
    }

    public int size() {
        return matrix.length;
    }

}
