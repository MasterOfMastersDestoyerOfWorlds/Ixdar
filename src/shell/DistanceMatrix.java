package shell;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Vector;

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

import shell.PointND.Double;

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
	 * for reconstructing a partial distance  matrix from a list of segments
	 * @param segments
	 * @param ps
	 * @param d
	 */
	
	public DistanceMatrix(ArrayList<Segment> segments, PointSet ps, DistanceMatrix d) {
		matrix = new double[ps.size()][ps.size()];
		points = new ArrayList<PointND>(ps.size());
		for(int i = 0; i < ps.size(); i ++) {
			points.add(null);
		}
		
		lookup = new HashMap<Integer,Integer>();
		this.zero = d.zero;
		for (Segment s : segments) {

			System.out.println(segments);
			int i = ps.indexOf(s.first);
			int j = ps.indexOf(s.last);
			if(!points.contains(s.first)) {
				System.out.println(ps.size());
				System.out.println(s.first.getID());
				points.set(i, s.first);
				lookup.put(s.first.getID(), i);
				System.out.println(points);
			}
			if(!points.contains(s.last)) {
				points.set(j, s.last);
				lookup.put(s.last.getID(), j);
			}
			double dist = d.getDistance(points.get(i), points.get(j));
			System.out.println(s);
			System.out.println(dist);
			System.out.println(i + " " + j);
			matrix[i][j] = dist;
			matrix[j][i] = dist;
			
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
		
		if(i.isNSphereCenter() || j.isNSphereCenter()) {
			return nSphereRadius;
		}
		
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
		this.checkPointSet(ps);

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
				
		return findCentroid(ps);	
	}
	
	public PointND findCentroid(PointSet ps) {
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
	
	public PointND findNSphereCenter() {
		PointSet ps = this.toPointSet();
		return findNSphereCenter(ps);
		
	}
	
	public PointND findNSphereCenter(PointSet ps) {
		int numPoints = ps.size();
		int dim = ps.getMaxDim();
		
		//https://stackoverflow.com/questions/37449046/how-to-calculate-the-sphere-center-with-4-points
		//https://stackoverflow.com/questions/72230142/how-to-find-the-center-and-radius-of-an-any-dimensional-sphere-giving-dims1-poi

		double[][] M = new double[numPoints][dim];
		double[] v = new double[numPoints];
		for (int i = 0; i < numPoints; i++) {
			for (int j = 1; j < dim; j++) {
				M[i][j-1] = ps.get(i).getCoord(j);
				v[i] += Math.pow(ps.get(i).getCoord(j), 2);
			}
		}
		RealMatrix A = new Array2DRowRealMatrix(M);
		
		for (int j = 0; j < numPoints; j++) {
			M[numPoints-1][j] = 1;
		}
		
		SingularValueDecomposition svd = new SingularValueDecomposition(A);
		DecompositionSolver ds = svd.getSolver();
		RealVector b = new ArrayRealVector(v);
		RealVector x = ds.solve(b);
		
		double[] ans = new double[numPoints];
		
		for(int i = 1; i < numPoints; i ++){

			ans[i] = x.getEntry(i-1)/2;
		}
		
		this.nSphereCenter = new PointND.Double(ans);
		this.nSphereCenter.setNSphereCenter();
		this.nSphereRadius = this.nSphereCenter.distance(ps.get(0));
		

		for(PointND p: ps) {
			assert Math.abs(p.distance(nSphereCenter)- nSphereRadius) < 0.0001;
		}
	
		return this.nSphereCenter;	
	}
	
	public HashMap<Segment, PointND> findMidPoints() {
		PointSet ps = this.toPointSet();
		return findMidPoints(ps);
	}
	
	public HashMap<Segment, PointND> findMidPoints(PointSet ps) {
		HashMap<Segment, PointND> result = new HashMap<Segment, PointND>();

		for (int i = 0; i < ps.size(); i++) {
			for (int j = 1; j < ps.size(); j++) {
				if(i != j) {
					Segment s = new Segment(ps.get(i), ps.get(j));
					if(!result.containsKey(s)) {
						int dim = ps.getMaxDim();
						double[] coords = new double[dim];
						for(int k = 0; k < dim; k ++) {
							coords[k] = (s.first.getCoord(k) + s.last.getCoord(k))/2;
						}
						PointND midpoint = new PointND.Double(coords);
						assert Math.abs(s.first.distance(midpoint) - s.last.distance(midpoint)) < 0.0001;
						result.put(s, midpoint);
					}
				}
			}
		}
		
		return result;	
	}
	
	public PointND findMidPointsCenter(PointSet ps) {
		PointSet midpoints = new PointSet();
		midpoints.addAll(this.findMidPoints(ps).values());
		this.centroid = new PointND.Double(midpoints);
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
	
    public int dfsCycleCount(int start, boolean[] visited, Shell shell, ArrayList<Segment> traveled){
 
    	int cycles = 0;
        // Set current node as visited
        visited[start] = true;
 
        // For every node of the graph
        for (int i = 0; i < matrix[start].length; i++) {
 
            // If some node is adjacent to the current node
            // and it has not already been visited
            if (this.matrix[start][i] > 0) {
            	Segment s = new Segment(points.get(i), points.get(start));
            	if(!traveled.contains(s)) {
            		traveled.add(s);
	            	if(!visited[i]) {
	                    cycles += dfsCycleCount(i, visited, shell, traveled);
	                    if(cycles == 1) {
	                    	if(!shell.contains(this.points.get(i)))
	                    		shell.add(this.points.get(i));
	                    }
	            	}
	            	else {
	            		cycles++;
	            		if(!shell.contains(this.points.get(i)))
	            			shell.add(this.points.get(i));
	            	}
            	}
            	if(cycles > 1) {
            		return cycles;
            	}
            }
        }
        return cycles;
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
