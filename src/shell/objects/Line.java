package shell.objects;

import java.util.ArrayList;

import shell.exceptions.TerminalParseException;
import shell.terminal.commands.OptionList;

public class Line extends PointCollection {
    public static String cmd = "ln";
    public static OptionList opts = new OptionList("l", "ln", "line");

    public static ArrayList<PointND> parse(String[] args, int startIdx) throws TerminalParseException {
        Line l = parseLine(args, startIdx);
        return l.points;
    }

    public static Line parseLine(String[] args, int startIdx) throws TerminalParseException {
        if (args.length - startIdx == 0) {
            return new Line();
        }
        double xStart = java.lang.Double.parseDouble(args[startIdx]);
        double yStart = java.lang.Double.parseDouble(args[startIdx + 1]);
        double xEnd = java.lang.Double.parseDouble(args[startIdx + 2]);
        double yEnd = java.lang.Double.parseDouble(args[startIdx + 3]);
        int numPoints = java.lang.Integer.parseInt(args[startIdx + 4]);
        Line l = new Line(xStart, yStart, numPoints, xEnd, yEnd);
        return l;
    }

    @Override
    public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
        PointCollection c = parseLine(args, startIdx);
        return c;
    }

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

    @Override
    public String fullName() {
        return "line";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "a line with n points";
    }

    @Override
    public String usage() {
        return "usage: add line [x start(double)] [y start(double)] [x end (double)] [y end (double)] [number of points (int)] ";
    }

    @Override
    public int argLength() {
        return 5;
    }

    @Override
    public OptionList options() {
        return opts;
    }

    double xStart;
    double yStart;
    int numPoints;
    double xEnd;
    double yEnd;
    ArrayList<PointND> points;

    public Line() {
        xStart = -5.0;
        yStart = 0.0;
        xEnd = 5.0;
        yEnd = 0.0;
        numPoints = 10;
    }

    public Line(double xStart, double yStart, int numPoints, double xEnd, double yEnd) {
        this.xStart = xStart;
        this.yStart = yStart;
        this.numPoints = numPoints;
        this.xEnd = xEnd;
        this.yEnd = yEnd;
        this.points = realizePoints();
    }

    @Override
    public String toFileString() {
        return "Line " + xStart + " " + yStart + " " + xEnd + " " + yEnd + " " + numPoints;
    }
}
