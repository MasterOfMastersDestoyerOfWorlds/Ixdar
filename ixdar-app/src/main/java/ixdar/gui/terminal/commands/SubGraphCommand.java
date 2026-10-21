package ixdar.gui.terminal.commands;

import java.io.File;
import java.util.ArrayList;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.geometry.tsp.shell.Range;
import ixdar.geometry.tsp.shell.Shell;
import ixdar.gui.terminal.Terminal;
import ixdar.geometry.tsp.PointSetPath;

@CommandAnnotation(id = "sg", acts = Subsystem.KNOT)
public class SubGraphCommand extends TerminalCommand {
    public static final String ARGUMENT = "argument ";
    public static final String WAS_OUT_OF_BOUNDS = " was out of bounds";

    public static String cmd = "sg";

    @Override
    public String fullName() {
        return "subgraph";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "create a new ixdar file from a subgraph of the current graph";
    }

    @Override
    public String usage() {
        return "usage: sg|subgraph [id range 1(range)] ... [id range n(range)]";
    }

    @Override
    public int argLength() {
        return -1;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        PointSetPath pointSetPath = terminal.dataset(Subsystem.KNOT);
        ArrayList<Range> ranges = new ArrayList<>();
        for (int i = startIdx; i < args.length; i++) {
            String arg = args[i];
            try {
                Range r = Range.parse(arg);
                if (!pointSetPath.tsp.hasPoint(r.startIdx) || !pointSetPath.tsp.hasPoint(r.endIdx)) {
                    terminal.error(ARGUMENT + arg + WAS_OUT_OF_BOUNDS);
                    return null;
                }
                ranges.add(r);
            } catch (Exception e) {
                terminal.error(ARGUMENT + arg + " was could not be parsed: " + e.getMessage());
                return null;
            }

        }
        if (ranges.isEmpty()) {
            terminal.error(usage());
            return null;
        }
        String subGraphFileName = new File(terminal.directory).getName() + "_";
        for (int i = 0; i < ranges.size() - 1; i++) {
            Range r = ranges.get(i);
            subGraphFileName += r.toString() + "p";
        }

        Range lastRange = ranges.get(ranges.size() - 1);
        subGraphFileName += lastRange.toString();

        subGraphFileName += ".ix";
        String newPath = new File(terminal.directory, subGraphFileName).getPath();
        Shell subGraph = new Shell();
        for (Range r : ranges) {
            subGraph.addAllInRange(r, pointSetPath.tsp);
        }

        PointSetPath.rewriteSolutionFile(newPath, subGraph);
        return new String[] { "ld " + subGraphFileName };
    }
}
