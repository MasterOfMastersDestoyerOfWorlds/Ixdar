package ixdar.geometry.point;

import java.util.ArrayList;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.geometry.GeometryAnnotation;
import ixdar.common.exceptions.TerminalParseException;

@GeometryAnnotation(id = "arc")
public class Arc implements Geometry, PointCollection {
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final int NUM_180 = 180;
    public static final int NUM_5 = 5;
    public static final int NUM_10 = 10;
    public static final int NUM_45 = 45;
    public static final int NUM_315 = 315;
    public static final int NUM_6 = 6;
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
     * TODO: document {@code Arc}.
     */
    public Arc() {
        xCenter = 0.0;
        yCenter = 0.0;
        radius = NUM_10;
        numPoints = NUM_10;
        startAngle = NUM_45;
        endAngle = NUM_315;
        points = realizePoints();

    }

    /**
     * TODO: document {@code Arc}.
     *
     * @param xCenter TODO: describe
     * @param yCenter TODO: describe
     * @param radius TODO: describe
     * @param numPoints TODO: describe
     * @param startAngle TODO: describe
     * @param endAngle TODO: describe
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
     * TODO: document {@code parse}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
     */
    public static ArrayList<PointND> parse(String[] args, int startIdx) throws TerminalParseException {
        Arc arc = parseArc(args, startIdx);
        return arc.points;
    }

    /**
     * TODO: document {@code parseArc}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
     */
    public static Arc parseArc(String[] args, int startIdx) throws TerminalParseException {
        if (args.length - startIdx == 0) {
            return new Arc();
        }
        double xCenter = java.lang.Double.parseDouble(args[startIdx]);
        double yCenter = java.lang.Double.parseDouble(args[startIdx + 1]);
        double radius = java.lang.Double.parseDouble(args[startIdx + 2]);
        int numPoints = java.lang.Integer.parseInt(args[startIdx + NUM_3]);
        double startAngle = java.lang.Double.parseDouble(args[startIdx + NUM_4]) * (Math.PI / NUM_180);
        double endAngle = java.lang.Double.parseDouble(args[startIdx + NUM_5]) * (Math.PI / NUM_180);
        return new Arc(xCenter, yCenter, radius, numPoints, startAngle, endAngle);
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
        PointCollection c = parseArc(args, startIdx);
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
     * TODO: document {@code desc}.
     *
     * @return TODO: describe
     */
    @Override
    public String desc() {
        return "an arc of a circle with n points";
    }

    /**
     * TODO: document {@code usage}.
     *
     * @return TODO: describe
     */
    @Override
    public String usage() {
        return "usage: add arc [x center(double)] [y center(double)] [radius (double)] [number of points (int)] [start angle degrees (double)] [end angle degrees (double)]";
    }

    /**
     * TODO: document {@code argLength}.
     *
     * @return TODO: describe
     */
    @Override
    public int argLength() {
        return NUM_6;
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
        return "ARC " + xCenter + " " + yCenter + " " + radius + " " + numPoints + " " + startAngle + " " + endAngle;
    }

    /**
     * TODO: document {@code fullName}.
     *
     * @return TODO: describe
     */
    @Override
    public String fullName() {
        return cmd;
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
