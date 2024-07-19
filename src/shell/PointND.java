package shell;

import java.awt.geom.Point2D;
import java.io.Serializable;

/**
 * The {@code PointND} class defines a point representing a location in
 * {@code (x,y, ...)} coordinate space.
 * <p>
 * This class is only the abstract superclass for all objects that store a ND
 * coordinate. The actual storage representation of the coordinates is left to
 * the subclass.
 *
 * @author Andrew Wollack
 * 
 */
public abstract class PointND implements Cloneable {

	/**
	 * The {@code Float} class defines a point specified in float precision.
	 * 
	 */
	public static class Float extends PointND implements Serializable {
		/**
		 * The X coordinate of this {@code PointND}.
		 * 
		 * @serial
		 */
		public float[] fs;

		/**
		 * Constructs and initializes a {@code PointND} with coordinates (0,&nbsp;0).
		 * 
		 */
		public Float(int ID) {

			this.setID(ID);
			fs = new float[1];
		}
		

		/**
		 * Constructs and initializes a {@code PointND} with the specified coordinates.
		 *
		 * @param fs the n coordinates of the newly constructed {@code PointND}
		 * 
		 */
		public Float(int ID, float... fs) {

			this.setID(ID);
			int ind = 1;
		    for (int i = fs.length-1; i >=0; i--) { 
		        if (fs[i] != 0) { 
		            ind = i+1; 
		            break; 
		        } 
		    } 
			this.fs = new float[ind];
			for(int i = 0; i < this.fs.length; i++) {
				this.fs[i] = fs[i];
			}
		}
		
		/**
		 * Constructs and initializes a {@code PointND} with coordinates (0,&nbsp;0).
		 * 
		 */
		public Float() {

			this.setID(maxID);
			fs = new float[1];
		}

		/**
		 * Constructs and initializes a {@code PointND} with the specified coordinates.
		 *
		 * @param fs the n coordinates of the newly constructed {@code PointND}
		 * 
		 */
		public Float(float... fs) {

			this.setID(maxID);
			int ind = 1;
		    for (int i = fs.length-1; i >=0; i--) { 
		        if (fs[i] != 0) { 
		            ind = i+1; 
		            break; 
		        } 
		    } 
			this.fs = new float[ind];
			for(int i = 0; i < this.fs.length; i++) {
				this.fs[i] = fs[i];
			}
		}


		@Override
		public int hashCode() {
			return this.getID();
		}
		
		/**
		 * @return the dimension of the vector
		 */
		public int getDim() {
			return fs.length;
		}

		/**
		 * {@inheritDoc}
		 */
		public double getCoord(int dim) {
			return (double) fs[dim];
		}

		/**
		 * {@inheritDoc}
		 */
		public void setLocation(double... ds) {
			float[] fs = new float[ds.length];
			for (int i = 0; i < ds.length; i++) {
				fs[i] = (float) ds[i];
			}
			this.fs = fs;
		}

		/**
		 * Sets the location of this {@code PointND} to the specified {@code float}
		 * coordinates.
		 *
		 * @param fs the new coordinates of this {@code PointND}
		 * 
		 */
		public void setLocation(float... fs) {
			this.fs = fs;
		}

		/**
		 * Returns a {@code String} that represents the value of this {@code PointND}.
		 * 
		 * @return a string representation of this {@code PointND}.
		 * 
		 */
		public String toString() {
			StringBuilder sb = new StringBuilder("PointND.Float[");
			for (int i = 0; i < fs.length - 1; i++) {
				sb.append(fs[i]);
				sb.append(", ");
			}
			sb.append(fs[fs.length - 1]);
			sb.append("]");
			return this.getID() + " ";
		}

		/*
		 * JDK 1.6 serialVersionUID
		 */
		private static final long serialVersionUID = -2870572449815403710L;

		@Override
		public double[] getCoordList() {
			double[] ds = new double[fs.length];
			for (int i = 0; i < fs.length; i++) {
				ds[i] = (double) fs[i];
			}
			return ds;
		}
	}

	/**
	 * The {@code Double} class defines a point specified in {@code double}
	 * precision.
	 * 
	 */
	public static class Double extends PointND implements Serializable {
		/**
		 * The coordinates of this {@code PointND}.
		 * 
		 * @serial
		 */
		public double[] ds;

		/**
		 * Constructs and initializes a {@code PointND} with coordinates (0,&nbsp;0).
		 * 
		 */
		public Double() {
			this.setID(maxID);
			ds = new double[1];
		}

		/**
		 * Constructs and initializes a {@code PointND} with the specified coordinates.
		 *
		 * @param ds the n coordinates of the newly constructed {@code PointND}
		 * 
		 */
		public Double(double... fs) {
			this.setID(maxID);			
			int ind = 1;
		    for (int i = fs.length-1; i >=0; i--) { 
		        if (fs[i] != 0) { 
		            ind = i+1; 
		            break; 
		        } 
		    } 
			ds = new double[ind];
			for(int i = 0; i < ds.length; i++) {
				ds[i] = fs[i];
			}
		}
		
