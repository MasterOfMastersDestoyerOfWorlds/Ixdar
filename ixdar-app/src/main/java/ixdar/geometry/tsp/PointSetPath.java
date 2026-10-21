package ixdar.geometry.tsp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import ixdar.geometry.grid2d.Grid;
import ixdar.geometry.tsp.input.Arc;
import ixdar.geometry.tsp.input.Circle;
import ixdar.geometry.tsp.input.Ix;
import ixdar.geometry.tsp.input.Line;
import ixdar.geometry.tsp.input.Triangle;
import ixdar.geometry.tsp.knot.Knot;
import ixdar.geometry.tsp.knot.Segment;
import ixdar.geometry.tsp.points.PointND;
import ixdar.geometry.tsp.points.PointSet;
import ixdar.geometry.tsp.shell.DistanceMatrix;
import ixdar.geometry.tsp.shell.Shell;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.TerminalParseException;
import ixdar.gui.ui.actions.Action;
import ixdar.platform.Platforms;
import ixdar.platform.file.FileManagement;
import ixdar.platform.file.TextFile;
import ixdar.scenes.main.Toggle;

/**
 * The optimal tsp path in a pointset ps, and the {@code .ix} solution file format it is read
 * from and written back to.
 */
public class PointSetPath {
    public static final String EXPECTED_ALL_POINTS_TO_BE_IN = "Expected all points to be in: ";
    public static final String BUT_FOUND_POINT_OF_TYPE = " but found point of type: ";
    public static final String ANS = "ANS ";

    public static final String SUBGRAPH_UNIT_TEST_FOLDER = "./test/unit/subgraphs/";

    public PointSet ps;

    /** The answer tour stored in the file, which the move commands edit and write back. */
    public Shell tsp;
    public DistanceMatrix d;
    public ArrayList<String> comments;
    public Grid grid;

    /** The {@code .ix} file the point set was read from, {@code null} for one not read from a file. */
    public TextFile file;

    /** The scratch file {@code add} appends points to. */
    public TextFile tempFile;

    /** The working copy of {@link #tsp}, shuffled, that the knots are solved on. */
    public Shell tourShell;

    /** The knots solved from {@link #tourShell}, the top of the multi-level knot. */
    public ArrayList<Knot> resultKnots = new ArrayList<>();

    /** Single tours read off the knot, a candidate answer when there is exactly one. */
    public ArrayList<Shell> subPaths = new ArrayList<>();

    /** Height of the tallest solved knot, {@code -1} before any is solved. */
    public int totalLayers = -1;

    /** Length of {@link #tourShell} once solved. */
    public double tourLength;

    /** The colour knots are drawn in when neither the gradient nor the metro diagram is on. */
    public Color stickyColor = Color.TEAL;

    /** One colour per knot layer, the metro diagram's. */
    public ArrayList<Color> metroColors = new ArrayList<>();

    /** One colour per solved knot, the gradient's. */
    public ArrayList<Color> knotGradientColors = new ArrayList<>();

    /** The colour the showing view draws a knot in; the view supplies it. */
    public Function<Knot, Color> knotColor = knot -> Color.IXDAR;

    /** Brings a knot into the showing view, its layer and its camera; the view supplies it. */
    public Consumer<Knot> focusKnot = knot -> { };

    /** Highlights a knot in the showing view; the view supplies it. */
    public Consumer<Knot> hoverKnot = knot -> { };

    /** Drops the knot highlight; the view supplies it. */
    public Action clearHoverKnot = () -> { };

    /** Brings a segment into the showing view's camera; the view supplies it. */
    public Consumer<Segment> focusSegment = segment -> { };

    /** Highlights a segment in a colour in the showing view; the view supplies it. */
    public BiConsumer<Segment, Color> hoverSegment = (segment, color) -> { };

    /** Drops the segment highlight; the view supplies it. */
    public Action clearHoverSegment = () -> { };

    /**
     * Initializes the path and pointset variables.
     *
     * @param ps points read from the file
     * @param tsp ordered tour through {@code ps} (the answer / TSP path)
     * @param d cached distance matrix, or null if none was loaded
     * @param comments {@code //}-prefixed comment lines preserved from the source file
     * @param grid grid (cartesian / hex) the points belong to
     */
    public PointSetPath(PointSet ps, Shell tsp, DistanceMatrix d,
            ArrayList<String> comments, Grid grid) {
        this.ps = ps;
        this.tsp = tsp;
        this.d = d;
        this.comments = comments;
        this.grid = grid;
    }

