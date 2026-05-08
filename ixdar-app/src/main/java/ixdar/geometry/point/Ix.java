package ixdar.geometry.point;

import java.io.IOException;
import java.util.ArrayList;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.geometry.GeometryAnnotation;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.platform.file.FileManagement;
import ixdar.platform.file.PointSetPath;

/**
 * Reference to another {@code .ix} file's point set, exposed on the terminal
 * as {@code add ix} (also {@code i} / {@code ixdar} / {@code load} /
 * {@code ld}). On parse the referenced file is loaded and its points become
 * this collection's points.
 */
@GeometryAnnotation(id = "ix")
public class Ix implements Geometry, PointCollection {
    public static final String IXDAR = "ixdar";
    public static String cmd = "ix";
    public static OptionList opts = new OptionList("i", cmd, IXDAR, "load", "ld");

    String fileName;
    ArrayList<PointND> points;

    /**
     * Default reference: loads the {@code djbouti.ix} sample file when realized.
     */
    public Ix() {
        fileName = "djbouti.ix";
    }

    /**
     * Build an {@code Ix} reference with a known filename and pre-loaded points.
     *
     * @param fileName name of the {@code .ix} file this reference points at
     * @param points points already loaded from {@code fileName}
     */
    public Ix(String fileName, ArrayList<PointND> points) {
        this.fileName = fileName;
        this.points = points;
    }

    /**
     * Convenience that parses the filename arg, loads the file, and returns
     * just its points.
     *
     * @param args full terminal argument array
     * @param startIdx index of the filename argument
     * @return points loaded from the referenced file
     * @throws TerminalParseException if the file cannot be located or read
     */
    public static ArrayList<PointND> parse(String[] args, int startIdx) throws TerminalParseException {
        PointSetPath retTup = parseFull(args, startIdx);
        return retTup.ps;
    }

    /**
     * Resolve the filename argument to a test-fixture path and import the
     * whole {@link PointSetPath} (points plus path data) from it.
     *
     * @param args full terminal argument array
     * @param startIdx index of the filename argument
     * @return loaded point-set-and-path tuple
     * @throws TerminalParseException if the underlying file IO fails
     */
    public static PointSetPath parseFull(String[] args, int startIdx) throws TerminalParseException {
        String loadFile = FileManagement.getTestFile(args[startIdx]);
        try{
        PointSetPath retTup = FileManagement.importFromFile(loadFile);

        return retTup;
        }
        catch(IOException e){
            throw new TerminalParseException("could not load: "+ loadFile);
        }

    }

    /**
     * Parse a full {@code Ix} value, retaining both the filename and the
     * loaded points.
     *
     * @param args full terminal argument array
     * @param startIdx index of the filename argument
     * @return populated {@code Ix} reference
     * @throws TerminalParseException if the file cannot be located or read
     */
    public static Ix parseIx(String[] args, int startIdx) throws TerminalParseException {
        PointSetPath retTup = parseFull(args, startIdx);
        Ix ix = new Ix(args[startIdx], retTup.ps);
        return ix;
    }

    /**
     * {@link PointCollection} entry point; delegates to {@link #parseIx}.
     *
     * @param args full terminal argument array
     * @param startIdx index of the filename argument
     * @return parsed reference as a {@link PointCollection}
     * @throws TerminalParseException if the file cannot be located or read
     */
    @Override
    public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
        PointCollection c = parseIx(args, startIdx);
        return c;
    }

    /**
     * Minimum CLI args: the filename is required.
     *
     * @return {@code 1}
     */
    @Override
    public int minArgLength() {
        return 1;
    }

    /**
     * Return the points loaded from the referenced file.
     *
     * @return the previously imported points (not a copy)
     */
    @Override
    public ArrayList<PointND> realizePoints() {
        return points;
    }

    /**
     * Short human-readable description shown in terminal help.
     *
     * @return description string
     */
    @Override
    public String desc() {
        return "all of the points contained in another ix file";
    }

    /**
     * Usage hint shown when the command is invoked with bad arguments.
     *
     * @return single-line usage string
     */
    @Override
    public String usage() {
        return "usage: add ix [name of ix file(filename)]";
    }

    /**
     * Required positional arg count for parsing: a single filename.
     *
     * @return {@code 1}
     */
    @Override
    public int argLength() {
        return 1;
    }

    /**
     * Aliases the terminal accepts for this geometry ({@code i}, {@code ix},
     * {@code ixdar}, {@code load}, {@code ld}).
     *
     * @return shared option list
     */
    @Override
    public OptionList options() {
        return opts;
    }

    /**
     * Serialize this reference to its {@code .ix} file representation.
     *
     * @return {@code "IX <fileName>"}
     */
    @Override
    public String toFileString() {
        return "IX " + fileName;
    }

    /**
     * Long terminal name for this geometry.
     *
     * @return {@value #IXDAR}
     */
    @Override
    public String fullName() {
        return IXDAR;
    }

    /**
     * CLI shorthand for this geometry, matching the {@code @GeometryAnnotation} id.
     *
     * @return {@code "ix"}
     */
    @Override
    public String shortName() {
        return cmd;
    }

}