		/**
		 * Constructs and initializes a {@code PointND} with coordinates (0,&nbsp;0).
		 * @param ID for comparison purposes across basis
		 */
		public Double(int ID) {
			this.setID(ID);
			ds = new double[1];
		}

		/**
		 * Constructs and initializes a {@code PointND} with the specified coordinates.
		 *
		 * @param ds the n coordinates of the newly constructed {@code PointND}
		 * @param ID for comparison purposes across basis
		 */
		public Double(int ID, double... fs) {
			this.setID(ID);
			int ind = 1;
		    for (int i = fs.length-1; i >=0; i--) { 
		        if (fs[i] != 0) { 
		            ind = i+1; 
		            break; 
		        } 
		    } 
			ds = new double[ind];
			for(int i = 0; i < ds.length; i++) {
				ds[i] = fs[i];
			}
		}

		/**
		 * Constructs a {@code PointND} as the centtrroid of the specified PointSet
		 * @param ps
		 */
		public Double(PointSet ps) {
			this.setID(maxID);
			ds = new double[ps.getMaxDim()];
			for(PointND p : ps) {
				for(int i = 0 ; i < p.getDim(); i ++) {
					ds[i] += p.getCoord(i);
				}
			}
			
			for(int i = 0; i < ds.length; i ++) {
				ds[i] = ds[i]/ps.size();
			}
		}
		
		@Override
		public int hashCode() {
			return this.getID();
		}

		public int getDim() {
			return ds.length;
		}

		/**
		 * {@inheritDoc}
		 * 
		 */
		public double getCoord(int dim) {
			if(dim >= ds.length){
				return 0.0;
			}
			return (double) ds[dim];
		}

		/**
		 * {@inheritDoc}
		 * 
		 */
		public void setLocation(double... ds) {
			this.ds = ds;
		}

		/**
		 * {@inheritDoc}
		 * 
		 */
		public void setLocation(float... fs) {
			double[] ds = new double[fs.length];
			for (int i = 0; i < fs.length; i++) {
				ds[i] = (double) fs[i];
			}
			this.ds = ds;
		}

		/**
		 * Returns a {@code String} that represents the value of this {@code PointND}.
		 * 
		 * @return a string representation of this {@code PointND}.
		 * 
		 */
		public String toString() {
			StringBuilder sb = new StringBuilder("PointND.Double[");
			for (int i = 0; i < ds.length - 1; i++) {
				sb.append(ds[i]);
				sb.append(", ");
			}
			sb.append(ds[ds.length - 1]);
			sb.append("]");
			return this.getID() + "";
		}

		/*
		 * JDK 1.6 serialVersionUID
		 */
		private static final long serialVersionUID = 6150783262733311327L;

		/**
		 * {@inheritDoc}
		 */
		@Override
		public double[] getCoordList() {
			// TODO Auto-generated method stub
			return ds;
		}
	}

	/**
	 * This is an abstract class that cannot be instantiated directly. Type-specific
	 * implementation subclasses are available for instantiation and provide a
	 * number of formats for storing the information necessary to satisfy the
	 * various accessor methods below.
	 *
	 * @see java.awt.geom.PointND.Float
	 * @see java.awt.geom.PointND.Double
	 * @see java.awt.Point
	 * 
	 */
	protected PointND() {
	}

	private int ID = -1;
	
	private boolean isCentroid = false;
	
	private boolean isNSphereCenter = false;
	
	private boolean isDummyNode = false;
	
	private static int maxID = 0;
	
	/**
	 * Returns the nth coordinate of this {@code PointND} in {@code double} precision.
	 * 
	 * @param dim nth dimension to retrieve
	 * 
	 * @return the coordinates of this {@code PointND}.
	 * 
	 */
	public abstract double getCoord(int dim);

	/**
	 * Returns the coordinates of this {@code PointND} in {@code double} precision.
	 * 
	 * @param dim nth dimension to retrieve
	 * 
	 * @return the coordinates of this {@code PointND}.
	 * 
	 */
	public abstract double[] getCoordList();

	public abstract int getDim();

	/**
	 * Sets the location of this {@code PointND} to the specified {@code float}
	 * coordinates.
	 *
	 * @param ds the new coordinates of this {@code PointND}
	 * 
	 */
	public abstract void setLocation(double... ds);

	/**
	 * Sets the location of this {@code PointND} to the same coordinates as the
	 * specified {@code PointND} object.
	 * 
	 * @param p the specified {@code PointND} to which to set this {@code PointND}
	 * 
	 */
	public void setLocation(PointND p) {
		setLocation(p.getCoordList());
	}

	/**
	 * Returns the square of the distance from this {@code PointND} to a specified
	 * point.
	 *
	 * @param p the coordinates of the specified point to be measured against this
	 *           {@code PointND}
	 * @return the square of the distance between this {@code PointND} and the
	 *         specified point.
	 * 
	 */
	public double distanceSq(double... p) {
		return distanceSq(new PointND.Double(-1, p));
	}

