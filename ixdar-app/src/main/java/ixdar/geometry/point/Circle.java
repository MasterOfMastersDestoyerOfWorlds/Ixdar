package ixdar.geometry.point;

import java.util.ArrayList;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.geometry.GeometryAnnotation;
import ixdar.common.exceptions.TerminalParseException;

/**
 * Regular polygon sampled around a center, exposed on the terminal as
 * {@code add circ} (also {@code c} / {@code circle}). Stores center, radius,
 * vertex count, and rotation, and produces evenly-spaced points on the circle.
 */
@GeometryAnnotation(id = "circ")
public class Circle implements Geometry, PointCollection {
    public static final String CIRCLE = "circle";
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final double NUM_180_0 = 180.0;
    public static final int NUM_5 = 5;
    public static final int NUM_10 = 10;
    public static String cmd = "circ";
    public static OptionList opts = new OptionList("c", cmd, CIRCLE);

    double xCenter;
    double yCenter;
    double radius;
    int numPoints;
    double rotation;
    ArrayList<PointND> points;

    /**
     * Default circle: centered at the origin, radius and vertex count both
     * {@value #NUM_10}, zero rotation. Realizes its points eagerly.
     */
    public Circle() {
        xCenter = 0.0;
        yCenter = 0.0;
        radius = NUM_10;
        numPoints = NUM_10;
        rotation = 0;
        points = realizePoints();
    }

    /**
     * Build a circle from explicit parameters and realize its vertices.
     *
     * @param xCenter x of the center
     * @param yCenter y of the center
     * @param radius circle radius
     * @param numPoints number of evenly-spaced vertices to sample
     * @param rotation starting-angle offset in radians
     */
    public Circle(double xCenter, double yCenter, double radius, int numPoints, double rotation) {
        this.xCenter = xCenter;
        this.yCenter = yCenter;
        this.radius = radius;
        this.numPoints = numPoints;
        this.rotation = rotation;
        points = realizePoints();
    }

    /**
     * Convenience that parses the args and returns just the realized vertices.
     *
     * @param args full terminal argument array
     * @param startIdx index of the first argument belonging to the circle
     * @throws TerminalParseException if the slice is malformed
     * @return the sampled points of the parsed circle
     */
    public static ArrayList<PointND> parse(String[] args, int startIdx) throws TerminalParseException {
        Circle c = parseCircle(args, startIdx);
        return c.points;
    }

    /**
     * Parse a {@code Circle} from {@code [xCenter, yCenter, radius, numPoints,
     * rotationDeg]}. With zero trailing args, returns a default {@link #Circle()};
     * the rotation argument is given in degrees and converted to radians.
     *
     * @param args full terminal argument array
     * @param startIdx index of the first argument belonging to the circle
     * @throws TerminalParseException if any token is not numeric
     * @return parsed circle
     */
    public static Circle parseCircle(String[] args, int startIdx) throws TerminalParseException {
        if (args.length - startIdx == 0) {
            return new Circle();
        }
        double xCenter = java.lang.Double.parseDouble(args[startIdx]);
        double yCenter = java.lang.Double.parseDouble(args[startIdx + 1]);
        double radius = java.lang.Double.parseDouble(args[startIdx + 2]);
        int numPoints = java.lang.Integer.parseInt(args[startIdx + NUM_3]);
        double rotation = Math.PI * java.lang.Double.parseDouble(args[startIdx + NUM_4]) / NUM_180_0;
        return new Circle(xCenter, yCenter, radius, numPoints, rotation);
    }

    /**
     * {@link PointCollection} entry point; delegates to {@link #parseCircle}.
     *
     * @param args full terminal argument array
     * @param startIdx index of the first argument belonging to the circle
     * @throws TerminalParseException if the slice is malformed
     * @return parsed circle as a {@link PointCollection}
     */
    @Override
    public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
        PointCollection c = parseCircle(args, startIdx);
        return c;
    }

    /**
     * Sample {@code numPoints} vertices evenly around the circle starting at
     * {@code rotation} radians.
     *
     * @return newly built list of {@link PointND.Double} vertices
     */
    @Override
    public ArrayList<PointND> realizePoints() {
        ArrayList<PointND> points = new ArrayList<>();
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
     * Short human-readable description shown in terminal help.
     *
     * @return description string
     */
    @Override
    public String desc() {
        return "a regular polygon with n points";
    }

    /**
     * Usage hint shown when the command is invoked with bad arguments.
     *
     * @return single-line usage string
     */
    @Override
    public String usage() {
        return "usage: add circle [x center(double)] [y center(double)] [radius (double)] [number of points (int)] [rotation degrees (double)]";
    }

    /**
     * Required positional arg count for parsing: {@value #NUM_5}.
     *
     * @return number of arguments {@link #parseCircle} consumes
     */
    @Override
    public int argLength() {
        return NUM_5;
    }

    /**
     * Aliases the terminal accepts for this geometry ({@code c}, {@code circ},
     * {@code circle}).
     *
     * @return shared option list
     */
    @Override
    public OptionList options() {
        return opts;
    }

    /**
     * Serialize this circle to its {@code .ix} file representation.
     *
     * @return {@code "CIRCLE x y r n rot"} in space-separated form
     */
    @Override
    public String toFileString() {
        return "CIRCLE " + xCenter + " " + yCenter + " " + radius + " " + numPoints + " " + rotation;
    }

    /**
     * Long terminal name for this geometry.
     *
     * @return {@value #CIRCLE}
     */
    @Override
    public String fullName() {
        return CIRCLE;
    }

    /**
     * CLI shorthand for this geometry, matching the {@code @GeometryAnnotation} id.
     *
     * @return {@code "circ"}
     */
    @Override
    public String shortName() {
        return cmd;
    }

}
