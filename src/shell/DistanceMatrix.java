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

	public DistanceMatrix(double[][] matrix) {
		this.matrix = matrix;
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix.length; j++) {
				double dist = matrix[i][j];
				if (dist > maxDist) {
					maxDist = dist;
				}
			}
		}
	}

	public ArrayList<PointND> getPoints() {
		return points;
	}

	public double getMaxDist() {
		return maxDist;
	}
	
	public double[][] getMatrix() {
		// TODO Auto-generated method stub
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
						temp[i][j] = 1;
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
				M[i][j] = (Math.pow(D[1][j], 2) + Math.pow(D[i][1], 2) - Math.pow(D[i][j], 2)) / 2;
			}
		}
		
		// do SVD for M
		Complex[][] UValues = new Complex[M.length][M.length];
		RealMatrix E = new Array2DRowRealMatrix(M);
		
		
		// find the eigen values of U
		EigenDecomposition SVD = new EigenDecomposition(E);
		double[] realE = SVD.getRealEigenvalues();
		double[] imagE = SVD.getImagEigenvalues();
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
		FieldMatrix<Complex> U = realToImag(SVD.getV());
		FieldMatrix<Complex> X = U.multiply(S);

		// Convert to 2n space
		PointSet ps = new PointSet();
		for (int i = 0; i < X.getRowDimension(); i++) {

			Complex[] row = X.getRow(i);
			double[] coords = new double[row.length * 2];
			for (int j = 0; j < row.length; j++) {
				coords[j] = row[j].getReal();
				coords[row.length + j] = row[j].getImaginary();
			}
			ps.add(new PointND.Double(coords));
		}

		return ps;

	}
	
	private static FieldMatrix<Complex> realToImag(RealMatrix M){
		Complex[][] C = new Complex[M.getRowDimension()][M.getColumnDimension()];
		for (int i = 0; i < M.getRowDimension(); i++) {
			for (int j = 0; j < M.getColumnDimension(); j++) {
				C[i][j] = new Complex(M.getEntry(i, j), 0);
			}
		}
		return new Array2DRowFieldMatrix<Complex>(C);
		
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
