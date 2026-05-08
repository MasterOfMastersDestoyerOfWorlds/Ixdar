package ixdar.geometry.point;

import java.util.ArrayList;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.geometry.GeometryAnnotation;
import ixdar.common.exceptions.TerminalParseException;

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
     * TODO: document {@code Line}.
     */
    public Line() {
        xStart = -NUM_5_0;
        yStart = 0.0;
        xEnd = NUM_5_0;
        yEnd = 0.0;
        numPoints = NUM_10;
    }

    /**
     * TODO: document {@code Line}.
     *
     * @param xStart TODO: describe
     * @param yStart TODO: describe
     * @param numPoints TODO: describe
     * @param xEnd TODO: describe
     * @param yEnd TODO: describe
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
     * TODO: document {@code parse}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
     */
    public static ArrayList<PointND> parse(String[] args, int startIdx) throws TerminalParseException {
        Line l = parseLine(args, startIdx);
        return l.points;
    }

    /**
     * TODO: document {@code parseLine}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
     */
    public static Line parseLine(String[] args, int startIdx) throws TerminalParseException {
        if (args.length - startIdx == 0) {
            return new Line();
        }
        double xStart = java.lang.Double.parseDouble(args[startIdx]);
        double yStart = java.lang.Double.parseDouble(args[startIdx + 1]);
        double xEnd = java.lang.Double.parseDouble(args[startIdx + 2]);
        double yEnd = java.lang.Double.parseDouble(args[startIdx + NUM_3]);
        int numPoints = java.lang.Integer.parseInt(args[startIdx + NUM_4]);
        Line l = new Line(xStart, yStart, numPoints, xEnd, yEnd);
        return l;
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
        PointCollection c = parseLine(args, startIdx);
        return c;
    }

    /**
     * TODO: document {@code realizePoints}.
     *
     * @return TODO: describe
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
     * TODO: document {@code fullName}.
     *
     * @return TODO: describe
     */
    @Override
    public String fullName() {
        return LINE;
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
     * TODO: document {@code desc}.
     *
     * @return TODO: describe
     */
    @Override
    public String desc() {
        return "a line with n points";
    }

    /**
     * TODO: document {@code usage}.
     *
     * @return TODO: describe
     */
    @Override
    public String usage() {
        return "usage: add line [x start(double)] [y start(double)] [x end (double)] [y end (double)] [number of points (int)] ";
    }

    /**
     * TODO: document {@code argLength}.
     *
     * @return TODO: describe
     */
    @Override
    public int argLength() {
        return NUM_5;
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
     * TODO: document {@code toFileString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toFileString() {
        return "Line " + xStart + " " + yStart + " " + xEnd + " " + yEnd + " " + numPoints;
    }
}
