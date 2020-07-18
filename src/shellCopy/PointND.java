package shellCopy;

import java.awt.geom.Point2D;
import java.io.Serializable;

/**
 * The {@code Point2D} class defines a point representing a location
 * in {@code (x,y)} coordinate space.
 * <p>
 * This class is only the abstract superclass for all objects that
 * store a 2D coordinate.
 * The actual storage representation of the coordinates is left to
 * the subclass.
 *
 * @author      Jim Graham
 * @since 1.2
 */
public abstract class PointND implements Cloneable {

    /**
     * The {@code Float} class defines a point specified in float
     * precision.
     * @since 1.2
     */
    public static class Float extends PointND implements Serializable {
        /**
         * The X coordinate of this {@code Point2D}.
         * @since 1.2
         * @serial
         */
        public float[] fs;

        /**
         * Constructs and initializes a {@code Point2D} with
         * coordinates (0,&nbsp;0).
         * @since 1.2
         */
        public Float() {
        }

        /**
         * Constructs and initializes a {@code Point2D} with
         * the specified coordinates.
         *
         * @param x the X coordinate of the newly
         *          constructed {@code Point2D}
         * @param y the Y coordinate of the newly
         *          constructed {@code Point2D}
         * @since 1.2
         */
        public Float(float... fs) {
            this.fs = fs;
        }
        
        public int getDim() {
        	return fs.length;
        }

        /**
         * {@inheritDoc}
         * @since 1.2
         */
        public double getCoord(int dim) {
            return (double) fs[dim];
        }

        /**
         * {@inheritDoc}
         * @since 1.2
         */
        public void setLocation(double... ds) {
        	float[] fs = new float[ds.length];
        	for(int i = 0; i < ds.length; i ++) {
        		fs[i] = (float)ds[i];
        	}
        	this.fs = fs;
        }

        /**
         * Sets the location of this {@code Point2D} to the
         * specified {@code float} coordinates.
         *
         * @param x the new X coordinate of this {@code Point2D}
         * @param y the new Y coordinate of this {@code Point2D}
         * @since 1.2
         */
        public void setLocation(float... fs) {
            this.fs = fs;
        }

        /**
         * Returns a {@code String} that represents the value
         * of this {@code Point2D}.
         * @return a string representation of this {@code Point2D}.
         * @since 1.2
         */
        public String toString() {
        	StringBuilder sb = new StringBuilder("PointND.Float[");
        	for(int i = 0; i < fs.length-1; i++) {
        		sb.append(fs[i]);
        		sb.append(", ");
        	}
        	sb.append(fs[fs.length-1]);
        	sb.append("]");
            return sb.toString();
        }

        /*
         * JDK 1.6 serialVersionUID
         */
        private static final long serialVersionUID = -2870572449815403710L;

		@Override
		public double[] getCoordList() {
        	double[] ds = new double[fs.length];
        	for(int i = 0; i < fs.length; i ++) {
        		ds[i] = (double)fs[i];
        	}
        	return ds;
		}
    }

    /**
     * The {@code Double} class defines a point specified in
     * {@code double} precision.
     * @since 1.2
     */
    public static class Double extends PointND implements Serializable {
        /**
         * The X coordinate of this {@code Point2D}.
         * @since 1.2
         * @serial
         */
        public double[] ds;

        /**
         * Constructs and initializes a {@code Point2D} with
         * coordinates (0,&nbsp;0).
         * @since 1.2
         */
        public Double() {
        }

        /**
         * Constructs and initializes a {@code Point2D} with
         * the specified coordinates.
         *
         * @param x the X coordinate of the newly
         *          constructed {@code Point2D}
         * @param y the Y coordinate of the newly
         *          constructed {@code Point2D}
         * @since 1.2
         */
        public Double(double... fs) {
            this.ds = fs;
        }
        
        public int getDim() {
        	return ds.length;
        }

        /**
         * {@inheritDoc}
         * @since 1.2
         */
        public double getCoord(int dim) {
            return (double) ds[dim];
        }

        /**
         * {@inheritDoc}
         * @since 1.2
         */
        public void setLocation(double... ds) {
            this.ds = ds;
        }

        /**
         * Sets the location of this {@code Point2D} to the
         * specified {@code float} coordinates.
         *
         * @param x the new X coordinate of this {@code Point2D}
         * @param y the new Y coordinate of this {@code Point2D}
         * @since 1.2
         */
        public void setLocation(float... fs) {
        	double[] ds = new double[fs.length];
        	for(int i = 0; i < fs.length; i ++) {
        		ds[i] = (double)fs[i];
        	}
        	this.ds = ds;
        }

        /**
         * Returns a {@code String} that represents the value
         * of this {@code Point2D}.
         * @return a string representation of this {@code Point2D}.
         * @since 1.2
         */
        public String toString() {
        	StringBuilder sb = new StringBuilder("PointND.Double[");
        	for(int i = 0; i < ds.length-1; i++) {
        		sb.append(ds[i]);
        		sb.append(", ");
        	}
        	sb.append(ds[ds.length-1]);
        	sb.append("]");
            return sb.toString();
        }

        /*
         * JDK 1.6 serialVersionUID
         */
        private static final long serialVersionUID = 6150783262733311327L;