	/**
	 * Returns the square of the distance from this {@code PointND} to a specified
	 * {@code PointND}.
	 *
	 * @param pt the specified point to be measured against this {@code PointND}
	 * @return the square of the distance between this {@code PointND} to a
	 *         specified {@code PointND}.
	 * 
	 */
	public double distanceSq(PointND pt) {
		double sum = 0;

		int length = Math.max(pt.getDim(), getDim());
		
		for (int i = 0; i < length; i++) {
			
			double val;
			if(i >= pt.getDim()) {
				val = getCoord(i);
			}
			else if(i >= getDim()) {
				val = 0 - pt.getCoord(i);
			}
			else {
				val = getCoord(i) - pt.getCoord(i);
			}
			sum += val * val;
		}
		return sum;
	}

	/**
	 * Returns the distance from this {@code PointND} to a specified point.
	 *
	 * @param p the coordinates of the specified point to be measured against this
	 *           {@code PointND}
	 * @return the distance between this {@code PointND} and a specified point.
	 * 
	 */
	public double distance(double... p) {

		return distance(new PointND.Double(-1, p));
	}

	/**
	 * Returns the distance from this {@code PointND} to a specified
	 * {@code PointND}.
	 *
	 * @param pt the specified point to be measured against this {@code PointND}
	 * @return the distance between this {@code PointND} and the specified
	 *         {@code PointND}.
	 * 
	 */
	public double distance(PointND pt) {
		double sum = 0;
		int length = Math.max(pt.getDim(), getDim());
		for (int i = 0; i < length; i++) {
			
			double val;
			if(i >= pt.getDim()) {
				val = getCoord(i);
			}
			else if(i >= getDim()) {
				val = 0 - pt.getCoord(i);
			}
			else {
				val = getCoord(i) - pt.getCoord(i);
			}
			sum += val * val;
		}
		return Math.sqrt(sum);
	}
	
	/**
	 * Returns the point vector centered at a specified {@code PointND}.
	 *
	 * @param pt the specified point for the vector to start at
	 * @return the vector from this point to the specified {@code PointND}.
	 * 
	 */
	public PointND toVector(PointND pt) {
		double[] ds;
		if (pt.getDim() > getDim()) {
			ds = new double[pt.getDim()];
		}
		else {
			ds = new double[getDim()];
		}
		for (int i = 0; i < ds.length; i++) {
			if(i >= pt.getDim()) {
				ds[i] = getCoord(i);
			}
			else if(i >= getDim()) {
				ds[i] = 0 - pt.getCoord(i);
			}
			else {
				ds[i] = getCoord(i) - pt.getCoord(i);
			}
		}
		return new PointND.Double(-1, ds);
	}

	/**
	 * Creates a new object of the same class and with the same contents as this
	 * object.
	 * 
	 * @return a clone of this instance.
	 * @exception OutOfMemoryError if there is not enough memory.
	 * @see java.lang.Cloneable
	 */
	public Object clone() {
		try {
			return super.clone();
		} catch (CloneNotSupportedException e) {
			// this shouldn't happen, since we are Cloneable
			throw new InternalError(e);
		}
	}
	
	

	/**
	 * Determines whether or not two points are equal. Two instances of
	 * {@code PointND} are equal if the values of their {@code x} and {@code y}
	 * member fields, representing their position in the coordinate space, are the
	 * same.
	 * 
	 * @param obj an object to be compared with this {@code PointND}
	 * @return {@code true} if the object to be compared is an instance of
	 *         {@code PointND} and has the same values; {@code false} otherwise.
	 */
	public boolean equals(Object obj) {
		if (obj instanceof PointND) {
			PointND pt = (PointND) obj;
			if(pt.getID() == getID()) {
				return true;
			}

			return false;
		}
		return super.equals(obj);
	}

	/**
	 * Converts the N dimensional Point to a 2 Dimensional Point for graphing purposes
	 * 
	 * @return a {@code Point2D} that consists of the first 2 coordinates of this point
	 */
	public Point2D toPoint2D() {
		if(this.isDummyNode){
			return new Point2D.Double(-1000000, -1000000);
		}
		return new Point2D.Double(getCoord(0), getCoord(1));
	}

	public int getID() {
		return ID;
	}

	public void setID(int ID) {
		if(ID >= maxID ) {
			maxID = ID + 1;
		}
		this.ID = ID;
	}

	public boolean isCentroid() {
		return isCentroid;
	}

	public void setCentroid() {
		this.isCentroid = true;
	}
	
	public boolean isNSphereCenter() {
		return isNSphereCenter;
	}

	public void setNSphereCenter() {
		this.isNSphereCenter = true;
	}

	public boolean isDummyNode() {
		return isDummyNode;
	}

	public void setDummyNode() {
		this.isDummyNode = true;
	}
}
