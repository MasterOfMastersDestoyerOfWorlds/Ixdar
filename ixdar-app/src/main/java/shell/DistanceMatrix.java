package shell;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.linear.Array2DRowFieldMatrix;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.DecompositionSolver;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.FieldMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import shell.point.PointND;

/**
 * A class that represents the distances between all points in the pointset
 */
public class DistanceMatrix {

    private double[][] matrix;
    private ArrayList<PointND> points;
    private HashMap<Integer, Integer> lookup;
    private double maxDist = 0;
    private double minDist = java.lang.Double.MAX_VALUE;
    private double zero = 0;
    private PointND.Double centroid;
    private double[] centroidDist;
    private PointND.Double nSphereCenter;
    private double nSphereRadius = -1;

    public PointND.Double getnSphereCenter() {
        return nSphereCenter;
    }

    public void setnSphereCenter(PointND.Double nSphereCenter) {
        this.nSphereCenter = nSphereCenter;
    }

    public double getnSphereRadius() {
        return nSphereRadius;
    }

    public void setnSphereRadius(double nSphereRadius) {
        this.nSphereRadius = nSphereRadius;
    }

    /**
     * Creates a distance matrix that represents the distance between every point in
     * the pointset
     * 
     * @param pointset
     */
    public DistanceMatrix(PointSet pointset) {
        matrix = new double[pointset.size()][pointset.size()];
        points = new ArrayList<PointND>();
        lookup = new HashMap<Integer, Integer>();
        for (PointND p : pointset) {
            points.add(p);
        }
        for (int i = 0; i < matrix.length; i++) {
            lookup.put(points.get(i).getID(), i);
            for (int j = 0; j < matrix.length; j++) {
                double dist = points.get(i).distance(points.get(j));
                if (dist > maxDist) {
                    maxDist = dist;
                }
                if (dist < minDist) {
                    minDist = dist;
                }
                matrix[i][j] = dist;
            }
        }
    }

    public DistanceMatrix(PointSet ps, DistanceMatrix d) {
        matrix = new double[ps.size()][ps.size()];
        points = new ArrayList<PointND>();
        lookup = new HashMap<Integer, Integer>();
        this.zero = d.zero;
        this.maxDist = d.maxDist;
        for (PointND p : ps) {
            points.add(p);
        }
        for (int i = 0; i < matrix.length; i++) {
            lookup.put(points.get(i).getID(), i);
            for (int j = 0; j < matrix.length; j++) {
                double dist = d.getDistance(points.get(i), points.get(j));
                matrix[i][j] = dist;
            }
        }
    }

    /**
     * Gets the points stored in the distance matrix
     * 
     * @return the points stored in the distance matrix
     */
    public ArrayList<PointND> getPoints() {
        return points;
    }

    /**
     * Gets the maximum distance between any two points in the distance matrix
     * 
     * @return the maximum distance between any two points in the distance matrix
     */
    public double getMaxDist() {
        return maxDist;
    }

