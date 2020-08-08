package shell;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.linear.Array2DRowFieldMatrix;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.FieldMatrix;
import org.apache.commons.math3.linear.RealMatrix;

/**
 * A class that represents the distances between all points in the pointset
 */
public class DistanceMatrix {

	private double[][] matrix;
	private ArrayList<PointND> points;
	private double maxDist = 0;

	/**
	 * Creates a distance matrix that represents the distance between every point in
	 * the pointset
	 * 
	 * @param pointset
	 */
	public DistanceMatrix(PointSet pointset) {
		matrix = new double[pointset.size()][pointset.size()];
		points = new ArrayList<PointND>();
		for (PointND p : pointset) {
			points.add(p);
		}
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix.length; j++) {
				double dist = points.get(i).distance(points.get(j));
				if (dist > maxDist) {
					maxDist = dist;
				}
				matrix[i][j] = dist;
			}
		}
	}

	/**
	 * Creates a new distance matrix given a 2d array of values and a list of points
	 * 
	 * @param matrix
	 * @param points
	 */
	public DistanceMatrix(double[][] matrix, ArrayList<PointND> points) {
		this.matrix = matrix;
		this.points = points;
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix.length; j++) {
				double dist = matrix[i][j];
				if (dist > maxDist) {
					maxDist = dist;
				}
			}
		}
	}

	/**
	 * Gets the points stored in the distance matrix
	 * @return the points stored in the distance matrix
	 */
	public ArrayList<PointND> getPoints() {
		return points;
	}

	/**
	 * Gets the maximum distance between any two points in the distance matrix
	 * @return the maximum distance between any two points in the distance matrix
	 */
	public double getMaxDist() {
		return maxDist;
	}

	/**
	 * Gets the distance matrix
	 * @return the distance matrix
	 */
	public double[][] getMatrix() {
		return matrix;
	}

	/**
	 * Adds a dummy node to the matrix with infinite distance to all points except
	 * start and end which is zero distance away
	 * 
	 * @param start
	 * @param end
	 * @return A new distance matrix with the dummy node added on
	 */
	public DistanceMatrix addDummyNode(PointND start, PointND end) {
		double[][] temp = new double[matrix.length + 1][matrix.length + 1];
		int startIndex = points.indexOf(start);
		int endIndex = points.indexOf(end);
		for (int i = 0; i < temp.length; i++) {
			for (int j = 0; j < temp.length; j++) {
				if (i == matrix.length || j == matrix.length) {
					if (i == startIndex || j == startIndex || i == endIndex || j == endIndex || i==j) {
						temp[i][j] = 0;
					} else {
						temp[i][j] = maxDist;
					}
				} else {
					temp[i][j] = matrix[i][j];
				}
			}
		}
		return new DistanceMatrix(temp, points);
	}

	/**
	 * 
	 * Triangulates the distance matrix and returns a set of points that have at least 2n dimensions to account for imaginary numbers where n 
	 * is the number of points in the matrix
	 * 
	 * @return A Point Set triangulated from the distance matrix that follows the triangle property
	 */
	public PointSet toPointSet() {
		
		double[][] D = new double[matrix.length][matrix.length];
		//addint the max distance so the matrix follows the triangle property
		for (int i = 0; i < D.length; i++) {
			for (int j = 0; j < D.length; j++) {
				if (i != j) {
					D[i][j] = matrix[i][j] + 2 * maxDist;
				}
			}
		}
		
		
		double[][] M = new double[D.length][D.length];
		// idk whats happening here
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
			if(i < this.getPoints().size()) {
				ps.add(new PointND.Double(this.getPoints().get(i).getID(), coords));
			}
			else {
				ps.add(new PointND.Double(-2, coords));
			}
		}

		return ps;

	}
	
	/**
	 * converts a RealMatrix to a FieldMatrix over the Complex Field
	 * @param M the real matrix to convert
	 * @return M in the complex field
	 */
	private static FieldMatrix<Complex> realToImag(RealMatrix M){
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
	 * @param C the matrix to flip
	 * @return the matrix flipped horizontally
	 */
	private static FieldMatrix<Complex> flipMatrix(FieldMatrix<Complex> C) {
		Complex[][] result = new Complex[C.getRowDimension()][C.getColumnDimension()];
		for(int i=0; i < C.getRowDimension(); i ++) {
			for(int j=0; j < C.getColumnDimension(); j ++) {
				if(j == C.getColumnDimension()-1 - i) {
					result[i][j] = new Complex(1,0);
				}else {
					result[i][j] = new Complex(0,0);
				}
			}
		}
		return C.multiply(new Array2DRowFieldMatrix<Complex>(result));
	}
	
	/**
	 * reverses an array of doubles so that element 0 is now last etc.
	 * @param array to reverse
	 * @return the array reversed
	 */
	private static double[] reverseArray(double[] array) {
		double[] result = new double[array.length];
		for(int i=0; i < array.length; i ++) {
			result[i] = array[array.length-1-i];
		}
		return result;
	}
	
	@Override
	public String toString() {
		String str = "DistanceMatrix[\n";
        for(int i = 0; i < matrix.length; i ++) {
        	str += "[";
        	for(int j = 0; j < matrix.length; j++) {
        		BigDecimal bd = new BigDecimal(matrix[i][j]);
        		bd = bd.round(new MathContext(3));
        		str+= " " + Double.valueOf(String.format("%."+3+"G", bd)) + " ";
        	}
        	str+="]\n";
        }
        str += "]";
		return str;
	}

}
