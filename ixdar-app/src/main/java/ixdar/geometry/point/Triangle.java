package ixdar.geometry.point;

import java.util.ArrayList;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.geometry.GeometryAnnotation;
import ixdar.common.exceptions.TerminalParseException;

@GeometryAnnotation(id = "tri")
public class Triangle implements Geometry, PointCollection {
    public static final String TRIANGLE = "triangle";
    public static final int NUM_3 = 3;
    public static final double NUM_180_0 = 180.0;
    public static final int NUM_10 = 10;

    public static OptionList opts = new OptionList("t", cmd, TRIANGLE);
    public static String cmd = "tri";

    double xCenter;
    double yCenter;
    double radius;
    double rotation;
    ArrayList<PointND> points;

    /**
     * TODO: document {@code Triangle}.
     *
     * @param xCenter TODO: describe
     * @param yCenter TODO: describe
     * @param radius TODO: describe
     * @param rotation TODO: describe
     */
    public Triangle(double xCenter, double yCenter, double radius, double rotation) {
        this.xCenter = xCenter;
        this.yCenter = yCenter;
        this.radius = radius;
        this.rotation = rotation;
        this.points = realizePoints();
    }

    /**
     * TODO: document {@code Triangle}.
     */
    public Triangle() {
        xCenter = 0.0;
        yCenter = 0.0;
        radius = NUM_10;
        rotation = 0;
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
        Triangle t = parseTriangle(args, startIdx);
        return t.points;
    }

    /**
     * TODO: document {@code parseTriangle}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code parseCollection}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
     */
    @Override
    public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
        PointCollection c = parseTriangle(args, startIdx);
        return c;
    }

    /**
     * TODO: document {@code desc}.
     *
     * @return TODO: describe
     */
    @Override
    public String desc() {
        return "a regular polygon with 3 points";
    }

    /**
     * TODO: document {@code usage}.
     *
     * @return TODO: describe
     */
    @Override
    public String usage() {
        return "usage: add triangle [x center(double)] [y center(double)] [radius (double)] [rotation degrees (double)]";
    }

    /**
     * TODO: document {@code argLength}.
     *
     * @return TODO: describe
     */
    @Override
    public int argLength() {
        return NUM_3;
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
        return "TRI " + xCenter + " " + yCenter + " " + radius + " " + " " + rotation;
    }

    /**
     * TODO: document {@code realizePoints}.
     *
     * @return TODO: describe
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
     * TODO: document {@code fullName}.
     *
     * @return TODO: describe
     */
    @Override
    public String fullName() {
        return TRIANGLE;
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