    /**
     * Parse an {@code .ix} solution file: reads points, geometry primitives (circles, lines,
     * triangles, arcs, hex points), wormholes ({@code WH}), the answer order ({@code ANS}),
     * nested loads ({@code Ix}), flags ({@code REMOVE_DUPLICATES}, {@code SHOW_GRID}),
     * and toggle directives ({@code TOGGLE}/{@code TGL}). Parsing begins after the
     * {@code NODE_COORD_SECTION} marker.
     *
     * @param path path forwarded to {@link Platforms#get()}.{@code loadFile}
     * @throws TerminalParseException if a primitive fails its grid check
     * @throws IOException if the file cannot be read
     * @return parsed pointset, answer tour, distance matrix, comments, and grid
     */
    public static PointSetPath importFromFile(String path) throws TerminalParseException, IOException {

        boolean fromResource = false;
        TextFile file = Platforms.get().loadFile(path);
        FileInfo fi = new FileInfo();
        for (int i = 0; i < file.size(); i++) {
            String line = file.getLines().get(i);
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
                    int firstPointId = Integer.parseInt(args[1]);
                    int secondPointId = Integer.parseInt(args[2]);
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
                    for (int j = 1; j < args.length; j++) {
                        fi.answerOrder.add(Integer.parseInt(args[j]));
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
                    PointND pt = new PointND.Double(fi.index, Double.parseDouble(args[1]),
                            Double.parseDouble(args[2]));

                    addPoint(pt, fi);
                }
            }

            if (line.contains("NODE_COORD_SECTION")) {
                fi.flag = true;
            }
            fi.lineNumber++;
        }
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
        if (!fromResource && fi.removeDuplicates && fi.duplicatePointIndexes.size() > 0) {
            // removeDuplicates(f, fi.duplicatePointIndexes);
        }
        if (fi.showGrid) {
            fi.grid.showGrid();
        }
        if (fi.grid == null) {
            fi.grid = new Grid.HexGrid();
            fi.grid.showGrid();
        }
        return new PointSetPath(fi.ps, fi.tsp, fi.d, fi.comments, fi.grid);
    }

    /**
     * Overwrite {@code path} with one {@code toFileString()} line per element of {@code shell}.
     *
     * @param path target file path (truncated, not appended)
     * @param shell ordered points to serialize
     */
    public static void rewriteSolutionFile(String path, Shell shell) {
        ArrayList<String> lines = new ArrayList<String>();
        for (int i = 0; i < shell.size(); i++) {
            lines.add(shell.get(i).toFileString());
        }
        try {
            Platforms.get().writeTextFile(new TextFile(path, lines), false);
        } catch (IOException e) {
            e.printStackTrace();
        }
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
                throw new TerminalParseException(EXPECTED_ALL_POINTS_TO_BE_IN + fi.grid.allowableTypes()
                        + BUT_FOUND_POINT_OF_TYPE + pt.getClass());
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

    /**
     * Append a batch of parsed primitive points to the in-progress {@link FileInfo}, validating
     * each against the active grid (cartesian / hex). Auto-detects the grid from the first point.
     *
     * @param points points to append in order
     * @param fi parser state being populated
     * @throws TerminalParseException if a point's coordinate type is incompatible with the grid
     */
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
                    throw new TerminalParseException(EXPECTED_ALL_POINTS_TO_BE_IN + fi.grid.allowableTypes()
                            + BUT_FOUND_POINT_OF_TYPE + pt.getClass());
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
                    lines.add(line + FileManagement.N);
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

    /**
     * Replace the existing {@code ANS } line with one built from {@code ans} point IDs, or
     * append a new one if the file has no answer line yet, then write the result back.
     *
     * @param file target solution file
     * @param ans ordered points whose IDs make up the answer
     */
    public static void appendAns(TextFile file, Shell ans) {

        try {
            ArrayList<String> lines = new ArrayList<String>();
            String line = null;
            boolean foundAns = false;
            String ansLine = ANS;
            for (int i = 0; i < ans.size(); i++) {
                ansLine += ans.get(i).getID() + " ";
            }
            for (int i = 0; i < file.size(); i++) {
                line = file.getLines().get(i);
                if (line.contains(ANS)) {
                    line = ansLine;
                    foundAns = true;
                }
                lines.add(line + FileManagement.N);
            }
            if (!foundAns) {
                lines.add(ansLine + FileManagement.N);
            }
            new TextFile(file.path, lines);
            Platforms.get().writeTextFile(file, false);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Write a generated unit-test source under {@link #SUBGRAPH_UNIT_TEST_FOLDER}. {@code template}
     * is split on {@code \n} and each line written with the platform line separator.
     *
     * @param fileName test file name (within the subgraph unit-test folder)
     * @param template full source body
     */
    public static void writeSubGraphTest(String fileName, String template) {
        File unitTest = new File(SUBGRAPH_UNIT_TEST_FOLDER + fileName);
        try {
            unitTest.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        String[] lines = template.split(FileManagement.N);
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
