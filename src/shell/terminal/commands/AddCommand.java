package shell.terminal.commands;

import java.util.ArrayList;
import shell.PointND;
import shell.file.Circle;
import shell.file.FileManagement;
import shell.file.Line;
import shell.file.Triangle;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class AddCommand extends TerminalCommand {

    public static String cmd = "add";
    private OptionList pointOptions = new OptionList("p", "point");

    @Override
    public String fullName() {
        return "add";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "add a point or set of points to the current file";
    }

    @Override
    public String usage() {
        return "usage: add [object type(triangle|circle|point|line)] [args]";
    }

    @Override
    public int argLength() {
        return -1;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        if (Triangle.opts.contains(args[startIdx])) {
            ArrayList<PointND> triangle = Triangle.parse(args, startIdx + 1);
        } else if (Circle.opts.contains(args[startIdx])) {
            ArrayList<PointND> circle = Circle.parse(args, startIdx + 1);
        } else if (pointOptions.contains(args[startIdx])) {
            double[] coords = new double[args.length - startIdx];
            for (int i = 0; i < coords.length; i++) {
                coords[i] = java.lang.Double.parseDouble(args[startIdx + 1 + i]);
            }
            PointND pt = new PointND.Double(coords);
            FileManagement.appendLine(Main.tempFile, pt.toFileString());
        } else if (Line.opts.contains(args[startIdx])) {
            ArrayList<PointND> line = Line.parse(args, startIdx + 1);
        }

        return null;
    }
}
