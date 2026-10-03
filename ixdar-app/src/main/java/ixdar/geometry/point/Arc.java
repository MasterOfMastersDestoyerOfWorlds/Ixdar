package ixdar.geometry.point;

import java.util.ArrayList;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.geometry.GeometryAnnotation;
import ixdar.common.exceptions.TerminalParseException;

/**
 * Section of a circle sampled between two angles, exposed on the terminal as
 * {@code add arc} (also {@code a}). Stores the center, radius, vertex count,
 * and start/end angles in radians.
 */
@GeometryAnnotation(id = "arc")
public class Arc implements Geometry, PointCollection {
    public static final int DEFAULT_RADIUS = 10;
    public static final int DEFAULT_NUM_POINTS = 10;
    public static final int DEFAULT_START_ANGLE = 45;
    public static final int DEFAULT_END_ANGLE = 315;
    public static final int ARG_COUNT = 6;
    public static String cmd = "arc";
    public static OptionList opts = new OptionList("a", cmd);

    double xCenter;
    double yCenter;
    double radius;
    int numPoints;
    double startAngle;
    double endAngle;
    ArrayList<PointND> points;

    /**
     * Default arc: centered at the origin, radius {@value #DEFAULT_RADIUS} and
     * vertex count {@value #DEFAULT_NUM_POINTS}, sweeping from
     * {@value #DEFAULT_START_ANGLE} to {@value #DEFAULT_END_ANGLE}
     * (interpreted as raw values, not converted from degrees). Realizes its
     * points eagerly.
     */
    public Arc() {
        xCenter = 0.0;
        yCenter = 0.0;
        radius = DEFAULT_RADIUS;
        numPoints = DEFAULT_NUM_POINTS;
        startAngle = DEFAULT_START_ANGLE;
        endAngle = DEFAULT_END_ANGLE;
        points = realizePoints();

    }

    /**
     * Build an arc from explicit parameters and realize its vertices.
     *
     * @param xCenter x of the center
     * @param yCenter y of the center
     * @param radius arc radius
     * @param numPoints number of evenly-spaced samples along the arc
     * @param startAngle starting sweep angle in radians
     * @param endAngle ending sweep angle in radians
     */
    public Arc(double xCenter, double yCenter, double radius, int numPoints, double startAngle, double endAngle) {
        this.xCenter = xCenter;
        this.yCenter = yCenter;
        this.radius = radius;
        this.numPoints = numPoints;
        this.startAngle = startAngle;
        this.endAngle = endAngle;
        points = realizePoints();
    }

    /**
     * Convenience that parses the args and returns just the realized vertices.
     *
     * @param args full terminal argument array
     * @param startIdx index of the first argument belonging to the arc
     * @throws TerminalParseException if the slice is malformed
     * @return the sampled points of the parsed arc
     */
    public static ArrayList<PointND> parse(String[] args, int startIdx) throws TerminalParseException {
        Arc arc = parseArc(args, startIdx);
        return arc.points;
    }

    /**
     * Parse an {@code Arc} from {@code [xCenter, yCenter, radius, numPoints,
     * startAngleDeg, endAngleDeg]}. With zero trailing args, returns a default
     * {@link #Arc()}; the angles are supplied in degrees and converted to
     * radians.
     *
     * @param args full terminal argument array
     * @param startIdx index of the first argument belonging to the arc
     * @throws TerminalParseException if any token is not numeric
     * @return parsed arc
     */
    public static Arc parseArc(String[] args, int startIdx) throws TerminalParseException {
        if (args.length - startIdx == 0) {
            return new Arc();
        }
        double xCenter = Double.parseDouble(args[startIdx]);
        double yCenter = Double.parseDouble(args[startIdx + 1]);
        double radius = Double.parseDouble(args[startIdx + 2]);
        int numPoints = Integer.parseInt(args[startIdx + 3]);
        double startAngle = Double.parseDouble(args[startIdx + 4]) * (Math.PI / 180);
        double endAngle = Double.parseDouble(args[startIdx + 5]) * (Math.PI / 180);
        return new Arc(xCenter, yCenter, radius, numPoints, startAngle, endAngle);
    }

    /**
     * {@link PointCollection} entry point; delegates to {@link #parseArc}.
     *
     * @param args full terminal argument array
     * @param startIdx index of the first argument belonging to the arc
     * @throws TerminalParseException if the slice is malformed
     * @return parsed arc as a {@link PointCollection}
     */
    @Override
    public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
        PointCollection c = parseArc(args, startIdx);
        return c;
    }

    /**
     * Sample {@code numPoints} vertices evenly between {@code startAngle} and
     * {@code endAngle} on the circle of {@code radius} around the center.
     *
     * @return newly built list of {@link PointND.Double} vertices
     */
    @Override
    public ArrayList<PointND> realizePoints() {
        ArrayList<PointND> points = new ArrayList<>();
        double radians = Math.abs(endAngle - startAngle) / ((double) numPoints);
        for (int i = 0; i < numPoints; i++) {
            double xCoord = radius * Math.cos(i * radians + startAngle) + xCenter;
            double yCoord = radius * Math.sin(i * radians + startAngle) + yCenter;
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
        return "an arc of a circle with n points";
    }

    /**
     * Usage hint shown when the command is invoked with bad arguments.
     *
     * @return single-line usage string
     */
    @Override
    public String usage() {
        return "usage: add arc [x center(double)] [y center(double)] [radius (double)] [number of points (int)] [start angle degrees (double)] [end angle degrees (double)]";
    }

    /**
     * Required positional arg count for parsing: {@value #ARG_COUNT}.
     *
     * @return number of arguments {@link #parseArc} consumes
     */
    @Override
    public int argLength() {
        return ARG_COUNT;
    }

    /**
     * Aliases the terminal accepts for this geometry ({@code a}, {@code arc}).
     *
     * @return shared option list
     */
    @Override
    public OptionList options() {
        return opts;
    }

    /**
     * Serialize this arc to its {@code .ix} file representation.
     *
     * @return {@code "ARC x y r n start end"} space-separated, with angles in radians
     */
    @Override
    public String toFileString() {
        return "ARC " + xCenter + " " + yCenter + " " + radius + " " + numPoints + " " + startAngle + " " + endAngle;
    }

    /**
     * Long terminal name for this geometry. (Same value as {@link #shortName()}
     * for arcs.)
     *
     * @return {@code "arc"}
     */
    @Override
    public String fullName() {
        return cmd;
    }

    /**
     * CLI shorthand for this geometry, matching the {@code @GeometryAnnotation} id.
     *
     * @return {@code "arc"}
     */
    @Override
    public String shortName() {
        return cmd;
    }

}
