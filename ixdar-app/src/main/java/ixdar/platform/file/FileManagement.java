package ixdar.platform.file;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import ixdar.common.exceptions.TerminalParseException;
import ixdar.geometry.point.Arc;

import ixdar.common.utils.Compat;
import ixdar.geometry.point.Circle;
import ixdar.geometry.point.Grid;
import ixdar.geometry.point.Ix;
import ixdar.geometry.point.Line;
import ixdar.geometry.point.PointND;
import ixdar.geometry.point.PointSet;
import ixdar.geometry.point.Triangle;
import ixdar.geometry.shell.DistanceMatrix;
import ixdar.geometry.shell.Shell;
import ixdar.platform.Platforms;
import ixdar.platform.Toggle;

public class FileManagement {
    public static final String IX = ".ix";
    public static final String STR = "/";
    public static final String EXPECTED_ALL_POINTS_TO_BE_IN = "Expected all points to be in: ";
    public static final String BUT_FOUND_POINT_OF_TYPE = " but found point of type: ";
    public static final String N = "\n";
    public static final String ANS = "ANS ";
    public static final String STR_2 = "// ";

    public static final String ASSET_REPO_ENV_VAR = "IXDAR_ASSET_REPO_ROOT";
    public static final String ASSET_REPO_PROP = "ixdar.asset.repo.root";
    public static final String DEFAULT_TEST_MODEL_FILE = "Hand.obj";

    public static final String solutionsFolder = "./src/main/resources/solutions/";

    public static final String testFileCacheLocation = "./src/test/cache/cache";

    public static final String cacheFolder = "./src/test/cache/";

    public static final String subGraphUnitTestFolder = "./test/unit/subgraphs/";

    /**
     * Locate the IxdarAssets checkout root, preferring the JVM property
     * {@code ixdar.asset.repo.root} over the env var {@code IXDAR_ASSET_REPO_ROOT}.
     *
     * @return absolute root path, or {@code null} if neither is set
     */
    public static String getAssetRepoRoot() {
        String propRoot = System.getProperty(ASSET_REPO_PROP);
        if (!Compat.isBlank(propRoot)) {
            return propRoot;
        }
        String root = System.getenv(ASSET_REPO_ENV_VAR);
        if (Compat.isBlank(root)) {
            return null;
        }
        return root;
    }

    /**
     * Resolve a path inside the IxdarAssets repo to an absolute filesystem path.
     *
     * @param relativeAssetPath path relative to the assets repo root
     * @throws IllegalStateException if the assets repo root has not been configured
     * @return absolute path produced by {@code Path.of(root, relativeAssetPath)}
     */
    public static String resolveAssetPath(String relativeAssetPath) {
        String root = getAssetRepoRoot();
        if (Compat.isBlank(root)) {
            throw new IllegalStateException(
                    "Missing asset repo root. Set either env var " + ASSET_REPO_ENV_VAR
                            + " or JVM property " + ASSET_REPO_PROP
                            + " (e.g. C:\\Code\\IxdarAssets).");
        }
        return Path.of(root, relativeAssetPath).toString();
    }

    /**
     * Load a file from the IxdarAssets repo via the current platform.
     *
     * @param relativeAssetPath path relative to the assets repo root
     * @throws IOException if the asset is missing or cannot be read
     * @return the file contents
     */
    public static TextFile loadAssetFile(String relativeAssetPath) throws IOException {
        String absolutePath = resolveAssetPath(relativeAssetPath);
        return Platforms.get().loadExternalFile(absolutePath);
    }

    /**
     * Resolve a solution-folder path for a logical test file name. Splits on {@code _} so that
     * {@code "myShape_v1"} maps under {@code solutions/myShape/}; appends {@code .ix} when missing.
     *
     * @param fileName base name (with or without {@code .ix} extension)
     * @return relative path under {@link #solutionsFolder}
     */
    public static String getTestFile(String fileName) {
        String[] parts = fileName.split("_");
        if (fileName.contains(IX)) {
            return solutionsFolder + parts[0].replace(IX, "") + STR + fileName;
        }
        return solutionsFolder + parts[0] + STR + fileName + IX;
    }

