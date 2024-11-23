package shell.terminal.commands;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import shell.file.FileManagement;
import shell.render.color.Color;
import shell.shell.Range;
import shell.shell.Shell;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class LoadCommand extends TerminalCommand {

    @Override
    public String fullName() {
        return "load";
    }

    @Override
    public String shortName() {
        return "ld";
    }

    @Override
    public String usage() {
        return "usage: ld|load [file to load(filename)]";
    }

    @Override
    public int argLength() {
        return 1;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        ArrayList<Range> ranges = new ArrayList<>();
        for (int i = startIdx; i < args.length; i++) {
            String arg = args[i];
            try {
                Range r = Range.parse(arg);
                if (!Main.orgShell.hasPoint(r.startIdx)) {
                    terminal.history
                            .addLine("exception: argument " + arg
                                    + " was out of bounds", Color.RED);
                } else if (!Main.orgShell.hasPoint(r.endIdx)) {
                    terminal.history
                            .addLine("exception: argument " + arg
                                    + " was out of bounds", Color.RED);
                }
                ranges.add(r);
            } catch (Exception e) {
                terminal.history
                        .addLine("exception: argument " + arg
                                + " was could not be parsed: " + e.getMessage(), Color.RED);
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
            terminal.history.addLine("exception: could not create subgraph: " + subGraphFileName, Color.RED);
        }
        Shell subGraph = new Shell();
        for (Range r : ranges) {
            subGraph.addAllInRange(r, Main.orgShell);
        }

        FileManagement.rewriteSolutionFile(newFile, subGraph);
        return new String[] { "op " + subGraphFileName };
    }
}
