package ixdar.geometry.point;

import java.io.IOException;
import java.util.ArrayList;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.geometry.GeometryAnnotation;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.platform.file.FileManagement;
import ixdar.platform.file.PointSetPath;

@GeometryAnnotation(id = "ix")
public class Ix implements Geometry, PointCollection {
    public static final String IXDAR = "ixdar";
    public static String cmd = "ix";
    public static OptionList opts = new OptionList("i", cmd, IXDAR, "load", "ld");

    String fileName;
    ArrayList<PointND> points;

    /**
     * TODO: document {@code Ix}.
     */
    public Ix() {
        fileName = "djbouti.ix";
    }

    /**
     * TODO: document {@code Ix}.
     *
     * @param fileName TODO: describe
     * @param points TODO: describe
     */
    public Ix(String fileName, ArrayList<PointND> points) {
        this.fileName = fileName;
        this.points = points;
    }

    /**
     * TODO: document {@code parse}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
     */
    public static ArrayList<PointND> parse(String[] args, int startIdx) throws TerminalParseException {
        PointSetPath retTup = parseFull(args, startIdx);
        return retTup.ps;
    }

    /**
     * TODO: document {@code parseFull}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code parseIx}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
     */
    public static Ix parseIx(String[] args, int startIdx) throws TerminalParseException {
        PointSetPath retTup = parseFull(args, startIdx);
        Ix ix = new Ix(args[startIdx], retTup.ps);
        return ix;
    }

    /**
     * TODO: document {@code parseCollection}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
     */
    @Override
    public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
        PointCollection c = parseIx(args, startIdx);
        return c;
    }

    /**
     * TODO: document {@code minArgLength}.
     *
     * @return TODO: describe
     */
    @Override
    public int minArgLength() {
        return 1;
    }

    /**
     * TODO: document {@code realizePoints}.
     *
     * @return TODO: describe
     */
    @Override
    public ArrayList<PointND> realizePoints() {
        return points;
    }

    /**
     * TODO: document {@code desc}.
     *
     * @return TODO: describe
     */
    @Override
    public String desc() {
        return "all of the points contained in another ix file";
    }

    /**
     * TODO: document {@code usage}.
     *
     * @return TODO: describe
     */
    @Override
    public String usage() {
        return "usage: add ix [name of ix file(filename)]";
    }

    /**
     * TODO: document {@code argLength}.
     *
     * @return TODO: describe
     */
    @Override
    public int argLength() {
        return 1;
    }

    /**
     * TODO: document {@code options}.
     *
     * @return TODO: describe
     */
    @Override
    public OptionList options() {
        return opts;
    }

    /**
     * TODO: document {@code toFileString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toFileString() {
        return "IX " + fileName;
    }

    /**
     * TODO: document {@code fullName}.
     *
     * @return TODO: describe
     */
    @Override
    public String fullName() {
        return IXDAR;
    }

    /**
     * TODO: document {@code shortName}.
     *
     * @return TODO: describe
     */
    @Override
    public String shortName() {
        return cmd;
    }

}
