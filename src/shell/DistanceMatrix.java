package shell;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;

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
	private HashMap<Integer,Integer> lookup;
	private double maxDist = 0;
	private double minDist = java.lang.Double.MAX_VALUE;
	private double zero = 0;
	private PointND.Double centroid;
	private double[] centroidDist;

	/**
	 * Creates a distance matrix that represents the distance between every point in
	 * the pointset
	 * 
	 * @param pointset
	 */
	public DistanceMatrix(PointSet pointset) {
		matrix = new double[pointset.size()][pointset.size()];
		points = new ArrayList<PointND>();
		lookup = new HashMap<Integer,Integer>();
		for (PointND p : pointset) {
			points.add(p);
		}
		for (int i = 0; i < matrix.length; i++) {
			lookup.put(points.get(i).getID(),i);
			for (int j = 0; j < matrix.length; j++) {
				double dist = points.get(i).distance(points.get(j));
				if (dist > maxDist) {
					maxDist = dist;
				}
				if(dist < minDist) {
					minDist = dist;
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
		lookup = new HashMap<Integer,Integer>();
		for (int i = 0; i < matrix.length; i++) {
			lookup.put(points.get(i).getID(),i);
			for (int j = 0; j < matrix.length; j++) {
				double dist = matrix[i][j];
				if (dist > maxDist) {
					maxDist = dist;
				}
				if(dist < minDist) {
					minDist = dist;
				}
			}
		}
	}

	public DistanceMatrix(PointSet ps, DistanceMatrix d) {
		matrix = new double[ps.size()][ps.size()];
		points = new ArrayList<PointND>();
		lookup = new HashMap<Integer,Integer>();
		this.zero = d.zero;
		this.maxDist = d.maxDist;
		for (PointND p : ps) {
			points.add(p);
		}
		for (int i = 0; i < matrix.length; i++) {
			lookup.put(points.get(i).getID(),i);
			for (int j = 0; j < matrix.length; j++) {
				double dist = d.getDistance(points.get(i), points.get(j));
				matrix[i][j] = dist;
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
	
	public void updateMaxDist() {
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix.length; j++) {
				double dist = this.getDistance(points.get(i), points.get(j));
				if (dist > maxDist) {
					maxDist = dist;
				}
				if(dist < minDist) {
					minDist = dist;
				}
			}
		}
	}

	/**
	 * Gets the distance matrix
	 * @return the distance matrix
	 */
	public double[][] getMatrix() {
		return matrix;
	}
	
	/**
	 * Gets the distance between i and j
	 * @return the distance 
	 */
	public double getDistance(int i, int j) {
		return matrix[i][j];
	}
	/**
	 * Gets the distance between i and j
	 * @return the distance 
	 */
	public double getDistance(PointND i, PointND j) {
		
		if(i.isCentroid() || j.isCentroid()) {
			return i.isCentroid() ? centroidDist[lookup.get(j.getID())] : centroidDist[lookup.get(i.getID())];
		}
		return matrix[lookup.get(i.getID())][lookup.get(j.getID())];
	}

	public double sumDistances(PointND p) {
		double sum = 0.0;
		int i = lookup.get(p.getID());
		for(int j = 0; j < matrix.length; j ++) {
			sum += matrix[i][j];
		}
		return sum;
	}
	public double sumAngles(PointND p) {
		double sum = 0.0;
		int i = lookup.get(p.getID());
		for(int j = 0; j < matrix.length; j ++) {
			if(i != j) {
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
	public PointND addDummyNode(Segment s) {
		double[][] temp = new double[matrix.length + 1][matrix.length + 1];
		int startIndex = points.indexOf(s.first);
		int endIndex = points.indexOf(s.last);
		//\\maxDist = 2*maxDist;
		for (int i = 0; i < temp.length; i++) {
			for (int j = 0; j < temp.length; j++) {
				if (i == matrix.length || j == matrix.length) {
					if (i == startIndex || j == startIndex || i == endIndex || j == endIndex) {
						temp[i][j] = zero;
					}
					else if (i==j) {
						temp[i][j] = 0;
					}
					else {
						temp[i][j] = maxDist;
					}
				} else {
					temp[i][j] = matrix[i][j];
				}
			}
		}
		zero = 2*maxDist;
		for (int i = 0; i < temp.length; i++) {
			for (int j = 0; j < temp.length; j++) {
				if (i != j) {
					temp[i][j] = temp[i][j] + zero;
				}
			}
		}
		maxDist = maxDist + zero;
		

		
		PointND dummy = new PointND.Double();
		dummy.setDummyNode();
		dummy.setDummyParents(s);
		points.add(dummy);

		lookup.put(dummy.getID(),points.size()-1);
		matrix = temp;
		return dummy;
	}
	
	public EigenDecomposition getEigenvalues() {
		RealMatrix E = new Array2DRowRealMatrix(matrix);
		return new EigenDecomposition(E);
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
		//https://cstheory.stackexchange.com/questions/12885/guidelines-to-reduce-general-tsp-to-triangle-tsp/14049#14049
		for (int i = 0; i < D.length; i++) {
			for (int j = 0; j < D.length; j++) {
				if (i != j) {
					D[i][j] = matrix[i][j];
				}
			}
		}
		
		
		double[][] M = new double[D.length][D.length];
		//https://math.stackexchange.com/questions/156161/finding-the-coordinates-of-points-from-distance-matrix
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
				ps.add(new PointND.Double(coords));
			}
		}

		return ps;

	}
	
	private void checkPointSet(PointSet ps) {

		for(PointND p : ps) {
			for(PointND p2 : ps) {
				if(!p.isCentroid() && !p.equals(p2)) {
					double  trueDist = this.getDistance(p, p2);
					double converted = p.distance(p2);
					assert( Math.abs(converted - trueDist) < 0.0001): "Expected: " + trueDist + " got: " + converted  + "\n " + this;
				}
			}
		}
	}
	
	/**
	 * Transforms the DistanceMatrix to a PointSet and then averages over the cooridnates to find the centroid
	 * Adds the centroid to the distance matrix
	 * 
	 * @return the centroid of the points represented in the distance matrix
	 */
	public PointND findCentroid() {
		PointSet ps = this.toPointSet();
		this.checkPointSet(ps);
		this.centroid = new PointND.Double(ps);
		this.centroid.setCentroid();
		this.centroidDist = new double[matrix.length];
		for(int i = 0; i < matrix.length; i ++) {
			int index = lookup.get(ps.get(i).getID());
			centroidDist[index] = ps.get(i).distance(centroid);
			//System.out.println( ps.get(i).getID()+ " Dist to centroid: " + centroidDist[index]);
		}
				
		return centroid;	
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
        	if(points.get(i).isDummyNode()) {
        		str += "*";
        	}
        	
        	str += "[";
        	for(int j = 0; j < matrix.length; j++) {
        		BigDecimal bd = new BigDecimal(matrix[i][j]);
        		bd = bd.round(new MathContext(7));
        		str+= " " + java.lang.Double.valueOf(String.format("%."+7+"G", bd)) + " ";
        	}
        	str+="]\n";
        }
        str += "]";
		return str;
	}

	public double getZero() {
		// TODO Auto-generated method stub
		return this.zero;
	}

	public int size() {
		// TODO Auto-generated method stub
		return matrix.length;
	}

}
