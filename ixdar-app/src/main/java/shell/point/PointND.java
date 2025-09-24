package shell.point;

import java.io.Serializable;
import java.util.ArrayList;

import org.joml.Vector2f;

import shell.PointSet;
import shell.exceptions.TerminalParseException;
import shell.render.sdf.SDFCircle;
import shell.terminal.commands.OptionList;

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
public abstract class PointND extends SDFCircle implements PointCollection, Cloneable {
    public static OptionList opts = new OptionList("p", "pt", "point");

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

        @Override
        public int hashCode() {
            return this.getID();
        }

        /**
         * @return the dimension of the vector
         */
        @Override
        public int getDim() {
            return fs.length;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public double getCoord(int dim) {
            return (double) fs[dim];
        }

        /**
         * {@inheritDoc}
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
                sb.append(", ");
            }
            sb.append(fs[fs.length - 1]);
            sb.append("]");
            return this.getID() + " ";
        }

        @Override
        public String toFileString() {
            String res = this.ID + " ";
            for (int i = 0; i < fs.length; i++) {
                res += String.format("%.4f", fs[i]) + " ";
            }
            return res;
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

        @Override
        public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
            PointCollection c = parse(args, startIdx);
            return c;
        }

        @Override
        public String usage() {
            return "add point [coord 1(double)] ... [coord n(double)]";
        }

        @Override
        public String desc() {
            return "a point in N dimensional space";
        }

        @Override
        public int argLength() {
            return -1;
        }

        @Override
        public int minArgLength() {
            return -1;
        }

        @Override
        public OptionList options() {
            return opts;
        }

        @Override
        public ArrayList<PointND> realizePoints() {
            ArrayList<PointND> lst = new ArrayList<>();
            lst.add(this);
            return lst;
        }

        @Override
        public String fullName() {
            return "point";
        }

        @Override
        public String shortName() {
            return cmd;
        }
    }

    public String cmd = "pt";

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
         * @param hexCoordinates the n coordinates of the newly constructed
         *                       {@code PointND}
         * 
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
         * @param hexCoordinates the n coordinates of the newly constructed
         *                       {@code PointND}
         * @param ID             for comparison purposes across basis
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
         * Constructs a {@code PointND} as the centtrroid of the specified PointSet
         * 
         * @param ps
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

        @Override
        public int hashCode() {
            return this.getID();
        }

        @Override
        public int getDim() {
            return ds.length;
        }

        /**
         * {@inheritDoc}
         * 
         */
        @Override
        public double getCoord(int dim) {
            if (dim >= ds.length) {
                return 0.0;
            }
            return (double) ds[dim];
        }

        /**
         * {@inheritDoc}
         * 
         */
        @Override
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
        @Override
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

        @Override
        public String toFileString() {
            String res = this.ID + " ";
            for (int i = 0; i < ds.length; i++) {
                res += String.format("%.4f", ds[i]) + " ";
            }
            return res;
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
            return ds;
        }

        @Override
        public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
            PointCollection c = parse(args, startIdx);
            return c;
        }

        @Override
        public String usage() {
            return "add point [coord 1(double)] ... [coord n(double)]";
        }

        @Override
        public String desc() {
            return "a point in N dimensional space";
        }

        @Override
        public int argLength() {
            return -1;
        }

        @Override
        public int minArgLength() {
            return -1;
        }

        @Override
        public OptionList options() {
            return opts;
        }

        @Override
        public ArrayList<PointND> realizePoints() {
            ArrayList<PointND> lst = new ArrayList<>();
            lst.add(this);
            return lst;
        }

        @Override
        public String fullName() {
            return "point";
        }

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

        public static OptionList opts = new OptionList("hex", "hx");
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
         * 
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
         * @param hexCoordinates the n coordinates of the newly constructed
         *                       {@code PointND}
         * 
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
         * @param q
         * @param r
         * @param s
         */
        public Hex(int ID, int q, int r, int s) {
            this.setID(ID);
            this.q = q;
            this.r = r;
            this.s = s;
        }

        public Hex(int[] coords) {
            this.setID(maxID);
            this.q = coords[0];
            this.r = coords[1];
            this.s = coords[2];
        }

        @Override
        public int hashCode() {
            return this.getID();
        }

        @Override
        public int getDim() {
            return 3;
        }

        /**
         * {@inheritDoc}
         * 
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
         * {@inheritDoc}
         * 
         */
        @Override
        public void setLocation(double... ds) {
            q = (int) ds[0];
            r = (int) ds[1];
            s = (int) ds[2];
        }

