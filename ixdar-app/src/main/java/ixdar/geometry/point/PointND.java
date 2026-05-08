package ixdar.geometry.point;

import java.io.Serializable;
import java.util.ArrayList;

import org.joml.Vector2f;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.geometry.GeometryAnnotation;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.graphics.render.sdf.SDFCircle;

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
public abstract class PointND extends SDFCircle implements Geometry, PointCollection, Cloneable {
    public static final String PT = "pt";
    public static final String POINT = "point";
    public static final String STR = ", ";
    public static final String STR_2 = "]";
    public static final String STR_4F = "%.4f";
    public static final String ADD_POINT_COORD_1_DOUBLE_COORD_N_DOUBLE = "add point [coord 1(double)] ... [coord n(double)]";
    public static final String A_POINT_IN_N_DIMENSIONAL_SPACE = "a point in N dimensional space";
    public static final int NUM__1000000 = -1000000;
    public static OptionList opts = new OptionList("p", PT, POINT);

    private static int maxID = 0;

    public String cmd = PT;

    protected int ID = -1;

    private boolean isCentroid = false;

    private boolean isNSphereCenter = false;

    private boolean isDummyNode = false;

    /**
     * This is an abstract class that cannot be instantiated directly. Type-specific
     * implementation subclasses are available for instantiation and provide a
     * number of formats for storing the information necessary to satisfy the
     * various accessor methods below.
     */
    protected PointND() {
    }

    /**
     * TODO: document {@code parse}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
     */
    public static PointND parse(String[] args, int startIdx) throws TerminalParseException {

        if (args.length - startIdx == 0) {
            return new PointND.Double(0.0, 0.0);
        }
        double[] coords = new double[args.length - startIdx];
        for (int i = 0; i < coords.length; i++) {
            coords[i] = java.lang.Double.parseDouble(args[startIdx + i]);
        }
        PointND pt = new PointND.Double(coords);
        return pt;
    }

    /**
     * Returns the nth coordinate of this {@code PointND} in {@code double}
     * precision.
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
     * @return the coordinates of this {@code PointND}.
     *
     */
    public abstract double[] getCoordList();

    /**
     * TODO: document {@code getDim}.
     *
     * @return TODO: describe
     */
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
     *          {@code PointND}
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
            if (i >= pt.getDim()) {
                val = getCoord(i);
            } else if (i >= getDim()) {
                val = 0 - pt.getCoord(i);
            } else {
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
     *          {@code PointND}
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
            if (i >= pt.getDim()) {
                val = getCoord(i);
            } else if (i >= getDim()) {
                val = 0 - pt.getCoord(i);
            } else {
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
        } else {
            ds = new double[getDim()];
        }
        for (int i = 0; i < ds.length; i++) {
            if (i >= pt.getDim()) {
                ds[i] = getCoord(i);
            } else if (i >= getDim()) {
                ds[i] = 0 - pt.getCoord(i);
            } else {
                ds[i] = getCoord(i) - pt.getCoord(i);
            }
        }
        return new PointND.Double(-1, ds);
    }

