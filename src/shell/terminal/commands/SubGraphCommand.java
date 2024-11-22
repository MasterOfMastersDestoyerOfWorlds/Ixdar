package shell.terminal.commands;

import java.io.File;
import java.util.ArrayList;

import shell.render.color.Color;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class SubGraphCommand extends TerminalCommand {

    @Override
    public String fullName() {
        return "subgraph";
    }

    @Override
    public String shortName() {
        return "sg";
    }

    @Override
    public String usage() {
        return "usage: sg|subgraph [id range 1] ... [id range n]";
    }

    @Override
    public int argLength() {
        return -1;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        int argLength = args.length - startIdx - 1;
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
        String newFileName = terminal.loadedFile.getName();
        return new String[] { "op " + newFileName };

    }
}
