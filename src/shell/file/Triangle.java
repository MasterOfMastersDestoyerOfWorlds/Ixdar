package shell.file;

import java.util.ArrayList;

import shell.PointND;
import shell.terminal.commands.OptionList;

public class Triangle extends PointCollection implements FileStringable {

    public static OptionList opts = new OptionList("t", "tri", "triangle");

    public static ArrayList<PointND> parse(String[] args, int startIdx) {
        Triangle t = parseTriangle(args, startIdx);
        return t.points;
    }

    public static Triangle parseTriangle(String[] args, int startIdx) {
        double xCenter = java.lang.Double.parseDouble(args[startIdx]);
        double yCenter = java.lang.Double.parseDouble(args[startIdx + 1]);
        double radius = java.lang.Double.parseDouble(args[startIdx + 2]);
        double rotation = Math.PI * java.lang.Double.parseDouble(args[startIdx + 3]) / 180.0;
        Triangle t = new Triangle(xCenter, yCenter, radius, rotation);
        return t;
    }

    @Override
    public String desc() {
        return "a regular polygon with 3 points";
    }

    @Override
    public String usage() {
        return "usage: add triangle [x center(double)] [y center(double)] [radius (double)]";
    }

    @Override
    public int argLength() {
        return 3;
    }

    double xCenter;
    double yCenter;
    double radius;
    double rotation;
    ArrayList<PointND> points;

    public Triangle(double xCenter, double yCenter, double radius, double rotation) {
        this.xCenter = xCenter;
        this.yCenter = yCenter;
        this.radius = radius;
        this.rotation = rotation;
        this.points = realizePoints();
    }

    public Triangle() {
        xCenter = 0.0;
        yCenter = 0.0;
        radius = 10;
        rotation = 0;
    }

    @Override
    public String toFileString() {
        return "TRI " + xCenter + " " + yCenter + " " + radius + " " + " " + rotation;
    }

    @Override
    public ArrayList<PointND> realizePoints() {
        ArrayList<PointND> points = new ArrayList<>();
        int numPoints = 3;
        double radians = 2 * Math.PI / ((double) numPoints);
        for (int i = 0; i < numPoints; i++) {
            double xCoord = radius * Math.cos(i * radians + rotation) + xCenter;
            double yCoord = radius * Math.sin(i * radians + rotation) + yCenter;
            PointND pt = new PointND.Double(xCoord, yCoord);
            points.add(pt);
        }
        return points;
    }
}