    /**
     * Creates a new object of the same class and with the same contents as this
     * object.
     *
     * @exception OutOfMemoryError if there is not enough memory.
     * @throws InternalError TODO: describe
     * @return a clone of this instance.
     * @see java.lang.Cloneable
     */
    @Override
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
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof PointND) {
            PointND pt = (PointND) obj;
            if (pt.getID() == getID()) {
                return true;
            }
            if (pt.distance(this) == 0.0) {
                return true;
            }

            return false;
        }
        return super.equals(obj);
    }

    /**
     * Converts the N dimensional Knot to a 2 Dimensional Knot for graphing purposes.
     *
     * @return a {@code Point2D} that consists of the first 2 coordinates of this
     *         point
     */
    public Point2D toPoint2D() {
        if (this.isDummyNode) {
            return new Point2D.Double(NUM__1000000, NUM__1000000);
        }
        return new Point2D.Double(this.getScreenX(), this.getScreenY());
    }

    /**
     * TODO: document {@code getID}.
     *
     * @return TODO: describe
     */
    public int getID() {
        return ID;
    }

    /**
     * TODO: document {@code setID}.
     *
     * @param ID TODO: describe
     */
    public void setID(int ID) {
        if (ID >= maxID) {
            maxID = ID + 1;
        }
        this.ID = ID;
    }

    /**
     * TODO: document {@code isCentroid}.
     *
     * @return TODO: describe
     */
    public boolean isCentroid() {
        return isCentroid;
    }

    /**
     * TODO: document {@code setCentroid}.
     */
    public void setCentroid() {
        this.isCentroid = true;
    }

    /**
     * TODO: document {@code isNSphereCenter}.
     *
     * @return TODO: describe
     */
    public boolean isNSphereCenter() {
        return isNSphereCenter;
    }

    /**
     * TODO: document {@code setNSphereCenter}.
     */
    public void setNSphereCenter() {
        this.isNSphereCenter = true;
    }

    /**
     * TODO: document {@code isDummyNode}.
     *
     * @return TODO: describe
     */
    public boolean isDummyNode() {
        return isDummyNode;
    }

    /**
     * TODO: document {@code setDummyNode}.
     */
    public void setDummyNode() {
        this.isDummyNode = true;
    }

    /**
     * TODO: document {@code getScreenX}.
     *
     * @return TODO: describe
     */
    public double getScreenX() {
        if (this.isDummyNode) {
            return NUM__1000000;
        }
        return getCoord(0);
    }
    /**
     * TODO: document {@code getScreenXf}.
     *
     * @return TODO: describe
     */
    public float getScreenXf() {
        return (float) getScreenX();
    }

    /**
     * TODO: document {@code getScreenY}.
     *
     * @return TODO: describe
     */
    public double getScreenY() {
        if (this.isDummyNode) {
            return NUM__1000000;
        }
        return getCoord(1);
    }
    
    /**
     * TODO: document {@code getScreenYf}.
     *
     * @return TODO: describe
     */
    public float getScreenYf() {
        return (float) getScreenY();
    }

    /**
     * TODO: document {@code toCoordString}.
     *
     * @return TODO: describe
     */
    public String toCoordString() {
        return "X:" + (int) this.getScreenX() + " Y:"
                + (int) this.getScreenY();
    }

    /**
     * TODO: document {@code resetIds}.
     */
    public static void resetIds() {
        maxID = 0;
    }

    /**
     * The {@code Float} class defines a point specified in float precision.
     */
    public static class Float extends PointND implements Serializable {

        /*
         * JDK 1.6 serialVersionUID
         */
        private static final long serialVersionUID = -2870572449815403710L;
        /**
         * The X coordinate of this {@code PointND}.
         *
         * @serial
         */
        public float[] fs;

        /**
         * Constructs and initializes a {@code PointND} with coordinates (0,&nbsp;0).
         *
         * @param ID TODO: describe
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
         * @param ID TODO: describe
         */
        public Float(int ID, float... fs) {

            this.setID(ID);
            int ind = 1;
            for (int i = fs.length - 1; i >= 0; i--) {
                if (fs[i] != 0) {
                    ind = i + 1;
                    break;
                }
            }
            this.fs = new float[ind];
            for (int i = 0; i < this.fs.length; i++) {
                this.fs[i] = fs[i];
            }
        }

        /**
         * Constructs and initializes a {@code PointND} with coordinates (0,&nbsp;0).
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
            for (int i = fs.length - 1; i >= 0; i--) {
                if (fs[i] != 0) {
                    ind = i + 1;
                    break;
                }
            }
            this.fs = new float[ind];
            for (int i = 0; i < this.fs.length; i++) {
                this.fs[i] = fs[i];
            }
        }

        /**
         * TODO: document {@code hashCode}.
         *
         * @return TODO: describe
         */
        @Override
        public int hashCode() {
            return this.getID();
        }

        /**
         * TODO: document.
         *
         * @return the dimension of the vector
         */
        @Override
        public int getDim() {
            return fs.length;
        }

        /**
         * {@inheritDoc}.
         *
         * @param dim TODO: describe
         * @return TODO: describe
         */
        @Override
        public double getCoord(int dim) {
            return (double) fs[dim];
        }

        /**
         * {@inheritDoc}.
         *
         * @param ds TODO: describe
         */
        @Override
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
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("PointND.Float[");
            for (int i = 0; i < fs.length - 1; i++) {
                sb.append(fs[i]);
                sb.append(STR);
            }
            sb.append(fs[fs.length - 1]);
            sb.append(STR_2);
            return this.getID() + " ";
        }

        /**
         * TODO: document {@code toFileString}.
         *
         * @return TODO: describe
         */
        @Override
        public String toFileString() {
            String res = this.ID + " ";
            for (int i = 0; i < fs.length; i++) {
                res += String.format(STR_4F, fs[i]) + " ";
            }
            return res;
        }

        /**
         * TODO: document {@code getCoordList}.
         *
         * @return TODO: describe
         */
        @Override
        public double[] getCoordList() {
            double[] ds = new double[fs.length];
            for (int i = 0; i < fs.length; i++) {
                ds[i] = (double) fs[i];
            }
            return ds;
        }

        /**
         * TODO: document {@code parseCollection}.
         *
         * @param args TODO: describe
         * @param startIdx TODO: describe
         * @throws TerminalParseException TODO: describe
         * @return TODO: describe
         */
        @Override
        public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
            PointCollection c = parse(args, startIdx);
            return c;
        }

        /**
         * TODO: document {@code usage}.
         *
         * @return TODO: describe
         */
        @Override
        public String usage() {
            return ADD_POINT_COORD_1_DOUBLE_COORD_N_DOUBLE;
        }

        /**
         * TODO: document {@code desc}.
         *
         * @return TODO: describe
         */
        @Override
        public String desc() {
            return A_POINT_IN_N_DIMENSIONAL_SPACE;
        }

        /**
         * TODO: document {@code argLength}.
         *
         * @return TODO: describe
         */
        @Override
        public int argLength() {
            return -1;
        }

        /**
         * TODO: document {@code minArgLength}.
         *
         * @return TODO: describe
         */
        @Override
        public int minArgLength() {
            return -1;
        }

        /**
         * TODO: document {@code options}.
         *
         * @return TODO: describe
         */
        @Override
        public OptionList options() {
            return opts;
        }

        /**
         * TODO: document {@code realizePoints}.
         *
         * @return TODO: describe
         */
        @Override
        public ArrayList<PointND> realizePoints() {
            ArrayList<PointND> lst = new ArrayList<>();
            lst.add(this);
            return lst;
        }

        /**
         * TODO: document {@code fullName}.
         *
         * @return TODO: describe
         */
        @Override
        public String fullName() {
            return POINT;
        }

        /**
         * TODO: document {@code shortName}.
         *
         * @return TODO: describe
         */
        @Override
        public String shortName() {
            return cmd;
        }
    }

    /**
     * The {@code Double} class defines a point specified in {@code double}
     * precision.
     */
    @GeometryAnnotation(id = "pt")
    public static class Double extends PointND implements Serializable {

        /*
         * JDK 1.6 serialVersionUID
         */
        private static final long serialVersionUID = 6150783262733311327L;
        /**
         * The coordinates of this {@code PointND}.
         *
         * @serial
         */
        public double[] ds;

        /**
         * Constructs and initializes a {@code PointND} with coordinates (0,&nbsp;0).
         */
        public Double() {
            this.setID(maxID);
            ds = new double[1];
        }

        /**
         * Constructs and initializes a {@code PointND} with the specified coordinates.
         *
         * @param fs TODO: describe
         */
        public Double(double... fs) {
            this.setID(maxID);
            int ind = 1;
            for (int i = fs.length - 1; i >= 0; i--) {
                if (fs[i] != 0) {
                    ind = i + 1;
                    break;
                }
            }
            ds = new double[ind];
            for (int i = 0; i < ds.length; i++) {
                ds[i] = fs[i];
            }
        }

        /**
         * Constructs and initializes a {@code PointND} with coordinates (0,&nbsp;0).
         *
         * @param ID for comparison purposes across basis
         */
        public Double(int ID) {
            this.setID(ID);
            ds = new double[1];
        }

        /**
         * Constructs and initializes a {@code PointND} with the specified coordinates.
         *
         * @param ID             for comparison purposes across basis
         * @param fs TODO: describe
         */
        public Double(int ID, double... fs) {
            this.setID(ID);
            int ind = 1;
            for (int i = fs.length - 1; i >= 0; i--) {
                if (fs[i] != 0) {
                    ind = i + 1;
                    break;
                }
            }
            ds = new double[ind];
            for (int i = 0; i < ds.length; i++) {
                ds[i] = fs[i];
            }
        }

        /**
         * Constructs a {@code PointND} as the centtrroid of the specified PointSet.
         *
         * @param ps TODO: describe
         */
        public Double(PointSet ps) {
            this.setID(maxID);
            ds = new double[ps.getMaxDim()];
            for (PointND p : ps) {
                for (int i = 0; i < p.getDim(); i++) {
                    ds[i] += p.getCoord(i);
                }
            }

            for (int i = 0; i < ds.length; i++) {
                ds[i] = ds[i] / ps.size();
            }
        }

        /**
         * TODO: document {@code hashCode}.
         *
         * @return TODO: describe
         */
        @Override
        public int hashCode() {
            return this.getID();
        }

        /**
         * TODO: document {@code getDim}.
         *
         * @return TODO: describe
         */
        @Override
        public int getDim() {
            return ds.length;
        }

        /**
         * {@inheritDoc}.
         *
         * @param dim TODO: describe
         * @return TODO: describe
         */
        @Override
        public double getCoord(int dim) {
            if (dim >= ds.length) {
                return 0.0;
            }
            return (double) ds[dim];
        }

        /**
         * {@inheritDoc}.
         *
         * @param ds TODO: describe
         */
        @Override
        public void setLocation(double... ds) {
            this.ds = ds;
        }

        /**
         * {@inheritDoc}.
         *
         * @param fs TODO: describe
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
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("PointND.Double[");
            for (int i = 0; i < ds.length - 1; i++) {
                sb.append(ds[i]);
                sb.append(STR);
            }
            sb.append(ds[ds.length - 1]);
            sb.append(STR_2);
            return this.getID() + "";
        }

        /**
         * TODO: document {@code toFileString}.
         *
         * @return TODO: describe
         */
        @Override
        public String toFileString() {
            String res = this.ID + " ";
            for (int i = 0; i < ds.length; i++) {
                res += String.format(STR_4F, ds[i]) + " ";
            }
            return res;
        }

        /**
         * {@inheritDoc}.
         *
         * @return TODO: describe
         */
        @Override
        public double[] getCoordList() {
            return ds;
        }

        /**
         * TODO: document {@code parseCollection}.
         *
         * @param args TODO: describe
         * @param startIdx TODO: describe
         * @throws TerminalParseException TODO: describe
         * @return TODO: describe
         */
        @Override
        public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
            PointCollection c = parse(args, startIdx);
            return c;
        }

        /**
         * TODO: document {@code usage}.
         *
         * @return TODO: describe
         */
        @Override
        public String usage() {
            return ADD_POINT_COORD_1_DOUBLE_COORD_N_DOUBLE;
        }

        /**
         * TODO: document {@code desc}.
         *
         * @return TODO: describe
         */
        @Override
        public String desc() {
            return A_POINT_IN_N_DIMENSIONAL_SPACE;
        }

        /**
         * TODO: document {@code argLength}.
         *
         * @return TODO: describe
         */
        @Override
        public int argLength() {
            return -1;
        }

        /**
         * TODO: document {@code minArgLength}.
         *
         * @return TODO: describe
         */
        @Override
        public int minArgLength() {
            return -1;
        }

        /**
         * TODO: document {@code options}.
         *
         * @return TODO: describe
         */
        @Override
        public OptionList options() {
            return opts;
        }

        /**
         * TODO: document {@code realizePoints}.
         *
         * @return TODO: describe
         */
        @Override
        public ArrayList<PointND> realizePoints() {
            ArrayList<PointND> lst = new ArrayList<>();
            lst.add(this);
            return lst;
        }

        /**
         * TODO: document {@code fullName}.
         *
         * @return TODO: describe
         */
        @Override
        public String fullName() {
            return POINT;
        }

        /**
         * TODO: document {@code shortName}.
         *
         * @return TODO: describe
         */
        @Override
        public String shortName() {
            return cmd;
        }
    }

    /**
     * The {@code Hex} class defines a point specified in the triangular grid with
     * {@code integer} precision. we use three coordinates to represent the grid and
     * some points are represented by multiple mappings
     *
     * (q, r, s) q - left is negative, right is positive r - down and left is
     * negative, up and right is positive s - down and right is negative, up and
     * left is positive
     *
     * under this scheme point A (0,2,0) is the same as point B (1,1,1); although if
     * you traced the paths used to get to the point, point A is the shortest of the
     * two. There should always be a coordinate that is zero if you put it in
     * shortest path form.
     */
    public static class Hex extends PointND implements Serializable {
        public static final int NUM_3 = 3;
        public static final double NUM_3_0 = 3.0;
        public static final double NUM_2_0 = 2.0;
        public static final float NUM_1_5 = 1.5f;
        public static final double NUM_1_5_2 = 1.5;

        public static OptionList opts = new OptionList("hex", "hx");

        /*
         * JDK 1.6 serialVersionUID
         */
        private static final long serialVersionUID = 6150783262733311327L;

        private static final double root3over3 = 0.577350269;
        private static final double root3over2 = 0.866025404;
        private static final double root3 = 1.73205081;
        /**
         * The q coordinate of this {@code PointND.Hex}.
         *
         * right is positive left is negative
         *
         * @serial
         */
        public int q;

        /**
         * The r coordinate of this {@code PointND.Hex}.
         *
         * right and up is positive left and down is negative
         *
         * @serial
         */
        public int r;

        /**
         * The s coordinate of this {@code PointND.Hex}.
         *
         * left and up is positive right and down is negative
         *
         * @serial
         */
        public int s;

        /**
         * Constructs and initializes a {@code PointND.Hex} with coordinates
         * (0,&nbsp;0,&nbsp;0).
         */
        public Hex() {
            this.setID(maxID);
            q = 0;
            r = 0;
            s = 0;
        }

        /**
         * Constructs and initializes a {@code PointND} with the specified coordinates.
         *
         * @param q TODO: describe
         * @param r TODO: describe
         * @param s TODO: describe
         */
        public Hex(int q, int r, int s) {
            this.setID(maxID);
            this.q = q;
            this.r = r;
            this.s = s;
        }

        /**
         * Constructs and initializes a {@code PointND.Hex} with coordinates
         * (0,&nbsp;0,&nbsp;0).
         *
         * @param ID for comparison purposes across basis
         */
        public Hex(int ID) {
            this.setID(ID);
            q = 0;
            r = 0;
            s = 0;
        }

        /**
         * Constructs and initializes a {@code PointND.Hex} with the specified
         * coordinates.
         *
         * @param ID for comparison purposes across basis
         * @param q TODO: describe
         * @param r TODO: describe
         * @param s TODO: describe
         */
        public Hex(int ID, int q, int r, int s) {
            this.setID(ID);
            this.q = q;
            this.r = r;
            this.s = s;
        }

        /**
         * TODO: document {@code Hex}.
         *
         * @param coords TODO: describe
         */
        public Hex(int[] coords) {
            this.setID(maxID);
            this.q = coords[0];
            this.r = coords[1];
            this.s = coords[2];
        }

        /**
         * TODO: document {@code hashCode}.
         *
         * @return TODO: describe
         */
        @Override
        public int hashCode() {
            return this.getID();
        }

        /**
         * TODO: document {@code getDim}.
         *
         * @return TODO: describe
         */
        @Override
        public int getDim() {
            return NUM_3;
        }

        /**
         * {@inheritDoc}.
         *
         * @param dim TODO: describe
         * @return TODO: describe
         */
        @Override
        public double getCoord(int dim) {
            if (dim == 0) {
                return q;
            } else if (dim == 1) {
                return r;
            } else if (dim == 2) {
                return s;
            }
            return Integer.MIN_VALUE;
        }

        /**
         * {@inheritDoc}.
         *
         * @param ds TODO: describe
         */
        @Override
        public void setLocation(double... ds) {
            q = (int) ds[0];
            r = (int) ds[1];
            s = (int) ds[2];
        }

        /**
         * {@inheritDoc}.
         *
         * @param fs TODO: describe
         */
        public void setLocation(float... fs) {
            q = (int) fs[0];
            r = (int) fs[1];
            s = (int) fs[2];
        }

        /**
         * Returns a {@code String} that represents the value of this {@code PointND}.
         *
         * @return a string representation of this {@code PointND}.
         *
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("PointND.Hex[");
            sb.append(q);
            sb.append(STR);
            sb.append(r);
            sb.append(STR);
            sb.append(s);
            sb.append(STR_2);
            return this.getID() + "";
        }

        /**
         * TODO: document {@code toFileString}.
         *
         * @return TODO: describe
         */
        @Override
        public String toFileString() {
            return "HEX " + q + " " + r + " " + s;
        }

        /**
         * {@inheritDoc}.
         *
         * @return TODO: describe
         */
        @Override
        public double[] getCoordList() {
            return new double[] { q, r, s };
        }

        /**
         * TODO: document {@code parseCollection}.
         *
         * @param args TODO: describe
         * @param startIdx TODO: describe
         * @throws TerminalParseException TODO: describe
         * @return TODO: describe
         */
        @Override
        public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
            return Hex.parse(args, startIdx);
        }

        /**
         * TODO: document {@code parse}.
         *
         * @param args TODO: describe
         * @param startIdx TODO: describe
         * @throws TerminalParseException TODO: describe
         * @return TODO: describe
         */
        public static PointND parse(String[] args, int startIdx) throws TerminalParseException {
            if (args.length - startIdx != NUM_3) {
                throw new TerminalParseException(
                        "expected 3 coordinates to parse Hex Knot got " + (args.length - startIdx));
            }
            int[] coords = new int[NUM_3];
            for (int i = 0; i < coords.length; i++) {
                coords[i] = java.lang.Integer.parseInt(args[startIdx + i]);
            }
            PointND pt = new Hex(coords);
            return pt;
        }

        /**
         * TODO: document {@code usage}.
         *
         * @return TODO: describe
         */
        @Override
        public String usage() {
            return "add point [q, r, s]";
        }

        /**
         * TODO: document {@code desc}.
         *
         * @return TODO: describe
         */
        @Override
        public String desc() {
            return "a point in 3-dimensional hex space";
        }

        /**
         * TODO: document {@code argLength}.
         *
         * @return TODO: describe
         */
        @Override
        public int argLength() {
            return -1;
        }

        /**
         * TODO: document {@code minArgLength}.
         *
         * @return TODO: describe
         */
        @Override
        public int minArgLength() {
            return -1;
        }

        /**
         * TODO: document {@code options}.
         *
         * @return TODO: describe
         */
        @Override
        public OptionList options() {
            return opts;
        }

        /**
         * TODO: document {@code realizePoints}.
         *
         * @return TODO: describe
         */
        @Override
        public ArrayList<PointND> realizePoints() {
            ArrayList<PointND> lst = new ArrayList<>();
            lst.add(this);
            return lst;
        }

        /**
         * TODO: document {@code fullName}.
         *
         * @return TODO: describe
         */
        @Override
        public String fullName() {
            return POINT;
        }

        /**
         * TODO: document {@code shortName}.
         *
         * @return TODO: describe
         */
        @Override
        public String shortName() {
            return cmd;
        }

        /**
         * TODO: document {@code distance}.
         *
         * @param pt TODO: describe
         * @return TODO: describe
         */
        @Override
        public double distance(PointND pt) {
            if (pt instanceof PointND.Hex) {
                PointND.Hex other = (PointND.Hex) pt;
                return Math.max(Math.max(Math.abs(this.q - other.q), Math.abs(this.r - other.r)),
                        Math.abs(this.s - other.s));
            } else {
                return Integer.MIN_VALUE;
            }
        }

        /**
         * TODO: document {@code pixelToHexCoords}.
         *
         * @param x TODO: describe
         * @param y TODO: describe
         * @return TODO: describe
         */
        public static double[] pixelToHexCoords(double x, double y) {
            double q = (root3over3 * x - 1.0 / NUM_3_0 * y);
            double r = (NUM_2_0 / NUM_3_0 * y);
            double s = -q - r;
            return new double[] { q, r, s };
        }

        /**
         * TODO: document {@code getRightUpVector}.
         *
         * @return TODO: describe
         */
        public static Vector2f getRightUpVector() {
            return new Vector2f((float) (root3over2 * 1), NUM_1_5);
        }

        /**
         * TODO: document {@code getRightDownVector}.
         *
         * @return TODO: describe
         */
        public static Vector2f getRightDownVector() {
            return new Vector2f((float) (root3 * 1 + root3over2 * -1), -NUM_1_5);
        }

        /**
         * TODO: document {@code getHorizontalVector}.
         *
         * @return TODO: describe
         */
        public static Vector2f getHorizontalVector() {
            return new Vector2f((float) (root3 * 1), 0);
        }

        /**
         * TODO: document {@code getScreenY}.
         *
         * @return TODO: describe
         */
        @Override
        public double getScreenY() {
            return NUM_1_5_2 * r;
        }

        /**
         * TODO: document {@code getScreenX}.
         *
         * @return TODO: describe
         */
        @Override
        public double getScreenX() {
            return root3 * q + root3over2 * r;
        }

        /**
         * TODO: document {@code hexCoordsToPixel}.
         *
         * @param q TODO: describe
         * @param r TODO: describe
         * @return TODO: describe
         */
        public static Vector2f hexCoordsToPixel(float q, float r) {
            return new Vector2f((float) (root3 * q + root3over2 * r), (float) (NUM_1_5_2 * r));
        }

        /**
         * TODO: document {@code hexCoordsToPixel}.
         *
         * @param hexCoords TODO: describe
         * @return TODO: describe
         */
        public static Vector2f hexCoordsToPixel(double[] hexCoords) {
            return new Vector2f((float) (root3 * hexCoords[0] + root3over2 * hexCoords[1]),
                    (float) (NUM_1_5_2 * hexCoords[1]));
        }

        /**
         * TODO: document {@code toCoordString}.
         *
         * @return TODO: describe
         */
        @Override
        public String toCoordString() {
            return "Q:" + (int) q + " R:"
                    + (int) r + " S:" + (int) s;
        }
    }
}
