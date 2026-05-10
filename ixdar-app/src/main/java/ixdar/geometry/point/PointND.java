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
     * Parse the trailing tokens of a terminal command into a {@code PointND.Double}.
     * Each token is treated as a {@code double} coordinate; an empty tail yields the
     * origin {@code (0, 0)}.
     *
     * @param args raw token array from the terminal
     * @param startIdx first token belonging to this point
     * @throws TerminalParseException never thrown by this implementation, but reserved
     *         so subclasses may signal token errors uniformly
     * @return a freshly constructed point of dimension {@code args.length - startIdx}
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
     * Number of coordinate dimensions stored by this point.
     *
     * @return the dimensionality {@code n}
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
     * @throws InternalError if {@link Cloneable} is not honored by a subclass; should never occur for {@code PointND}
     * @return a clone of this instance.
     * @see Cloneable
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
     * Stable point id used for cross-basis comparisons. {@code -1} means "not
     * yet assigned".
     *
     * @return the point's id
     */
    public int getID() {
        return ID;
    }

    /**
     * Assign this point's id and bump the static {@code maxID} watermark so
     * future auto-assignments stay unique.
     *
     * @param ID new id for this point
     */
    public void setID(int ID) {
        if (ID >= maxID) {
            maxID = ID + 1;
        }
        this.ID = ID;
    }

    /**
     * Whether this point has been flagged as a centroid by {@link #setCentroid()}.
     *
     * @return the centroid flag
     */
    public boolean isCentroid() {
        return isCentroid;
    }

    /**
     * Mark this point as a centroid (e.g. computed from a {@link PointSet}).
     */
    public void setCentroid() {
        this.isCentroid = true;
    }

    /**
     * Whether this point has been flagged as the center of an N-sphere by
     * {@link #setNSphereCenter()}.
     *
     * @return the N-sphere-center flag
     */
    public boolean isNSphereCenter() {
        return isNSphereCenter;
    }

    /**
     * Mark this point as the center of an N-sphere.
     */
    public void setNSphereCenter() {
        this.isNSphereCenter = true;
    }

    /**
     * Whether this point has been flagged as a dummy/sentinel node by
     * {@link #setDummyNode()}; dummy nodes are reported at a sentinel
     * off-screen location.
     *
     * @return the dummy-node flag
     */
    public boolean isDummyNode() {
        return isDummyNode;
    }

    /**
     * Mark this point as a dummy/sentinel node so its screen coordinates are
     * forced off-screen.
     */
    public void setDummyNode() {
        this.isDummyNode = true;
    }

    /**
     * Screen-space X coordinate (the first coordinate) for rendering. Dummy
     * nodes are shifted to a far-off sentinel location.
     *
     * @return the X coordinate or the dummy sentinel
     */
    public double getScreenX() {
        if (this.isDummyNode) {
            return NUM__1000000;
        }
        return getCoord(0);
    }
    /**
     * {@link #getScreenX()} narrowed to {@code float}.
     *
     * @return the X screen coordinate as a {@code float}
     */
    public float getScreenXf() {
        return (float) getScreenX();
    }

    /**
     * Screen-space Y coordinate (the second coordinate) for rendering. Dummy
     * nodes are shifted to a far-off sentinel location.
     *
     * @return the Y coordinate or the dummy sentinel
     */
    public double getScreenY() {
        if (this.isDummyNode) {
            return NUM__1000000;
        }
        return getCoord(1);
    }

    /**
     * {@link #getScreenY()} narrowed to {@code float}.
     *
     * @return the Y screen coordinate as a {@code float}
     */
    public float getScreenYf() {
        return (float) getScreenY();
    }

    /**
     * Format this point's screen-space {@code (X, Y)} for the status readout.
     *
     * @return string of the form {@code "X:<x> Y:<y>"}
     */
    public String toCoordString() {
        return "X:" + (int) this.getScreenX() + " Y:"
                + (int) this.getScreenY();
    }

    /**
     * Reset the auto-assigned id counter so the next implicit {@code setID}
     * starts at {@code 0}. Useful when reloading a fresh problem.
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
         * @param ID stable id used for cross-basis comparisons
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
         * @param ID stable id used for cross-basis comparisons
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
         * Hash by point id so equal {@link #getID() ids} collide as expected by
         * the {@code equals}/{@code hashCode} contract used elsewhere.
         *
         * @return {@link #getID()}
         */
        @Override
        public int hashCode() {
            return this.getID();
        }

        /**
         * Number of coordinates stored, i.e. {@code fs.length}.
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
         * @param dim coordinate index
         * @return the {@code dim}-th coordinate widened to {@code double}
         */
        @Override
        public double getCoord(int dim) {
            return (double) fs[dim];
        }

        /**
         * {@inheritDoc}.
         *
         * @param ds new coordinates; narrowed element-wise to {@code float}
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
         * Serialize as {@code "<id> <coord_0> <coord_1> ..."} with each
         * coordinate formatted to four decimal places.
         *
         * @return file-friendly representation
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
         * Coordinates as a fresh {@code double[]} (widened from the float
         * storage).
         *
         * @return per-dimension coordinate array
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
         * Delegate to {@link PointND#parse(String[], int)} so this point type
         * can act as the "command parser" for {@link PointCollection}.
         *
         * @param args raw token array from the terminal
         * @param startIdx first token belonging to this collection
         * @throws TerminalParseException if {@code parse} cannot decode {@code args}
         * @return the parsed point as a {@link PointCollection}
         */
        @Override
        public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
            PointCollection c = parse(args, startIdx);
            return c;
        }

        /**
         * Terminal usage hint describing the {@code add point} command.
         *
         * @return the usage string
         */
        @Override
        public String usage() {
            return ADD_POINT_COORD_1_DOUBLE_COORD_N_DOUBLE;
        }

        /**
         * Short, human-readable command description.
         *
         * @return description string
         */
        @Override
        public String desc() {
            return A_POINT_IN_N_DIMENSIONAL_SPACE;
        }

        /**
         * Required argument count, or {@code -1} for variadic.
         *
         * @return {@code -1} (variadic)
         */
        @Override
        public int argLength() {
            return -1;
        }

        /**
         * Minimum argument count, or {@code -1} for unbounded.
         *
         * @return {@code -1}
         */
        @Override
        public int minArgLength() {
            return -1;
        }

        /**
         * Command aliases under which this point type is registered.
         *
         * @return the shared {@code OptionList} for points
         */
        @Override
        public OptionList options() {
            return opts;
        }

        /**
         * Treat this point as a single-element {@link PointCollection} by
         * returning a one-entry list containing {@code this}.
         *
         * @return list containing this point
         */
        @Override
        public ArrayList<PointND> realizePoints() {
            ArrayList<PointND> lst = new ArrayList<>();
            lst.add(this);
            return lst;
        }

        /**
         * Long command name (always {@value PointND#POINT}).
         *
         * @return the long command name
         */
        @Override
        public String fullName() {
            return POINT;
        }

        /**
         * Short command name (defaults to {@value PointND#PT}).
         *
         * @return the current short command name
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
         * @param fs the n coordinates of the newly constructed {@code PointND}
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
         * @param fs the n coordinates of the newly constructed {@code PointND}
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
         * @param ps point set whose mean coordinate is computed; the resulting
         *           point has dimension {@code ps.getMaxDim()}
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
         * Hash by point id so equal {@link #getID() ids} collide as expected.
         *
         * @return {@link #getID()}
         */
        @Override
        public int hashCode() {
            return this.getID();
        }

        /**
         * Number of coordinates stored, i.e. {@code ds.length}.
         *
         * @return the dimension of the vector
         */
        @Override
        public int getDim() {
            return ds.length;
        }

        /**
         * {@inheritDoc}. Returns {@code 0.0} for indices past the trimmed end
         * of the storage array.
         *
         * @param dim coordinate index
         * @return the {@code dim}-th coordinate, or {@code 0.0} if beyond storage
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
         * @param ds new coordinate array (stored by reference)
         */
        @Override
        public void setLocation(double... ds) {
            this.ds = ds;
        }

        /**
         * {@inheritDoc}.
         *
         * @param fs new coordinates, widened element-wise to {@code double}
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
         * Serialize as {@code "<id> <coord_0> <coord_1> ..."} with each
         * coordinate formatted to four decimal places.
         *
         * @return file-friendly representation
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
         * @return the underlying coordinate array (no defensive copy)
         */
        @Override
        public double[] getCoordList() {
            return ds;
        }

        /**
         * Delegate to {@link PointND#parse(String[], int)} so this point type
         * can act as the "command parser" for {@link PointCollection}.
         *
         * @param args raw token array from the terminal
         * @param startIdx first token belonging to this collection
         * @throws TerminalParseException if {@code parse} cannot decode {@code args}
         * @return the parsed point as a {@link PointCollection}
         */
        @Override
        public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
            PointCollection c = parse(args, startIdx);
            return c;
        }

        /**
         * Terminal usage hint describing the {@code add point} command.
         *
         * @return the usage string
         */
        @Override
        public String usage() {
            return ADD_POINT_COORD_1_DOUBLE_COORD_N_DOUBLE;
        }

        /**
         * Short, human-readable command description.
         *
         * @return description string
         */
        @Override
        public String desc() {
            return A_POINT_IN_N_DIMENSIONAL_SPACE;
        }

        /**
         * Required argument count, or {@code -1} for variadic.
         *
         * @return {@code -1} (variadic)
         */
        @Override
        public int argLength() {
            return -1;
        }

        /**
         * Minimum argument count, or {@code -1} for unbounded.
         *
         * @return {@code -1}
         */
        @Override
        public int minArgLength() {
            return -1;
        }

        /**
         * Command aliases under which this point type is registered.
         *
         * @return the shared {@code OptionList} for points
         */
        @Override
        public OptionList options() {
            return opts;
        }

        /**
         * Treat this point as a single-element {@link PointCollection} by
         * returning a one-entry list containing {@code this}.
         *
         * @return list containing this point
         */
        @Override
        public ArrayList<PointND> realizePoints() {
            ArrayList<PointND> lst = new ArrayList<>();
            lst.add(this);
            return lst;
        }

        /**
         * Long command name (always {@value PointND#POINT}).
         *
         * @return the long command name
         */
        @Override
        public String fullName() {
            return POINT;
        }

        /**
         * Short command name (defaults to {@value PointND#PT}).
         *
         * @return the current short command name
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
         * @param q hex axial-q coordinate (right is positive)
         * @param r hex axial-r coordinate (up-right is positive)
         * @param s hex axial-s coordinate (up-left is positive); should satisfy {@code q + r + s = 0}
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
         * @param q hex axial-q coordinate (right is positive)
         * @param r hex axial-r coordinate (up-right is positive)
         * @param s hex axial-s coordinate (up-left is positive); should satisfy {@code q + r + s = 0}
         */
        public Hex(int ID, int q, int r, int s) {
            this.setID(ID);
            this.q = q;
            this.r = r;
            this.s = s;
        }

        /**
         * Construct a hex point from a {@code [q, r, s]} array with an
         * auto-assigned id.
         *
         * @param coords three-element array of axial coordinates
         */
        public Hex(int[] coords) {
            this.setID(maxID);
            this.q = coords[0];
            this.r = coords[1];
            this.s = coords[2];
        }

        /**
         * Hash by point id so equal {@link #getID() ids} collide as expected.
         *
         * @return {@link #getID()}
         */
        @Override
        public int hashCode() {
            return this.getID();
        }

        /**
         * Hex points always carry the {@code (q, r, s)} triple.
         *
         * @return {@value #NUM_3}
         */
        @Override
        public int getDim() {
            return NUM_3;
        }

        /**
         * {@inheritDoc}. Returns {@code Integer.MIN_VALUE} for indices outside
         * the {@code (q, r, s)} triple.
         *
         * @param dim coordinate index, {@code 0..2}
         * @return {@code q}, {@code r} or {@code s}
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
         * @param ds new coordinates {@code [q, r, s]}, narrowed to {@code int}
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
         * @param fs new coordinates {@code [q, r, s]}, narrowed to {@code int}
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
         * Serialize as {@code "HEX <q> <r> <s>"}.
         *
         * @return file-friendly representation
         */
        @Override
        public String toFileString() {
            return "HEX " + q + " " + r + " " + s;
        }

        /**
         * {@inheritDoc}.
         *
         * @return a freshly allocated {@code [q, r, s]} array
         */
        @Override
        public double[] getCoordList() {
            return new double[] { q, r, s };
        }

        /**
         * Delegate to {@link Hex#parse(String[], int)}.
         *
         * @param args raw token array from the terminal
         * @param startIdx first token belonging to this collection
         * @throws TerminalParseException when {@code args} does not contain exactly three coordinates
         * @return the parsed hex point as a {@link PointCollection}
         */
        @Override
        public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
            return Hex.parse(args, startIdx);
        }

        /**
         * Parse exactly three integer coordinates {@code (q, r, s)} into a new
         * {@link Hex}.
         *
         * @param args raw token array from the terminal
         * @param startIdx first token belonging to this point
         * @throws TerminalParseException if fewer or more than three coordinates are supplied
         * @return the newly constructed hex point
         */
        public static PointND parse(String[] args, int startIdx) throws TerminalParseException {
            if (args.length - startIdx != NUM_3) {
                throw new TerminalParseException(
                        "expected 3 coordinates to parse Hex Knot got " + (args.length - startIdx));
            }
            int[] coords = new int[NUM_3];
            for (int i = 0; i < coords.length; i++) {
                coords[i] = Integer.parseInt(args[startIdx + i]);
            }
            PointND pt = new Hex(coords);
            return pt;
        }

        /**
         * Terminal usage hint describing the {@code add point} command for hex
         * coordinates.
         *
         * @return the usage string
         */
        @Override
        public String usage() {
            return "add point [q, r, s]";
        }

        /**
         * Short, human-readable command description for hex points.
         *
         * @return description string
         */
        @Override
        public String desc() {
            return "a point in 3-dimensional hex space";
        }

        /**
         * Required argument count, or {@code -1} for variadic.
         *
         * @return {@code -1}
         */
        @Override
        public int argLength() {
            return -1;
        }

        /**
         * Minimum argument count, or {@code -1} for unbounded.
         *
         * @return {@code -1}
         */
        @Override
        public int minArgLength() {
            return -1;
        }

        /**
         * Command aliases under which this point type is registered.
         *
         * @return the {@code OptionList} for hex points ({@code hex}/{@code hx})
         */
        @Override
        public OptionList options() {
            return opts;
        }

        /**
         * Treat this point as a single-element {@link PointCollection} by
         * returning a one-entry list containing {@code this}.
         *
         * @return list containing this point
         */
        @Override
        public ArrayList<PointND> realizePoints() {
            ArrayList<PointND> lst = new ArrayList<>();
            lst.add(this);
            return lst;
        }

        /**
         * Long command name (always {@value PointND#POINT}).
         *
         * @return the long command name
         */
        @Override
        public String fullName() {
            return POINT;
        }

        /**
         * Short command name (defaults to {@value PointND#PT}).
         *
         * @return the current short command name
         */
        @Override
        public String shortName() {
            return cmd;
        }

        /**
         * Hex distance between this point and {@code pt} measured as the
         * Chebyshev distance over {@code (q, r, s)}. Returns
         * {@code Integer.MIN_VALUE} when {@code pt} is not a {@link Hex}.
         *
         * @param pt the other point
         * @return integer hex distance, or sentinel for incompatible types
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
         * Convert a pixel-space coordinate into fractional hex coordinates
         * {@code (q, r, s)} (typically rounded by callers to snap).
         *
         * @param x pixel-space X
         * @param y pixel-space Y
         * @return three-element array {@code [q, r, s]}
         */
        public static double[] pixelToHexCoords(double x, double y) {
            double q = (root3over3 * x - 1.0 / NUM_3_0 * y);
            double r = (NUM_2_0 / NUM_3_0 * y);
            double s = -q - r;
            return new double[] { q, r, s };
        }

        /**
         * Pixel-space basis vector pointing to the next hex up and to the right.
         *
         * @return the right-up basis vector
         */
        public static Vector2f getRightUpVector() {
            return new Vector2f((float) (root3over2 * 1), NUM_1_5);
        }

        /**
         * Pixel-space basis vector pointing to the next hex down and to the right.
         *
         * @return the right-down basis vector
         */
        public static Vector2f getRightDownVector() {
            return new Vector2f((float) (root3 * 1 + root3over2 * -1), -NUM_1_5);
        }

        /**
         * Pixel-space basis vector pointing to the next hex along the
         * horizontal (q) axis.
         *
         * @return the horizontal basis vector
         */
        public static Vector2f getHorizontalVector() {
            return new Vector2f((float) (root3 * 1), 0);
        }

        /**
         * Screen-space Y for this hex, computed from {@code r} only.
         *
         * @return the Y pixel coordinate
         */
        @Override
        public double getScreenY() {
            return NUM_1_5_2 * r;
        }

        /**
         * Screen-space X for this hex, computed from {@code q} and {@code r}.
         *
         * @return the X pixel coordinate
         */
        @Override
        public double getScreenX() {
            return root3 * q + root3over2 * r;
        }

        /**
         * Convert axial coordinates {@code (q, r)} to pixel space. The {@code s}
         * coordinate is implicit ({@code -q - r}).
         *
         * @param q axial-q coordinate
         * @param r axial-r coordinate
         * @return pixel-space {@code (x, y)}
         */
        public static Vector2f hexCoordsToPixel(float q, float r) {
            return new Vector2f((float) (root3 * q + root3over2 * r), (float) (NUM_1_5_2 * r));
        }

        /**
         * Convert axial coordinates from a {@code [q, r, ...]} array to pixel
         * space; only the first two entries are read.
         *
         * @param hexCoords array whose first two elements are {@code q} and {@code r}
         * @return pixel-space {@code (x, y)}
         */
        public static Vector2f hexCoordsToPixel(double[] hexCoords) {
            return new Vector2f((float) (root3 * hexCoords[0] + root3over2 * hexCoords[1]),
                    (float) (NUM_1_5_2 * hexCoords[1]));
        }

        /**
         * Format this hex point's coordinates as {@code "Q:.. R:.. S:.."}.
         *
         * @return readable hex coordinate string
         */
        @Override
        public String toCoordString() {
            return "Q:" + (int) q + " R:"
                    + (int) r + " S:" + (int) s;
        }
    }
}
