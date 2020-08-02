package shellCopy;

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
	}

	public DistanceMatrix(int length) {
		// TODO Auto-generated constructor stub
	}

	public DistanceMatrix(int length, ArrayList<PointND> points2) {
		// TODO Auto-generated constructor stub
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
					if (i == startIndex || j == startIndex || i == endIndex || j == endIndex) {
						temp[i][j] = 0;
					} else {
						temp[i][j] = Double.MAX_VALUE;
					}
				} else {
					temp[i][j] = matrix[i][j];
				}
			}
		}
		return new DistanceMatrix(temp, points);
	}

	public PointSet toPointSet() {
		for(int i = 0; i < matrix.length; i++) {
			for(int j = 0; j < matrix.length; j++) {
				if(i != j){
					matrix[i][j] = matrix[i][j] + 2 * maxDist;
		    	}
			}
		}
    	  
    	  //Approach 1 (sqrtm)
    	  double[][] M = new double[matrix.length][matrix.length];
    	  //idk whats happening here
    	  for(int i = 0; i < matrix.length; i++) {
    	      for(int j = 0; j < matrix.length; j++) {
    	          matrix[i][j] = (Math.pow(matrix[1][j], 2) + Math.pow(matrix[i][1], 2) - Math.pow(matrix[i][j], 2)) / 2;
    	      }
    	  }
    	  
    	  matrix = M;
    	  //do SVD for M
    	  Complex[][] UValues = new Complex[matrix.length][matrix.length];
    	  //M * MTranspose = U 
    	  for(int i = 0; i < matrix.length; i++) {
    	      for(int j = 0; j < matrix.length; j++) {
    	    	  double val = 0;
    	    	  for(int k = 0; k <matrix.length; k ++) {
    	    		  val += matrix[i][k] * matrix[k][j];
    	    	  }
    	          UValues[i][j] = new Complex(val, 0);
    	      }
    	  }
    	  RealMatrix E = new Array2DRowRealMatrix(matrix);
    	  EigenDecomposition SVD = new EigenDecomposition(E);
    	  //find the eigen values of U
    	  double[] realE = SVD.getRealEigenvalues();
    	  double[] imagE = SVD.getImagEigenvalues();
    	  int numE = realE.length;
    	  //put into a diagonal matrix
    	  Complex[][] diag = new Complex[numE][numE];
    	  for(int i = 0; i < numE; i++) {
    		  for(int j = 0; j < numE; j++) {
    			  if(i == j) {
    				  diag[i][j] = new Complex(realE[i], imagE[j]).sqrt();
    			  }
    			  else {
    				  diag[i][j] = new Complex(0,0);
    			  }
    		  }
    	  }
    	  FieldMatrix<Complex> S = new Array2DRowFieldMatrix<Complex>(diag);

    	  FieldMatrix<Complex> U = new Array2DRowFieldMatrix<Complex>(UValues);
    			 
    	  FieldMatrix<Complex> X = U.multiply(S);
    	         

    	//Convert to 2n space
    	  

  		return null;
    	 
    }

}
