package shell.file;

import shell.point.Point2D;
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
import shell.Toggle;
import shell.exceptions.TerminalParseException;
import shell.point.Arc;
import shell.point.Circle;
import shell.point.Grid;
import shell.point.Ix;
import shell.point.Line;
import shell.point.PointND;
import shell.point.Triangle;
import shell.shell.Shell;

public class FileManagement {

    public static final String solutionsFolder = "./src/test/solutions/";

    public static final String testFileCacheLocation = "./src/test/cache/cache";

    public static final String cacheFolder = "./src/test/cache/";

    public static final String subGraphUnitTestFolder = "./test/unit/subgraphs/";

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
        if (!shell.utils.Compat.isBlank(cachedLocation)) {
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
            FileInfo fi = new FileInfo();
            while (line != null) {
                if (fi.flag == true) {
                    String[] args = line.split(" ");
                    if (Circle.opts.contains(args[0])) {
                        // CIRCLE
                        ArrayList<PointND> points = Circle.parse(args, 1);
                        addPoints(points, fi);
                    } else if (Line.opts.contains(args[0])) {
                        // LINE
                        ArrayList<PointND> points = Line.parse(args, 1);
                        addPoints(points, fi);
                    } else if (Triangle.opts.contains(args[0])) {
                        // TRIANGLE
                        ArrayList<PointND> points = Triangle.parse(args, 1);
                        addPoints(points, fi);
                    } else if (Arc.opts.contains(args[0])) {
                        // ARC
                        ArrayList<PointND> points = Arc.parse(args, 1);
                        addPoints(points, fi);
                    } else if (args[0].equals("WH")) {
                        // WORMHOLE
                        if (fi.d == null) {
                            fi.d = new DistanceMatrix(fi.ps);
                        }
                        int firstPointId = java.lang.Integer.parseInt(args[1]);
                        int secondPointId = java.lang.Integer.parseInt(args[2]);
                        PointND wormHole = fi.d.addDummyNode(fi.index, fi.lookUp.get(firstPointId),
                                fi.lookUp.get(secondPointId));
                        int insertIdx = firstPointId;
                        if (firstPointId > secondPointId) {
                            insertIdx = secondPointId;
                        }
                        fi.lines.add(insertIdx + 1, wormHole);
                        fi.ps.add(insertIdx + 1, wormHole);
                        fi.tsp.add(insertIdx + 1, wormHole);
                        fi.lookUp.put(wormHole.getID(), wormHole);

                        fi.index++;
                    } else if (args[0].equals("ANS")) {
                        // ANS
                        for (int i = 1; i < args.length; i++) {
                            fi.answerOrder.add(java.lang.Integer.parseInt(args[i]));
                        }
                    } else if (Ix.opts.contains(args[0])) {
                        // LOAD
                        PointSetPath retTup = Ix.parseFull(args, 1);
                        for (PointND pt : retTup.ps) {
                            fi.lookUp.put(fi.index, pt);
                            fi.lines.add(pt);
                            fi.ps.add(pt);
                            fi.tsp.add(pt);

                            fi.index++;
                        }
                        if (retTup.d != null) {
                            fi.d = new DistanceMatrix(fi.ps);
                        }
                        if (retTup.grid != null) {
                            fi.grid = retTup.grid;
                        }
                    } else if (PointND.Hex.opts.contains(args[0])) {
                        // HEX
                        PointND pt = PointND.Hex.parse(args, 1);
                        addPoint(pt, fi);
                    } else if (args[0].equals("FLAG")) {
                        // FLAG
                        if (args[1].equals("REMOVE_DUPLICATES")) {
                            fi.removeDuplicates = true;
                        }
                        if (args[1].equals("SHOW_GRID")) {
                            fi.showGrid = true;
                        }
                    } else if (args[0].equals("TOGGLE") || args[0].equals("TGL")) {
                        for (Toggle t : Toggle.values()) {
                            if (args[1].equals(t.name()) || args[1].equals(t.shortName())) {
                                t.value = Boolean.parseBoolean(args[2]);
                            }
                        }
                    } else if (args[0].contains("//")) {
                        // COMMENT
                        fi.comments.add(line);
                    } else {
                        PointND pt = new PointND.Double(fi.index, java.lang.Double.parseDouble(args[1]),
                                java.lang.Double.parseDouble(args[2]));

                        addPoint(pt, fi);
                    }
                }

                if (line.contains("NODE_COORD_SECTION")) {
                    fi.flag = true;
                }
                line = br.readLine();
                fi.lineNumber++;

            }
            br.close();
            if (fi.answerOrder.size() > 0) {
                Shell newAns = new Shell();
                int insertLoc = 0;
                for (Integer i : fi.answerOrder) {
                    PointND vp = fi.lookUp.get(i);
                    newAns.add(insertLoc, vp);
                    insertLoc++;
                }
                fi.tsp = newAns;
            }
            if (fi.removeDuplicates && fi.duplicatePointIndexes.size() > 0) {
                removeDuplicates(f, fi.duplicatePointIndexes);
            }
            if (fi.showGrid) {
                fi.grid.showGrid();
            }
            if (fi.grid == null) {
                fi.grid = new Grid.HexGrid();
                fi.grid.showGrid();
            }
            return new PointSetPath(fi.ps, fi.tsp, fi.d, fi.comments, fi.grid);
        } catch (NumberFormatException | IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static void addPoint(PointND pt, FileInfo fi) throws TerminalParseException {
        if (fi.grid == null) {
            if (pt instanceof PointND.Double || pt instanceof PointND.Float) {
                fi.grid = new Grid.CartesianGrid();
            } else if (pt instanceof PointND.Hex) {
                fi.grid = new Grid.HexGrid();
            }
        } else {
            if (!fi.grid.allowsPoint(pt)) {
                throw new TerminalParseException("Expected all points to be in: " + fi.grid.allowableTypes()
                        + " but found point of type: " + pt.getClass());
            }
        }
        if (fi.ps.contains(pt)) {
            System.out.println("Duplicated found: " + fi.index);
            fi.duplicatePointIndexes.add(fi.lineNumber);
        } else {
            fi.lookUp.put(fi.index, pt);
            fi.lines.add(pt);
            fi.ps.add(pt);
            fi.tsp.add(pt);
            fi.index++;
        }
    }

    public static void addPoints(ArrayList<PointND> points, FileInfo fi) throws TerminalParseException {
        for (int i = 0; i < points.size(); i++) {
            PointND pt = points.get(i);
            if (fi.grid == null) {
                if (pt instanceof PointND.Double || pt instanceof PointND.Float) {
                    fi.grid = new Grid.CartesianGrid();
                } else if (pt instanceof PointND.Hex) {
                    fi.grid = new Grid.HexGrid();
                }
            } else {
                if (!fi.grid.allowsPoint(pt)) {
                    throw new TerminalParseException("Expected all points to be in: " + fi.grid.allowableTypes()
                            + " but found point of type: " + pt.getClass());
                }
            }
            pt.setID(fi.index);
            fi.lookUp.put(fi.index, pt);
            fi.lines.add(pt);
            fi.ps.add(pt);
            fi.tsp.add(pt);
            fi.index++;
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

    public static void appendComment(File f, String comment) {
        try {
            String ansLine = "// " + comment;
            FileWriter fw = new FileWriter(f, true);
            BufferedWriter out = new BufferedWriter(fw);
            out.newLine();
            out.append(ansLine);
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

    public static void appendCutAns(File f) {
        List<String> lines = new ArrayList<String>();
        String line = null;
        try {
            FileReader fr = new FileReader(f);
            BufferedReader br = new BufferedReader(fr);
            while ((line = br.readLine()) != null) {
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
            e.printStackTrace();
            return;
        }
    }

}

class FileInfo {
    PointSet ps;
    Shell tsp;
    ArrayList<String> comments;
    boolean flag, first;
    int index;
    DistanceMatrix d;
    HashMap<Integer, PointND> lookUp;
    ArrayList<Integer> answerOrder;
    int lineNumber;
    ArrayList<Integer> duplicatePointIndexes;
    boolean removeDuplicates;
    boolean showGrid;
    ArrayList<PointND> lines;
    Grid grid;

    FileInfo() {

        ps = new PointSet();
        tsp = new Shell();
        comments = new ArrayList<>();
        flag = true;
        first = true;
        index = 0;
        d = null;
        lookUp = new HashMap<>();
        answerOrder = new ArrayList<>();
        lineNumber = 1;
        duplicatePointIndexes = new ArrayList<>();
        removeDuplicates = false;
        showGrid = false;
        lines = new ArrayList<PointND>();
    }
}