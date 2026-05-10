package ixdar.geometry.point;

import java.util.ArrayList;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.geometry.GeometryAnnotation;
import ixdar.common.exceptions.TerminalParseException;

/**
 * Straight-line segment sampled at evenly-spaced points, exposed on the
 * terminal as {@code add ln} (also {@code l} / {@code line}). Stores the two
 * endpoints and the number of vertices produced between them inclusive.
 */
@GeometryAnnotation(id = "ln")
public class Line implements Geometry, PointCollection {
    public static final String LINE = "line";
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final int NUM_5 = 5;
    public static final double NUM_5_0 = 5.0;
    public static final int NUM_10 = 10;
    public static String cmd = "ln";
    public static OptionList opts = new OptionList("l", cmd, LINE);

    double xStart;
    double yStart;
    int numPoints;
    double xEnd;
    double yEnd;
    ArrayList<PointND> points;

    /**
     * Default line: from {@code (-5, 0)} to {@code (5, 0)} sampled at
     * {@value #NUM_10} points. Vertices are not realized eagerly.
     */
    public Line() {
        xStart = -NUM_5_0;
        yStart = 0.0;
        xEnd = NUM_5_0;
        yEnd = 0.0;
        numPoints = NUM_10;
    }

    /**
     * Build a line segment between two endpoints and realize its vertices.
     *
     * @param xStart x of the starting endpoint
     * @param yStart y of the starting endpoint
     * @param numPoints total vertices placed along the segment (including both ends)
     * @param xEnd x of the ending endpoint
     * @param yEnd y of the ending endpoint
     */
    public Line(double xStart, double yStart, int numPoints, double xEnd, double yEnd) {
        this.xStart = xStart;
        this.yStart = yStart;
        this.numPoints = numPoints;
        this.xEnd = xEnd;
        this.yEnd = yEnd;
        this.points = realizePoints();
    }

    /**
     * Convenience that parses the args and returns just the realized vertices.
     *
     * @param args full terminal argument array
     * @param startIdx index of the first argument belonging to the line
     * @throws TerminalParseException if the slice is malformed
     * @return the sampled points of the parsed line
     */
    public static ArrayList<PointND> parse(String[] args, int startIdx) throws TerminalParseException {
        Line l = parseLine(args, startIdx);
        return l.points;
    }

    /**
     * Parse a {@code Line} from {@code [xStart, yStart, xEnd, yEnd, numPoints]}.
     * With zero trailing args, returns a default {@link #Line()}.
     *
     * @param args full terminal argument array
     * @param startIdx index of the first argument belonging to the line
     * @throws TerminalParseException if any token is not numeric
     * @return parsed line
     */
    public static Line parseLine(String[] args, int startIdx) throws TerminalParseException {
        if (args.length - startIdx == 0) {
            return new Line();
        }
        double xStart = Double.parseDouble(args[startIdx]);
        double yStart = Double.parseDouble(args[startIdx + 1]);
        double xEnd = Double.parseDouble(args[startIdx + 2]);
        double yEnd = Double.parseDouble(args[startIdx + NUM_3]);
        int numPoints = Integer.parseInt(args[startIdx + NUM_4]);
        Line l = new Line(xStart, yStart, numPoints, xEnd, yEnd);
        return l;
    }

    /**
     * {@link PointCollection} entry point; delegates to {@link #parseLine}.
     *
     * @param args full terminal argument array
     * @param startIdx index of the first argument belonging to the line
     * @throws TerminalParseException if the slice is malformed
     * @return parsed line as a {@link PointCollection}
     */
    @Override
    public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
        PointCollection c = parseLine(args, startIdx);
        return c;
    }

    /**
     * Sample {@code numPoints} equally-spaced vertices between the endpoints.
     *
     * @return newly built list of {@link PointND.Double} vertices
     */
    @Override
    public ArrayList<PointND> realizePoints() {
        ArrayList<PointND> points = new ArrayList<>();
        double slopeX = (xEnd - xStart) / ((double) numPoints - 1);
        double slopeY = (yEnd - yStart) / ((double) numPoints - 1);
        for (int i = 0; i < numPoints; i++) {
            double xCoord = (slopeX * i) + xStart;
            double yCoord = (slopeY * i) + yStart;
            PointND pt = new PointND.Double(xCoord, yCoord);
            points.add(pt);
        }
        return points;
    }

    /**
     * Long terminal name for this geometry.
     *
     * @return {@value #LINE}
     */
    @Override
    public String fullName() {
        return LINE;
    }

    /**
     * CLI shorthand for this geometry, matching the {@code @GeometryAnnotation} id.
     *
     * @return {@code "ln"}
     */
    @Override
    public String shortName() {
        return cmd;
    }

    /**
     * Short human-readable description shown in terminal help.
     *
     * @return description string
     */
    @Override
    public String desc() {
        return "a line with n points";
    }

    /**
     * Usage hint shown when the command is invoked with bad arguments.
     *
     * @return single-line usage string
     */
    @Override
    public String usage() {
        return "usage: add line [x start(double)] [y start(double)] [x end (double)] [y end (double)] [number of points (int)] ";
    }

    /**
     * Required positional arg count for parsing: {@value #NUM_5}.
     *
     * @return number of arguments {@link #parseLine} consumes
     */
    @Override
    public int argLength() {
        return NUM_5;
    }

    /**
     * Aliases the terminal accepts for this geometry ({@code l}, {@code ln},
     * {@code line}).
     *
     * @return shared option list
     */
    @Override
    public OptionList options() {
        return opts;
    }

    /**
     * Serialize this line to its {@code .ix} file representation.
     *
     * @return {@code "Line xStart yStart xEnd yEnd numPoints"} space-separated
     */
    @Override
    public String toFileString() {
        return "Line " + xStart + " " + yStart + " " + xEnd + " " + yEnd + " " + numPoints;
    }
}