    /**
     * Wrap {@link #getTestFile(String)} as a {@link TextFile} (no I/O performed).
     *
     * @param fileName base name forwarded to {@link #getTestFile(String)}
     * @return empty {@link TextFile} pointing at the resolved path
     */
    public static TextFile getFile(String fileName) {
        String path = getTestFile(fileName);
        return new TextFile(path);
    }

    /**
     * Logical scratch file at {@code "temp.ix"}; the {@code fileName} argument is currently
     * ignored.
     *
     * @param fileName intended name (unused)
     * @return temp {@link TextFile}
     */
    public static TextFile getTempFile(String fileName) {
        return new TextFile("temp", IX);
    }

    /**
     * Read the most recently used file path from the on-disk test cache.
     *
     * @return cached path string, or {@code ""} if the cache is missing or unreadable
     */
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

    /**
     * Persist {@code cachedLocation} as the last-used file path. Blank strings are ignored;
     * I/O errors are logged and swallowed.
     *
     * @param cachedLocation path to remember; ignored if blank
     */
    public static void updateTestFileCache(String cachedLocation) {
        if (!Compat.isBlank(cachedLocation)) {
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

    // New APIs returning logical text files instead of java.io.File for
    // cross-platform
    /**
     * Wrap a logical path in an empty {@link TextFile} (cross-platform alternative to
     * {@code java.io.File} since web has no filesystem).
     *
     * @param logicalPath path string
     * @return empty {@link TextFile} bound to that path
     */
    public static TextFile toTextFile(String logicalPath) {
        return new TextFile(logicalPath);
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
                    lines.add(line + N);
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
     * Copy {@code src} to {@code dest} line-by-line (each terminated by {@code \n}); errors are
     * caught and printed.
     *
     * @param src source file
     * @param dest destination file (overwritten)
     */
    public static void copyFileContents(File src, File dest) {

        List<String> lines = new ArrayList<String>();
        String line = null;
        try {
            FileReader fr = new FileReader(src);
            BufferedReader br = new BufferedReader(fr);
            while ((line = br.readLine()) != null) {
                lines.add(line + N);
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
                lines.add(line + N);
            }
            if (!foundAns) {
                lines.add(ansLine + N);
            }
            new TextFile(file.path, lines);
            Platforms.get().writeTextFile(file, false);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Append a {@code //}-prefixed comment line to {@code path} and flush it to disk in append
     * mode.
     *
     * @param path target file (mutated in memory and on disk)
     * @param comment text after {@code //}
     */
    public static void appendComment(TextFile path, String comment) {
        try {
            path.getLines().add(STR_2 + comment);
            Platforms.get().writeTextFile(path, true);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Append a line to {@code path} and flush it to disk in append mode. Note: the line is
     * also currently prefixed with {@code //} due to shared implementation with
     * {@link #appendComment}.
     *
     * @param path target file
     * @param appLine line content
     */
    public static void appendLine(TextFile path, String appLine) {
        try {
            path.getLines().add(STR_2 + appLine);
            Platforms.get().writeTextFile(path, true);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Round-trip {@code f} through line-by-line read/write (effectively rewrites it with
     * {@code \n} line terminators). Currently a placeholder for future cut-answer logic.
     *
     * @param f file to rewrite in place
     */
    public static void appendCutAns(File f) {
        List<String> lines = new ArrayList<String>();
        String line = null;
        try {
            FileReader fr = new FileReader(f);
            BufferedReader br = new BufferedReader(fr);
            while ((line = br.readLine()) != null) {
                lines.add(line + N);
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

    // removed duplicate rewriteSolutionFile method

    /**
     * Write a generated unit-test source under {@link #subGraphUnitTestFolder}. {@code template}
     * is split on {@code \n} and each line written with the platform line separator.
     *
     * @param fileName test file name (within the subgraph unit-test folder)
     * @param template full source body
     */
    public static void writeSubGraphTest(String fileName, String template) {
        File unitTest = new File(subGraphUnitTestFolder + fileName);
        try {
            unitTest.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        String[] lines = template.split(N);
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