		@Override
		public double[] getCoordList() {
			// TODO Auto-generated method stub
			return ds;
		}
    }

    /**
     * This is an abstract class that cannot be instantiated directly.
     * Type-specific implementation subclasses are available for
     * instantiation and provide a number of formats for storing
     * the information necessary to satisfy the various accessor
     * methods below.
     *
     * @see java.awt.geom.Point2D.Float
     * @see java.awt.geom.Point2D.Double
     * @see java.awt.Point
     * @since 1.2
     */
    protected PointND() {
    }

    /**
     * Returns the X coordinate of this {@code Point2D} in
     * {@code double} precision.
     * @return the X coordinate of this {@code Point2D}.
     * @since 1.2
     */
    public abstract double getCoord(int dim);
    
    public abstract double[] getCoordList();
    
    public abstract int getDim();

    /**
     * Sets the location of this {@code Point2D} to the
     * specified {@code double} coordinates.
     *
     * @param x the new X coordinate of this {@code Point2D}
     * @param y the new Y coordinate of this {@code Point2D}
     * @since 1.2
     */
    public abstract void setLocation(double... ds);

    /**
     * Sets the location of this {@code Point2D} to the same
     * coordinates as the specified {@code Point2D} object.
     * @param p the specified {@code Point2D} to which to set
     * this {@code Point2D}
     * @since 1.2
     */
    public void setLocation(PointND p) {
        setLocation(p.getCoordList());
    }

    /**
     * Returns the square of the distance from this
     * {@code Point2D} to a specified point.
     *
     * @param px the X coordinate of the specified point to be measured
     *           against this {@code Point2D}
     * @param py the Y coordinate of the specified point to be measured
     *           against this {@code Point2D}
     * @return the square of the distance between this
     * {@code Point2D} and the specified point.
     * @since 1.2
     */
    public double distanceSq(double... p) {
    	if(p.length != getDim()) {
    		return -1;
    	}
    	double sum = 0;
    	for(int i = 0; i < p.length; i ++) {
    		p[i] -= this.getCoord(i);
    		sum += p[i] * p[i];
    	}
        return sum;
    }

    /**
     * Returns the square of the distance from this
     * {@code Point2D} to a specified {@code Point2D}.
     *
     * @param pt the specified point to be measured
     *           against this {@code Point2D}
     * @return the square of the distance between this
     * {@code Point2D} to a specified {@code Point2D}.
     * @since 1.2
     */
    public double distanceSq(PointND pt) {
    	if(pt.getDim() != getDim()) {
    		return -1;
    	}
    	double sum = 0;
    	for(int i = 0; i < pt.getDim(); i ++) {
    		double val = pt.getCoord(i) - this.getCoord(i);
    		sum += val * val;
    	}
        return sum;
    }

    /**
     * Returns the distance from this {@code Point2D} to
     * a specified point.
     *
     * @param px the X coordinate of the specified point to be measured
     *           against this {@code Point2D}
     * @param py the Y coordinate of the specified point to be measured
     *           against this {@code Point2D}
     * @return the distance between this {@code Point2D}
     * and a specified point.
     * @since 1.2
     */
    public double distance(double... p) {
    	if(p.length != getDim()) {
    		return -1;
    	}
    	double sum = 0;
    	for(int i = 0; i < p.length; i ++) {
    		p[i] -= this.getCoord(i);
    		sum += p[i] * p[i];
    	}
        return Math.sqrt(sum);
    }

    /**
     * Returns the distance from this {@code Point2D} to a
     * specified {@code Point2D}.
     *
     * @param pt the specified point to be measured
     *           against this {@code Point2D}
     * @return the distance between this {@code Point2D} and
     * the specified {@code Point2D}.
     * @since 1.2
     */
    public double distance(PointND pt) {
    	if(pt.getDim() != getDim()) {
    		return -1;
    	}
    	double sum = 0;
    	for(int i = 0; i < pt.getDim(); i ++) {
    		double val = pt.getCoord(i) - this.getCoord(i);
    		sum += val * val;
    	}
        return Math.sqrt(sum);
    }

    /**
     * Creates a new object of the same class and with the
     * same contents as this object.
     * @return     a clone of this instance.
     * @exception  OutOfMemoryError            if there is not enough memory.
     * @see        java.lang.Cloneable
     * @since      1.2
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
     * {@code Point2D} are equal if the values of their
     * {@code x} and {@code y} member fields, representing
     * their position in the coordinate space, are the same.
     * @param obj an object to be compared with this {@code Point2D}
     * @return {@code true} if the object to be compared is
     *         an instance of {@code Point2D} and has
     *         the same values; {@code false} otherwise.
     * @since 1.2
     */
    public boolean equals(Object obj) {
        if (obj instanceof PointND) {
            PointND pt = (PointND) obj;
        	if(pt.getDim() != getDim()) {
        		return false;
        	}
            boolean result = true;
        	for(int i = 0; i < pt.getDim(); i ++) {
        		boolean val = (getCoord(i) == pt.getCoord(i));
        		result = result && val;
        	}
            return result;
        }
        return super.equals(obj);
    }
    
    public Point2D to2D() {
		return new Point2D.Double(getCoord(0), getCoord(1));
    }
}
