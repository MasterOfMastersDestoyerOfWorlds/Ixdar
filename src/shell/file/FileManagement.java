package shell.file;

import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import shell.DistanceMatrix;
import shell.PointSet;
import shell.exceptions.FileParseException;
import shell.exceptions.TerminalParseException;
import shell.objects.Arc;
import shell.objects.Circle;
import shell.objects.Grid;
import shell.objects.Ix;
import shell.objects.Line;
import shell.objects.PointND;
import shell.objects.Triangle;
import shell.shell.Shell;

public class FileManagement {

    public static final String solutionsFolder = "./src/test/solutions/";

    public static final String testFileCacheLocation = "./src/test/cache/cache";

    public static final String cacheFolder = "./src/test/cache/";

    public static final String subGraphUnitTestFolder = "./src/test/unit/subgraphs/";

    public static File getTestFile(String fileName) {
        String[] parts = fileName.split("_");
        if (fileName.contains(".ix")) {
            return new File(solutionsFolder + parts[0].replace(".ix", "") + "/" + fileName);
        }
        return new File(solutionsFolder + parts[0] + "/" + fileName + ".ix");
    }

    public static File getTempFile(String fileName) {
        File temp = null;
        try {
            temp = File.createTempFile("temp", ".ix");
            temp.deleteOnExit();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return temp;
    }

    public static String getTestFileCache() {
        File cache = new File(testFileCacheLocation);
        try (BufferedReader br = new BufferedReader(new FileReader(cache))) {
            String line = br.readLine();
            br.close();
            return line;
        } catch (Exception e) {

        }
        return "";
    }

    public static void updateTestFileCache(String cachedLocation) {
        if (!cachedLocation.isBlank()) {
            File cache = new File(testFileCacheLocation);
            try (FileWriter fw = new FileWriter(cache)) {
                BufferedWriter out = new BufferedWriter(fw);
                out.write(cachedLocation);
                out.flush();
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    static PointSet ps;
    static Path2D path;
    static Shell tsp;
    static ArrayList<String> comments;
    static ArrayList<Manifold> manifolds;
    static Manifold m;
    static boolean flag, first;
    static int index;
    static DistanceMatrix d;
    static HashMap<Integer, PointND> lookUp;
    static ArrayList<Integer> answerOrder;
    static int lineNumber;
    static ArrayList<Integer> duplicatePointIndexes;
    static boolean removeDuplicates;
    static ArrayList<PointND> lines;
    static Grid grid;

    public static void initImport() {
        ps = new PointSet();
        path = new GeneralPath(GeneralPath.WIND_NON_ZERO);
        tsp = new Shell();
        comments = new ArrayList<>();
        manifolds = new ArrayList<>();
        m = null;
        flag = true;
        first = true;
        index = 0;
        d = null;
        lookUp = new HashMap<>();
        answerOrder = new ArrayList<>();
        lineNumber = 1;
        duplicatePointIndexes = new ArrayList<>();
        removeDuplicates = false;
        lines = new ArrayList<PointND>();
    }

    /**
     * Imports the point set and optimal tsp path from a file
     * 
     * @param f
     * @return the optimal PointSetPath
     * @throws TerminalParseException
     */
    public static PointSetPath importFromFile(File f) throws TerminalParseException {

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            initImport();
            while (line != null) {
                if (flag == true) {
                    String[] args = line.split(" ");
                    Point2D pt2d = null;
                    if (Circle.opts.contains(args[0])) {
                        System.out.println("CIRCLE FOUND!");
                        ArrayList<PointND> points = Circle.parse(args, 1);
                        addPoints(points);
                    } else if (Line.opts.contains(args[0])) {
                        System.out.println("LINE FOUND!");
                        ArrayList<PointND> points = Line.parse(args, 1);
                        addPoints(points);
                    } else if (Triangle.opts.contains(args[0])) {
                        System.out.println("TRIANGLE FOUND!");
                        ArrayList<PointND> points = Triangle.parse(args, 1);
                        addPoints(points);
                    } else if (Arc.opts.contains(args[0])) {
                        System.out.println("ARC FOUND!");
                        ArrayList<PointND> points = Arc.parse(args, 1);
                        addPoints(points);
                    } else if (args[0].equals("WH")) {
                        System.out.println("WORMHOLEFOUND!");
                        if (d == null) {
                            d = new DistanceMatrix(ps);
                        }
                        int firstPointId = java.lang.Integer.parseInt(args[1]);
                        int secondPointId = java.lang.Integer.parseInt(args[2]);
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
                    } else if (args[0].equals("MANIFOLD")) {
                        System.out.println("MANIFOLD FOUND!");
                        m = new Manifold(java.lang.Integer.parseInt(args[1]), java.lang.Integer.parseInt(args[2]),
                                java.lang.Integer.parseInt(args[3]), java.lang.Integer.parseInt(args[4]),
                                args[5].equals("C"));
                        try {
                            m.parse(args);
                        } catch (FileParseException fpe) {
                            throw new FileParseException(f.toPath(), f.getName(), lineNumber);
                        }
                        manifolds.add(m);

                    } else if (args[0].equals("ANS")) {
                        for (int i = 1; i < args.length; i++) {
                            answerOrder.add(java.lang.Integer.parseInt(args[i]));
                        }
                    } else if (Ix.opts.contains(args[0])) {
                        PointSetPath retTup = Ix.parseFull(args, 1);
                        manifolds.addAll(retTup.manifolds);
                        for (PointND pt : retTup.ps) {
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
                        if (retTup.d != null) {
                            d = new DistanceMatrix(ps);
                        }
                    } else if (PointND.Hex.opts.contains(args[0])) {
                        PointND pt = PointND.Hex.parse(args, 1);
                        addPoint(pt);
                    } else if (args[0].equals("FLAG")) {
                        if (args[1].equals("REMOVE_DUPLICATES")) {
                            removeDuplicates = true;
                        }

                    } else if (args[0].contains("//")) {
                        comments.add(line);
                    } else {
                        PointND pt = new PointND.Double(index, java.lang.Double.parseDouble(args[1]),
                                java.lang.Double.parseDouble(args[2]));

                        addPoint(pt);
                    }
                }

                if (line.contains("NODE_COORD_SECTION")) {
                    flag = true;
                }
                line = br.readLine();
                lineNumber++;

            }
            br.close();
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
            if (removeDuplicates && duplicatePointIndexes.size() > 0) {
                removeDuplicates(f, duplicatePointIndexes);
            }
            return new PointSetPath(ps, path, tsp, d, manifolds, comments, grid);
        } catch (NumberFormatException | IOException | FileParseException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static void addPoint(PointND pt) throws TerminalParseException {
        if (grid == null) {
            if (pt instanceof PointND.Double || pt instanceof PointND.Float) {
                grid = new Grid.CartesianGrid();
            } else if (pt instanceof PointND.Hex) {
                grid = new Grid.HexGrid();
            }
        } else {
            if (!grid.allowsPoint(pt)) {
                throw new TerminalParseException("Expected all points to be in: " + grid.allowableTypes()
                        + " but found point of type: " + pt.getClass());
            }
        }
        if (ps.contains(pt)) {
            System.out.println("Duplicated found: " + index);
            duplicatePointIndexes.add(lineNumber);
        } else {
            lookUp.put(index, pt);
            lines.add(pt);
            ps.add(pt);
            tsp.add(pt);

            if (first) {
                path.moveTo(pt.getScreenX(), pt.getScreenY());
                first = false;
            } else {
                path.lineTo(pt.getScreenX(), pt.getScreenY());
            }

            index++;
        }
    }

    public static void addPoints(ArrayList<PointND> points) throws TerminalParseException {
        for (int i = 0; i < points.size(); i++) {
            PointND pt = points.get(i);
            if (grid == null) {
                if (pt instanceof PointND.Double || pt instanceof PointND.Float) {
                    grid = new Grid.CartesianGrid();
                } else if (pt instanceof PointND.Hex) {
                    grid = new Grid.HexGrid();
                }
            } else {
                if (!grid.allowsPoint(pt)) {
                    throw new TerminalParseException("Expected all points to be in: " + grid.allowableTypes()
                            + " but found point of type: " + pt.getClass());
                }
            }
            pt.setID(index);
            lookUp.put(index, pt);
            lines.add(pt);
            ps.add(pt);
            tsp.add(pt);
            if (first) {
                path.moveTo(pt.getScreenX(), pt.getScreenY());
                first = false;
            } else {
                path.lineTo(pt.getScreenX(), pt.getScreenY());
            }
            index++;
        }
    }

    private static void removeDuplicates(File f, ArrayList<Integer> duplicatePointIndexes) {

        List<String> lines = new ArrayList<String>();
        String line = null;
        try {
            FileReader fr = new FileReader(f);
            BufferedReader br = new BufferedReader(fr);
            int lineNumber = 1;
            while ((line = br.readLine()) != null) {
                if (!duplicatePointIndexes.contains(lineNumber)) {
                    lines.add(line + "\n");
                }
                lineNumber++;
            }
            fr.close();
            br.close();

            FileWriter fw = new FileWriter(f);
            BufferedWriter out = new BufferedWriter(fw);
            for (String s : lines)
                out.write(s);
            out.flush();
            out.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void copyFileContents(File src, File dest) {

        List<String> lines = new ArrayList<String>();
        String line = null;
        try {
            FileReader fr = new FileReader(src);
            BufferedReader br = new BufferedReader(fr);
            while ((line = br.readLine()) != null) {
                lines.add(line + "\n");
            }
            fr.close();
            br.close();

            FileWriter fw = new FileWriter(dest);
            BufferedWriter out = new BufferedWriter(fw);
            for (String s : lines)
                out.write(s);
            out.flush();
            out.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void appendAns(File f, Shell ans) {

        List<String> lines = new ArrayList<String>();
        String line = null;
        try {
            FileReader fr = new FileReader(f);
            BufferedReader br = new BufferedReader(fr);
            boolean foundAns = false;
            String ansLine = "ANS ";
            for (int i = 0; i < ans.size(); i++) {
                ansLine += ans.get(i).getID() + " ";
            }
            while ((line = br.readLine()) != null) {
                if (line.contains("ANS ")) {
                    line = ansLine;
                    foundAns = true;
                }
                lines.add(line + "\n");
            }
            if (!foundAns) {
                lines.add(ansLine + "\n");
            }
            fr.close();
            br.close();

            FileWriter fw = new FileWriter(f);
            BufferedWriter out = new BufferedWriter(fw);
            for (String s : lines)
                out.write(s);
            out.flush();
            out.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void appendLine(File f, String appLine) {

        try (FileWriter fw = new FileWriter(f, true)) {
            fw.write(appLine + "\n");
            fw.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void writeLines(File f, ArrayList<String> lines) {

        try (FileWriter fw = new FileWriter(f, false)) {
            for (String s : lines) {
                fw.write(s + "\n");
            }
            fw.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void appendCutAns(File f, ArrayList<Manifold> manifold) {
        List<String> lines = new ArrayList<String>();
        String line = null;
        try {
            FileReader fr = new FileReader(f);
            BufferedReader br = new BufferedReader(fr);
            int i = 0;
            while ((line = br.readLine()) != null) {
                if (line.contains("MANIFOLD ")) {
                    Manifold m = manifold.get(i);
                    if (m.shorterPathFound) {
                        System.out.println(line);
                        line = m.toFileString();
                    }
                    i++;
                }
                lines.add(line + "\n");
            }
            fr.close();
            br.close();

            FileWriter fw = new FileWriter(f);
            BufferedWriter out = new BufferedWriter(fw);
            for (String s : lines)
                out.write(s);
            out.flush();
            out.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void rewriteSolutionFile(File file, Shell shell) {

        FileWriter fw;
        try {
            fw = new FileWriter(file);
            BufferedWriter out = new BufferedWriter(fw);
            for (PointND pn : shell) {
                out.write(pn.toFileString());
                out.newLine();
            }
            out.flush();
            out.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public static void writeSubGraphTest(String fileName, String template) {
        File unitTest = new File(subGraphUnitTestFolder + fileName);
        try {
            unitTest.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        String[] lines = template.split("\n");
        FileWriter fw;
        try {
            fw = new FileWriter(unitTest);
            BufferedWriter out = new BufferedWriter(fw);
            for (String line : lines) {
                out.write(line);
                out.newLine();
            }
            out.flush();
            out.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return;
        }
    }

}
