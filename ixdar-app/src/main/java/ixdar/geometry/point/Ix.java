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
    public static String cmd = "ix";
    public static OptionList opts = new OptionList("i", "ix", "ixdar", "load", "ld");

    public static ArrayList<PointND> parse(String[] args, int startIdx) throws TerminalParseException {
        PointSetPath retTup = parseFull(args, startIdx);
        return retTup.ps;
    }

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

    public static Ix parseIx(String[] args, int startIdx) throws TerminalParseException {
        PointSetPath retTup = parseFull(args, startIdx);
        Ix ix = new Ix(args[startIdx], retTup.ps);
        return ix;
    }

    @Override
    public PointCollection parseCollection(String[] args, int startIdx) throws TerminalParseException {
        PointCollection c = parseIx(args, startIdx);
        return c;
    }

    @Override
    public int minArgLength() {
        return 1;
    }

    @Override
    public ArrayList<PointND> realizePoints() {
        return points;
    }

    @Override
    public String desc() {
        return "all of the points contained in another ix file";
    }

    @Override
    public String usage() {
        return "usage: add ix [name of ix file(filename)]";
    }

    @Override
    public int argLength() {
        return 1;
    }

    @Override
    public OptionList options() {
        return opts;
    }

    String fileName;
    ArrayList<PointND> points;

    public Ix() {
        fileName = "djbouti.ix";
    }

    public Ix(String fileName, ArrayList<PointND> points) {
        this.fileName = fileName;
        this.points = points;
    }

    @Override
    public String toFileString() {
        return "IX " + fileName;
    }

    @Override
    public String fullName() {
        return "ixdar";
    }

    @Override
    public String shortName() {
        return "ix";
    }

}