        /**
         * {@inheritDoc}
         * 
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
            sb.append(", ");
            sb.append(r);
            sb.append(", ");
            sb.append(s);
            sb.append("]");
            return this.getID() + "";
        }

        @Override
        public String toFileString() {
            return "HEX " + q + " " + r + " " + s;
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
            return new double[] { q, r, s };
        }

        @Override
        public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
            return Hex.parse(args, startIdx);
        }

        public static PointND parse(String[] args, int startIdx) throws TerminalParseException {
            if (args.length - startIdx != 3) {
                throw new TerminalParseException(
                        "expected 3 coordinates to parse Hex Knot got " + (args.length - startIdx));
            }
            int[] coords = new int[3];
            for (int i = 0; i < coords.length; i++) {
                coords[i] = java.lang.Integer.parseInt(args[startIdx + i]);
            }
            PointND pt = new Hex(coords);
            return pt;
        }

        @Override
        public String usage() {
            return "add point [q, r, s]";
        }

        @Override
        public String desc() {
            return "a point in 3-dimensional hex space";
        }

        @Override
        public int argLength() {
            return -1;
        }

        @Override
        public int minArgLength() {
            return -1;
        }

        @Override
        public OptionList options() {
            return opts;
        }

        @Override
        public ArrayList<PointND> realizePoints() {
            ArrayList<PointND> lst = new ArrayList<>();
            lst.add(this);
            return lst;
        }

        @Override
        public String fullName() {
            return "point";
        }

        @Override
        public String shortName() {
            return cmd;
        }

        private static final double root3over3 = 0.577350269;
        private static final double root3over2 = 0.866025404;
        private static final double root3 = 1.73205081;

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

        public static double[] pixelToHexCoords(double x, double y) {
            double q = (root3over3 * x - 1.0 / 3.0 * y);
            double r = (2.0 / 3.0 * y);
            double s = -q - r;
            return new double[] { q, r, s };
        }

        public static Vector2f getRightUpVector() {
            return new Vector2f((float) (root3over2 * 1), 1.5f);
        }

        public static Vector2f getRightDownVector() {
            return new Vector2f((float) (root3 * 1 + root3over2 * -1), -1.5f);
        }

        public static Vector2f getHorizontalVector() {
            return new Vector2f((float) (root3 * 1), 0);
        }

        @Override
        public double getScreenY() {
            return 1.5 * r;
        }

        @Override
        public double getScreenX() {
            return root3 * q + root3over2 * r;
        }

        public static Vector2f hexCoordsToPixel(float q, float r) {
            return new Vector2f((float) (root3 * q + root3over2 * r), (float) (1.5 * r));
        }

        public static Vector2f hexCoordsToPixel(double[] hexCoords) {
            return new Vector2f((float) (root3 * hexCoords[0] + root3over2 * hexCoords[1]),
                    (float) (1.5 * hexCoords[1]));
        }

        @Override
        public String toCoordString() {
            return "Q:" + (int) q + " R:"
                    + (int) r + " S:" + (int) s;
        }
    }

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
     * This is an abstract class that cannot be instantiated directly. Type-specific
     * implementation subclasses are available for instantiation and provide a
     * number of formats for storing the information necessary to satisfy the
     * various accessor methods below.
     *
     * 
     */
    protected PointND() {
    }

    protected int ID = -1;

    private boolean isCentroid = false;

    private boolean isNSphereCenter = false;

    private boolean isDummyNode = false;

    private static int maxID = 0;

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
     * @return a clone of this instance.
     * @exception OutOfMemoryError if there is not enough memory.
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
     * Converts the N dimensional Knot to a 2 Dimensional Knot for graphing purposes
     * 
     * @return a {@code Point2D} that consists of the first 2 coordinates of this
     *         point
     */
    public Point2D toPoint2D() {
        if (this.isDummyNode) {
            return new Point2D.Double(-1000000, -1000000);
        }
        return new Point2D.Double(this.getScreenX(), this.getScreenY());
    }

    public int getID() {
        return ID;
    }

    public void setID(int ID) {
        if (ID >= maxID) {
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

    public double getScreenX() {
        if (this.isDummyNode) {
            return -1000000;
        }
        return getCoord(0);
    }

    public double getScreenY() {
        if (this.isDummyNode) {
            return -1000000;
        }
        return getCoord(1);
    }

    public String toCoordString() {
        return "X:" + (int) this.getScreenX() + " Y:"
                + (int) this.getScreenY();
    }

    public static void resetIds() {
        maxID = 0;
    }
}
