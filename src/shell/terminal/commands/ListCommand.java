package shell.terminal.commands;

import java.io.File;

import shell.render.color.Color;
import shell.terminal.Terminal;

public class ListCommand extends TerminalCommand {

    @Override
    public String fullName() {
        return "list";
    }

    @Override
    public String shortName() {
        return "ls";
    }

    @Override
    public String usage() {
        return "usage: ls|listfiles";
    }

    @Override
    public int argLength() {
        return -1;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        if (args.length == startIdx) {
            File[] solutions = new File(terminal.directory).listFiles();
            for (int i = 0; i < solutions.length; i++) {
                File f = solutions[i];
                terminal.history.addLine(f.getName(), f.isDirectory() ? Color.BLUE_WHITE : Color.IXDAR);
            }
            terminal.history.addLine("dir: " + terminal.directory, Color.GREEN);
            return new String[] { "cd " };
        } else {
            if (args[startIdx].equals("values")) {

            }
            if (args[startIdx].equals("questions")) {

            }
            return null;
        }

    }
}
