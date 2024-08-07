package shell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;

import shell.ui.PointSetPath;

import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;

public class FileManagement {

    /**
     * Imports the point set and optimal tsp path from a file
     * 
     * @param f
     * @return the optimal PointSetPath
     */
    public static PointSetPath importFromFile(File f) {
        try {

            BufferedReader br = new BufferedReader(new FileReader(f));
            ArrayList<PointND> lines = new ArrayList<PointND>();
            String line = br.readLine();
            PointSet ps = new PointSet();
            Path2D path = new GeneralPath(GeneralPath.WIND_NON_ZERO);
            Shell tsp = new Shell();

            boolean flag = true, first = true;
            int index = 0;
            DistanceMatrix d = null;
            HashMap<Integer, PointND> lookUp = new HashMap<>();
            ArrayList<Integer> answerOrder = new ArrayList<>();
            while (line != null) {
                if (flag == true) {
                    String[] cords = line.split(" ");
                    Point2D pt2d = null;
                    if (cords[0].equals("CIRCLE")) {
                        System.out.println("CIRCLE FOUND!");
                        double xCenter = java.lang.Double.parseDouble(cords[1]);
                        double yCenter = java.lang.Double.parseDouble(cords[2]);
                        double radius = java.lang.Double.parseDouble(cords[3]);
                        int numPoints = java.lang.Integer.parseInt(cords[4]);
                        double radians = 2 * Math.PI / ((double) numPoints);
                        for (int i = 0; i < numPoints; i++) {
                            double xCoord = radius * Math.cos(i * radians) + xCenter;
                            double yCoord = radius * Math.sin(i * radians) + yCenter;
                            PointND pt = new PointND.Double(index, xCoord, yCoord);
                            pt2d = pt.toPoint2D();
                            lookUp.put(index, pt);
                            lines.add(pt);
                            ps.add(pt);
                            tsp.add(pt);

                            if (first) {
                                path.moveTo(pt2d.getX(), pt2d.getY());
                                first = false;
                            } else {
                                path.lineTo(pt2d.getX(), pt2d.getY());
                            }
                            index++;
                        }
                    } else if (cords[0].equals("LINE")) {
                        System.out.println("LINE FOUND!");
                        double xStart = java.lang.Double.parseDouble(cords[1]);
                        double yStart = java.lang.Double.parseDouble(cords[2]);
                        double xEnd = java.lang.Double.parseDouble(cords[3]);
                        double yEnd = java.lang.Double.parseDouble(cords[4]);
                        int numPoints = java.lang.Integer.parseInt(cords[5]);
                        double slopeX = (xEnd - xStart) / ((double) numPoints);
                        double slopeY = (yEnd - yStart) / ((double) numPoints);
                        for (int i = 0; i < numPoints; i++) {
                            double xCoord = (slopeX*i) + xStart;
                            double yCoord = (slopeY*i) + yStart;
                            PointND pt = new PointND.Double(index, xCoord, yCoord);
                            pt2d = pt.toPoint2D();
                            lookUp.put(index, pt);
                            lines.add(pt);
                            ps.add(pt);
                            tsp.add(pt);

                            if (first) {
                                path.moveTo(pt2d.getX(), pt2d.getY());
                                first = false;
                            } else {
                                path.lineTo(pt2d.getX(), pt2d.getY());
                            }
                            index++;
                        }
                    } else if (cords[0].equals("ARC")) {
                        System.out.println("CIRCLE FOUND!");
                        double xCenter = java.lang.Double.parseDouble(cords[1]);
                        double yCenter = java.lang.Double.parseDouble(cords[2]);
                        double radius = java.lang.Double.parseDouble(cords[3]);
                        int numPoints = java.lang.Integer.parseInt(cords[4]);
                        double startAngle = java.lang.Double.parseDouble(cords[5]) * (Math.PI / 180);
                        double endAngle = java.lang.Double.parseDouble(cords[6]) * (Math.PI / 180);
                        double radians = Math.abs(endAngle - startAngle) / ((double) numPoints);
                        for (int i = 0; i < numPoints; i++) {
                            double xCoord = radius * Math.cos(i * radians + startAngle) + xCenter;
                            double yCoord = radius * Math.sin(i * radians + startAngle) + yCenter;
                            PointND pt = new PointND.Double(index, xCoord, yCoord);
                            pt2d = pt.toPoint2D();
                            lookUp.put(index, pt);
                            lines.add(pt);
                            ps.add(pt);
                            tsp.add(pt);

                            if (first) {
                                path.moveTo(pt2d.getX(), pt2d.getY());
                                first = false;
                            } else {
                                path.lineTo(pt2d.getX(), pt2d.getY());
                            }
                            index++;
                        }
                    } else if (cords[0].equals("WH")) {
                        System.out.println("WORMHOLEFOUND!");
                        if (d == null) {
                            d = new DistanceMatrix(ps);
                        }
                        int firstPointId = java.lang.Integer.parseInt(cords[1]);
                        int secondPointId = java.lang.Integer.parseInt(cords[2]);
                        PointND wormHole = d.addDummyNode(index, lookUp.get(firstPointId),
                                lookUp.get(secondPointId));
                        int insertIdx = firstPointId;
                        if (firstPointId > secondPointId) {
                            insertIdx = secondPointId;
                        }
                        pt2d = wormHole.toPoint2D();
                        lines.add(insertIdx + 1, wormHole);
                        ps.add(insertIdx + 1, wormHole);
                        tsp.add(insertIdx + 1, wormHole);
                        lookUp.put(wormHole.getID(), wormHole);

                        if (first) {
                            path.moveTo(pt2d.getX(), pt2d.getY());
                            first = false;
                        } else {
                            path.lineTo(pt2d.getX(), pt2d.getY());
                        }

                        index++;
                    } else if (cords[0].equals("ANS")) {
                        for (int i = 1; i < cords.length; i++) {
                            answerOrder.add(java.lang.Integer.parseInt(cords[i]));
                        }
                    } else {
                        PointND pt = new PointND.Double(index, java.lang.Double.parseDouble(cords[1]),
                                java.lang.Double.parseDouble(cords[2]));
                        pt2d = pt.toPoint2D();
                        lookUp.put(index, pt);
                        lines.add(pt);
                        ps.add(pt);
                        tsp.add(pt);

                        if (first) {
                            path.moveTo(pt2d.getX(), pt2d.getY());
                            first = false;
                        } else {
                            path.lineTo(pt2d.getX(), pt2d.getY());
                        }

                        index++;
                    }

                }
                if (line.contains("NODE_COORD_SECTION")) {
                    flag = true;
                }
                line = br.readLine();

            }
            br.close();
            System.out.println(tsp);
            System.out.println(lookUp);
            if (answerOrder.size() > 0) {
                Shell newAns = new Shell();
                int insertLoc = 0;
                for (Integer i : answerOrder) {
                    PointND vp = lookUp.get(i);
                    newAns.add(insertLoc, vp);
                    insertLoc++;
                }
                tsp = newAns;
            }

            return new PointSetPath(ps, path, tsp, d);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;

    }

    public static void exportNewToFile() {

    }

}
