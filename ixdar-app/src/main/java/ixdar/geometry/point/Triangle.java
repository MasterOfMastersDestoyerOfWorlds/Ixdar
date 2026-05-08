package ixdar.geometry.point;

import java.util.ArrayList;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.geometry.GeometryAnnotation;
import ixdar.common.exceptions.TerminalParseException;

/**
 * Equilateral triangle sampled around a center, exposed on the terminal as
 * {@code add tri} (also {@code t} / {@code triangle}). It is a regular polygon
 * fixed at three vertices, parameterized by center, radius, and rotation.
 */
@GeometryAnnotation(id = "tri")
public class Triangle implements Geometry, PointCollection {
    public static final String TRIANGLE = "triangle";
    public static final int NUM_3 = 3;
    public static final double NUM_180_0 = 180.0;
    public static final int NUM_10 = 10;
    public static String cmd = "tri";
    public static OptionList opts = new OptionList("t", cmd, TRIANGLE);

    double xCenter;
    double yCenter;
    double radius;
    double rotation;
    ArrayList<PointND> points;

    /**
     * Build a triangle from explicit parameters and realize its three vertices.
     *
     * @param xCenter  x of the center
     * @param yCenter  y of the center
     * @param radius   distance from center to each vertex
     * @param rotation starting-angle offset in radians
     */
    public Triangle(double xCenter, double yCenter, double radius, double rotation) {
        this.xCenter = xCenter;
        this.yCenter = yCenter;
        this.radius = radius;
        this.rotation = rotation;
        this.points = realizePoints();
    }

    /**
     * Default triangle: centered at the origin, radius {@value #NUM_10}, zero
     * rotation. Note: vertices are not eagerly realized in this overload.
     */
    public Triangle() {
        xCenter = 0.0;
        yCenter = 0.0;
        radius = NUM_10;
        rotation = 0;
    }

    /**
     * Convenience that parses the args and returns just the realized vertices.
     *
     * @param args     full terminal argument array
     * @param startIdx index of the first argument belonging to the triangle
     * @throws TerminalParseException if the slice is malformed
     * @return the three sampled points of the parsed triangle
     */
    public static ArrayList<PointND> parse(String[] args, int startIdx) throws TerminalParseException {
        Triangle t = parseTriangle(args, startIdx);
        return t.points;
    }

    /**
     * Parse a {@code Triangle} from {@code [xCenter, yCenter, radius,
     * rotationDeg]}. With zero trailing args, returns a default
     * {@link #Triangle()}; rotation is supplied in degrees and converted to
     * radians.
     *
     * @param args     full terminal argument array
     * @param startIdx index of the first argument belonging to the triangle
     * @throws TerminalParseException if any token is not numeric
     * @return parsed triangle
     */
    public static Triangle parseTriangle(String[] args, int startIdx) throws TerminalParseException {
        if (args.length - startIdx == 0) {
            return new Triangle();
        }
        double xCenter = java.lang.Double.parseDouble(args[startIdx]);
        double yCenter = java.lang.Double.parseDouble(args[startIdx + 1]);
        double radius = java.lang.Double.parseDouble(args[startIdx + 2]);
        double rotation = Math.PI * java.lang.Double.parseDouble(args[startIdx + NUM_3]) / NUM_180_0;
        Triangle t = new Triangle(xCenter, yCenter, radius, rotation);
        return t;
    }

    /**
     * {@link PointCollection} entry point; delegates to {@link #parseTriangle}.
     *
     * @param args     full terminal argument array
     * @param startIdx index of the first argument belonging to the triangle
     * @throws TerminalParseException if the slice is malformed
     * @return parsed triangle as a {@link PointCollection}
     */
    @Override
    public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
        PointCollection c = parseTriangle(args, startIdx);
        return c;
    }

    /**
     * Short human-readable description shown in terminal help.
     *
     * @return description string
     */
    @Override
    public String desc() {
        return "a regular polygon with 3 points";
    }

    /**
     * Usage hint shown when the command is invoked with bad arguments.
     *
     * @return single-line usage string
     */
    @Override
    public String usage() {
        return "usage: add triangle [x center(double)] [y center(double)] [radius (double)] [rotation degrees (double)]";
    }

    /**
     * Required positional arg count for parsing: {@value #NUM_3}.
     *
     * @return number of arguments {@link #parseTriangle} consumes
     */
    @Override
    public int argLength() {
        return NUM_3;
    }

    /**
     * Aliases the terminal accepts for this geometry ({@code t}, {@code tri},
     * {@code triangle}).
     *
     * @return shared option list
     */
    @Override
    public OptionList options() {
        return opts;
    }

    /**
     * Serialize this triangle to its {@code .ix} file representation.
     *
     * @return {@code "TRI x y r  rot"} in space-separated form
     */
    @Override
    public String toFileString() {
        return "TRI " + xCenter + " " + yCenter + " " + radius + " " + " " + rotation;
    }

    /**
     * Sample three vertices evenly around the triangle, starting at
     * {@code rotation} radians.
     *
     * @return newly built list of {@link PointND.Double} vertices
     */
    @Override
    public ArrayList<PointND> realizePoints() {
        ArrayList<PointND> points = new ArrayList<>();
        int numPoints = NUM_3;
        double radians = 2 * Math.PI / ((double) numPoints);
        for (int i = 0; i < numPoints; i++) {
            double xCoord = radius * Math.cos(i * radians + rotation) + xCenter;
            double yCoord = radius * Math.sin(i * radians + rotation) + yCenter;
            PointND pt = new PointND.Double(xCoord, yCoord);
            points.add(pt);
        }
        return points;
    }

    /**
     * Long terminal name for this geometry.
     *
     * @return {@value #TRIANGLE}
     */
    @Override
    public String fullName() {
        return TRIANGLE;
    }

    /**
     * CLI shorthand for this geometry, matching the {@code @GeometryAnnotation} id.
     *
     * @return {@code "tri"}
     */
    @Override
    public String shortName() {
        return cmd;
    }
}
