package ixdar.geometry.point;

import java.util.ArrayList;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.geometry.GeometryAnnotation;
import ixdar.common.exceptions.TerminalParseException;

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
     * TODO: document {@code Circle}.
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
     * TODO: document {@code Circle}.
     *
     * @param xCenter TODO: describe
     * @param yCenter TODO: describe
     * @param radius TODO: describe
     * @param numPoints TODO: describe
     * @param rotation TODO: describe
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
     * TODO: document {@code parse}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
     */
    public static ArrayList<PointND> parse(String[] args, int startIdx) throws TerminalParseException {
        Circle c = parseCircle(args, startIdx);
        return c.points;
    }

    /**
     * TODO: document {@code parseCircle}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code parseCollection}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
     */
    @Override
    public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
        PointCollection c = parseCircle(args, startIdx);
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
     * TODO: document {@code desc}.
     *
     * @return TODO: describe
     */
    @Override
    public String desc() {
        return "a regular polygon with n points";
    }

    /**
     * TODO: document {@code usage}.
     *
     * @return TODO: describe
     */
    @Override
    public String usage() {
        return "usage: add circle [x center(double)] [y center(double)] [radius (double)] [number of points (int)] [rotation degrees (double)]";
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
        return "CIRCLE " + xCenter + " " + yCenter + " " + radius + " " + numPoints + " " + rotation;
    }

    /**
     * TODO: document {@code fullName}.
     *
     * @return TODO: describe
     */
    @Override
    public String fullName() {
        return CIRCLE;
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
