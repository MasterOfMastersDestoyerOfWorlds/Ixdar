package shell.terminal.commands;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import shell.file.FileManagement;
import shell.shell.Range;
import shell.shell.Shell;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class SubGraphCommand extends TerminalCommand {

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
        ArrayList<Range> ranges = new ArrayList<>();
        for (int i = startIdx; i < args.length; i++) {
            String arg = args[i];
            try {
                Range r = Range.parse(arg);
                if (!Main.orgShell.hasPoint(r.startIdx)) {
                    terminal.error("argument " + arg
                            + " was out of bounds");
                } else if (!Main.orgShell.hasPoint(r.endIdx)) {
                    terminal.error("argument " + arg
                            + " was out of bounds");
                }
                ranges.add(r);
            } catch (Exception e) {
                terminal.error("argument " + arg
                        + " was could not be parsed: " + e.getMessage());
                return null;
            }

        }
        String subGraphFileName = terminal.loadedFile.getName();
        int extension = terminal.directory.lastIndexOf("\\") + 1;
        if (extension == -1) {
            extension = 0;
        }
        subGraphFileName = terminal.directory.substring(extension, terminal.directory.length()) + "_";

        for (int i = 0; i < ranges.size() - 1; i++) {
            Range r = ranges.get(i);
            subGraphFileName += r.toString() + "p";
        }

        Range lastRange = ranges.get(ranges.size() - 1);
        subGraphFileName += lastRange.toString();

        subGraphFileName += ".ix";
        File newFile = new File(terminal.directory + "\\" + subGraphFileName);
        try {
            newFile.createNewFile();
        } catch (IOException e) {
            terminal.error("could not create subgraph: " + subGraphFileName);
        }
        Shell subGraph = new Shell();
        for (Range r : ranges) {
            subGraph.addAllInRange(r, Main.orgShell);
        }

        FileManagement.rewriteSolutionFile(newFile, subGraph);
        return new String[] { "ld " + subGraphFileName };
    }
}
