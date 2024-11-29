package shell.file;

import java.io.File;
import java.util.ArrayList;

import shell.PointND;
import shell.terminal.commands.OptionList;

public class Ix extends PointCollection implements FileStringable {

    public static OptionList opts = new OptionList("i", "ix", "ixdar", "load", "ld");

    public static ArrayList<PointND> parse(String[] args, int startIdx) {
        PointSetPath retTup = parseFull(args, startIdx);
        return retTup.ps;
    }

    public static PointSetPath parseFull(String[] args, int startIdx) {
        File loadFile = FileManagement.getTestFile(args[startIdx]);
        PointSetPath retTup = FileManagement.importFromFile(loadFile);
        return retTup;
    }

    public static Ix parseIx(String[] args, int startIdx) {
        PointSetPath retTup = parseFull(args, startIdx);
        Ix ix = new Ix(args[startIdx], retTup.ps);
        return ix;
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

}
