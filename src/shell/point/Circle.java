package shell.point;

import java.util.ArrayList;

import shell.exceptions.TerminalParseException;
import shell.terminal.commands.OptionList;

public class Circle extends PointCollection {
    public static String cmd = "circ";
    public static OptionList opts = new OptionList("c", "circ", "circle");

    public static ArrayList<PointND> parse(String[] args, int startIdx) throws TerminalParseException {
        Circle c = parseCircle(args, startIdx);
        return c.points;
    }

    public static Circle parseCircle(String[] args, int startIdx) throws TerminalParseException {
        if (args.length - startIdx == 0) {
            return new Circle();
        }
        double xCenter = java.lang.Double.parseDouble(args[startIdx]);
        double yCenter = java.lang.Double.parseDouble(args[startIdx + 1]);
        double radius = java.lang.Double.parseDouble(args[startIdx + 2]);
        int numPoints = java.lang.Integer.parseInt(args[startIdx + 3]);
        double rotation = Math.PI * java.lang.Double.parseDouble(args[startIdx + 4]) / 180.0;
        return new Circle(xCenter, yCenter, radius, numPoints, rotation);
    }

    @Override
    public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
        PointCollection c = parseCircle(args, startIdx);
        return c;
    }

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

    @Override
    public String desc() {
        return "a regular polygon with n points";
    }

    @Override
    public String usage() {
        return "usage: add circle [x center(double)] [y center(double)] [radius (double)] [number of points (int)] [rotation degrees (double)]";
    }

    @Override
    public int argLength() {
        return 5;
    }

    @Override
    public OptionList options() {
        return opts;
    }

    double xCenter;
    double yCenter;
    double radius;
    int numPoints;
    double rotation;
    ArrayList<PointND> points;

    public Circle() {
        xCenter = 0.0;
        yCenter = 0.0;
        radius = 10;
        numPoints = 10;
        rotation = 0;
        points = realizePoints();
    }

    public Circle(double xCenter, double yCenter, double radius, int numPoints, double rotation) {
        this.xCenter = xCenter;
        this.yCenter = yCenter;
        this.radius = radius;
        this.numPoints = numPoints;
        this.rotation = rotation;
        points = realizePoints();
    }

    @Override
    public String toFileString() {
        return "CIRCLE " + xCenter + " " + yCenter + " " + radius + " " + numPoints + " " + rotation;
    }

    @Override
    public String fullName() {
        return "circle";
    }

    @Override
    public String shortName() {
        return cmd;
    }

}
