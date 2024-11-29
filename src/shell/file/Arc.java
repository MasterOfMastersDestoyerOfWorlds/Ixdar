package shell.file;

import java.util.ArrayList;

import shell.PointND;
import shell.terminal.commands.OptionList;

public class Arc extends PointCollection implements FileStringable {

    public static OptionList opts = new OptionList("a", "arc");

    public static ArrayList<PointND> parse(String[] args, int startIdx) {
        Arc arc = parseArc(args, startIdx);
        return arc.points;
    }

    public static Arc parseArc(String[] args, int startIdx) {
        double xCenter = java.lang.Double.parseDouble(args[startIdx]);
        double yCenter = java.lang.Double.parseDouble(args[startIdx + 1]);
        double radius = java.lang.Double.parseDouble(args[startIdx + 2]);
        int numPoints = java.lang.Integer.parseInt(args[startIdx + 3]);
        double startAngle = java.lang.Double.parseDouble(args[startIdx + 4]) * (Math.PI / 180);
        double endAngle = java.lang.Double.parseDouble(args[startIdx + 5]) * (Math.PI / 180);
        return new Arc(xCenter, yCenter, radius, numPoints, startAngle, endAngle);
    }

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

    double xCenter;
    double yCenter;
    double radius;
    int numPoints;
    double startAngle;
    double endAngle;
    ArrayList<PointND> points;

    public Arc() {
        xCenter = 0.0;
        yCenter = 0.0;
        radius = 10;
        numPoints = 10;
        startAngle = 45;
        endAngle = 315;
        points = realizePoints();

    }

    public Arc(double xCenter, double yCenter, double radius, int numPoints, double startAngle, double endAngle) {
        this.xCenter = xCenter;
        this.yCenter = yCenter;
        this.radius = radius;
        this.numPoints = numPoints;
        this.startAngle = startAngle;
        this.endAngle = endAngle;
        points = realizePoints();
    }

    @Override
    public String desc() {
        return "an arc of a circle with n points";
    }

    @Override
    public String usage() {
        return "usage: add arc [x center(double)] [y center(double)] [radius (double)] [number of points (int)] [start angle degrees (double)] [end angle degrees (double)]";
    }

    @Override
    public int argLength() {
        return 6;
    }

    @Override
    public String toFileString() {
        return "ARC " + xCenter + " " + yCenter + " " + radius + " " + numPoints + " " + startAngle + " " + endAngle;
    }

}