    public void updateMaxDist() {
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix.length; j++) {
                double dist = this.getDistance(points.get(i), points.get(j));
                if (dist > maxDist) {
                    maxDist = dist;
                }
                if (dist < minDist) {
                    minDist = dist;
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

    /**
     * Gets the distance between i and j
     * 
     * @return the distance
     */
    public double getDistance(PointND i, PointND j) {

        return matrix[lookup.get(i.getID())][lookup.get(j.getID())];
    }

    public double sumDistances(PointND p) {
        double sum = 0.0;
        int i = lookup.get(p.getID());
        for (int j = 0; j < matrix.length; j++) {
            sum += matrix[i][j];
        }
        return sum;
    }

    public double sumAngles(PointND p) {
        double sum = 0.0;
        int i = lookup.get(p.getID());
        for (int j = 0; j < matrix.length; j++) {
            if (i != j) {
                sum += matrix[lookup.get(p.getID())][j];
            }

        }
        return sum;
    }

    /**
     * Adds a dummy node to the matrix with infinite distance to all points except
     * start and end which is zero distance away
     * 
     * @param start
     * @param end
     * @return A new distance matrix with the dummy node added on
     */
    public PointND addDummyNode(int ID, PointND first, PointND last) {
        double[][] temp = new double[matrix.length + 1][matrix.length + 1];
        int startIndex = points.indexOf(first);
        int endIndex = points.indexOf(last);
        // \\maxDist = 2*maxDist;
        for (int i = 0; i < temp.length; i++) {
            for (int j = 0; j < temp.length; j++) {
                if (i == matrix.length || j == matrix.length) {
                    if (i == startIndex || j == startIndex || i == endIndex || j == endIndex) {
                        temp[i][j] = zero;
                    } else if (i == j) {
                        temp[i][j] = 0;
                    } else {
                        temp[i][j] = maxDist;
                    }
                } else {
                    temp[i][j] = matrix[i][j];
                }
            }
        }
        zero = 2 * maxDist;
        for (int i = 0; i < temp.length; i++) {
            for (int j = 0; j < temp.length; j++) {
                if (i != j) {
                    temp[i][j] = temp[i][j] + zero;
                }
            }
        }
        maxDist = maxDist + zero;

        PointND dummy = new PointND.Double();
        if (ID >= 0) {
            dummy.setID(ID);
        }
        dummy.setDummyNode();
        points.add(dummy);

        lookup.put(dummy.getID(), points.size() - 1);
        matrix = temp;
        return dummy;
    }

    public EigenDecomposition getEigenvalues() {
        RealMatrix E = new Array2DRowRealMatrix(matrix);
        return new EigenDecomposition(E);
    }

    /**
     * 
     * Triangulates the distance matrix and returns a set of points that have at
     * least 2n dimensions to account for imaginary numbers where n is the number of
     * points in the matrix
     * 
     * @return A Knot Set triangulated from the distance matrix that follows the
     *         triangle property
     */
    public PointSet toPointSet() {

        double[][] D = new double[matrix.length][matrix.length];
        // addint the max distance so the matrix follows the triangle property
        // https://cstheory.stackexchange.com/questions/12885/guidelines-to-reduce-general-tsp-to-triangle-tsp/14049#14049
        for (int i = 0; i < D.length; i++) {
            for (int j = 0; j < D.length; j++) {
                if (i != j) {
                    D[i][j] = matrix[i][j];
                }
            }
        }

        double[][] M = new double[D.length][D.length];
        // https://math.stackexchange.com/questions/156161/finding-the-coordinates-of-points-from-distance-matrix
        for (int i = 0; i < M.length; i++) {
            for (int j = 0; j < M.length; j++) {
                M[i][j] = (Math.pow(D[0][j], 2) + Math.pow(D[i][0], 2) - Math.pow(D[i][j], 2)) / 2;
            }
        }

        // do SVD for M
        RealMatrix E = new Array2DRowRealMatrix(M);
        // find the eigen values of U
        EigenDecomposition SVD = new EigenDecomposition(E);
        double[] realE = reverseArray(SVD.getRealEigenvalues());
        double[] imagE = reverseArray(SVD.getImagEigenvalues());
        int numE = realE.length;

        // put into a diagonal matrix
        Complex[][] diag = new Complex[numE][numE];
        for (int i = 0; i < numE; i++) {
            for (int j = 0; j < numE; j++) {
                if (i == j) {
                    diag[i][j] = new Complex(realE[i], imagE[j]).sqrt();
                } else {
                    diag[i][j] = new Complex(0, 0);
                }
            }
        }

        FieldMatrix<Complex> S = new Array2DRowFieldMatrix<Complex>(diag);
        FieldMatrix<Complex> V = flipMatrix(realToImag(SVD.getV()));
        FieldMatrix<Complex> X = V.multiply(S);
        // Convert to 2n space
        PointSet ps = new PointSet();
        for (int i = 0; i < X.getRowDimension(); i++) {

            Complex[] row = X.getRow(i);
            double[] coords = new double[row.length * 2];
            for (int j = 0; j < row.length; j++) {
                coords[j] = row[j].getReal();
                coords[row.length + j] = row[j].getImaginary();
            }
            if (i < this.getPoints().size()) {
                ps.add(new PointND.Double(this.getPoints().get(i).getID(), coords));
            } else {
                ps.add(new PointND.Double(coords));
            }
        }
        this.checkPointSet(ps);

        return ps;

    }

    private void checkPointSet(PointSet ps) {

        for (PointND p : ps) {
            for (PointND p2 : ps) {
                if (!p.isCentroid() && !p.equals(p2)) {
                    double trueDist = this.getDistance(p, p2);
                    double converted = p.distance(p2);
                    assert (Math.abs(converted - trueDist) < 0.0001)
                            : "Expected: " + trueDist + " got: " + converted + "\n " + this;
                }
            }
        }
    }

    /**
     * Transforms the DistanceMatrix to a PointSet and then averages over the
     * cooridnates to find the centroid Adds the centroid to the distance matrix
     * 
     * @return the centroid of the points represented in the distance matrix
     */
    public PointND findCentroid() {
        PointSet ps = this.toPointSet();

        return findCentroid(ps);
    }

    public PointND findCentroid(PointSet ps) {
        this.centroid = new PointND.Double(ps);
        this.centroid.setCentroid();
        this.centroidDist = new double[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            int index = lookup.get(ps.get(i).getID());
            centroidDist[index] = ps.get(i).distance(centroid);
            // System.out.println( ps.get(i).getID()+ " Dist to centroid: " +
            // centroidDist[index]);
        }

        return centroid;
    }

    public PointND findNSphereCenter() {
        PointSet ps = this.toPointSet();
        return findNSphereCenter(ps);

    }

    public PointND findNSphereCenter(PointSet ps) {
        int numPoints = ps.size();
        int dim = ps.getMaxDim();

        // https://stackoverflow.com/questions/37449046/how-to-calculate-the-sphere-center-with-4-points
        // https://stackoverflow.com/questions/72230142/how-to-find-the-center-and-radius-of-an-any-dimensional-sphere-giving-dims1-poi

        double[][] M = new double[numPoints][dim];
        double[] v = new double[numPoints];
        for (int i = 0; i < numPoints; i++) {
            for (int j = 1; j < dim; j++) {
                M[i][j - 1] = ps.get(i).getCoord(j);
                v[i] += Math.pow(ps.get(i).getCoord(j), 2);
            }
        }
        RealMatrix A = new Array2DRowRealMatrix(M);

        for (int j = 0; j < numPoints; j++) {
            M[numPoints - 1][j] = 1;
        }

        SingularValueDecomposition svd = new SingularValueDecomposition(A);
        DecompositionSolver ds = svd.getSolver();
        RealVector b = new ArrayRealVector(v);
        RealVector x = ds.solve(b);

        double[] ans = new double[numPoints];

        for (int i = 1; i < numPoints; i++) {

            ans[i] = x.getEntry(i - 1) / 2;
        }

        this.nSphereCenter = new PointND.Double(ans);
        this.nSphereCenter.setNSphereCenter();
        this.nSphereRadius = this.nSphereCenter.distance(ps.get(0));

        for (PointND p : ps) {
            assert Math.abs(p.distance(nSphereCenter) - nSphereRadius) < 0.0001;
        }

        return this.nSphereCenter;
    }

    /**
     * converts a RealMatrix to a FieldMatrix over the Complex Field
     * 
     * @param M the real matrix to convert
     * @return M in the complex field
     */
    private static FieldMatrix<Complex> realToImag(RealMatrix M) {
        Complex[][] C = new Complex[M.getRowDimension()][M.getColumnDimension()];
        for (int i = 0; i < M.getRowDimension(); i++) {
            for (int j = 0; j < M.getColumnDimension(); j++) {
                C[i][j] = new Complex(M.getEntry(i, j), 0);
            }
        }
        return new Array2DRowFieldMatrix<Complex>(C);

    }

    /**
     * Flips a matrix horizontally by multiplying by I flipped horizontally
     * 
     * @param C the matrix to flip
     * @return the matrix flipped horizontally
     */
    private static FieldMatrix<Complex> flipMatrix(FieldMatrix<Complex> C) {
        Complex[][] result = new Complex[C.getRowDimension()][C.getColumnDimension()];
        for (int i = 0; i < C.getRowDimension(); i++) {
            for (int j = 0; j < C.getColumnDimension(); j++) {
                if (j == C.getColumnDimension() - 1 - i) {
                    result[i][j] = new Complex(1, 0);
                } else {
                    result[i][j] = new Complex(0, 0);
                }
            }
        }
        return C.multiply(new Array2DRowFieldMatrix<Complex>(result));
    }

    /**
     * reverses an array of doubles so that element 0 is now last etc.
     * 
     * @param array to reverse
     * @return the array reversed
     */
    private static double[] reverseArray(double[] array) {
        double[] result = new double[array.length];
        for (int i = 0; i < array.length; i++) {
            result[i] = array[array.length - 1 - i];
        }
        return result;
    }

    @Override
    public String toString() {
        String str = "DistanceMatrix[\n";
        for (int i = 0; i < matrix.length; i++) {
            if (points.get(i).isDummyNode()) {
                str += "*";
            } else {
                str += "" + points.get(i).getID();
            }

            str += "[";
            for (int j = 0; j < matrix.length; j++) {
                double v = matrix[i][j];
                // Use plain fixed-point formatting to avoid DecimalFormat pattern issues under
                // TeaVM
                str += " " + String.format(java.util.Locale.ROOT, "%.7f", v) + " ";
            }
            str += "]\n";
        }
        str += "]";
        return str;
    }

    public double getZero() {
        return this.zero;
    }

    public int size() {
        return matrix.length;
    }

    public double getSmallestSegmentLength() {
        double minLength = Double.MAX_VALUE;
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix.length; j++) {
                if (i != j) {
                    double matrixValue = matrix[i][j];
                    if (matrixValue < minLength) {
                        minLength = matrixValue;
                    }
                }
            }
        }
        return minLength;

    }

    public double getLargestSegmentLength() {
        double maxLength = Double.MIN_VALUE;
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix.length; j++) {
                double matrixValue = matrix[i][j];
                if (matrixValue > maxLength) {
                    maxLength = matrixValue;
                }
            }
        }
        return maxLength;

    }

}
