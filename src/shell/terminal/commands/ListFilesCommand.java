package shell.terminal.commands;

import java.io.File;

import shell.render.color.Color;
import shell.terminal.Terminal;

public class ListFilesCommand extends TerminalCommand {

    @Override
    public String fullName() {
        return "listfiles";
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
        return 0;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        File[] solutions = new File(terminal.directory).listFiles();
        for (int i = 0; i < solutions.length; i++) {
            File f = solutions[i];
            terminal.history.addLine(f.getName(), f.isDirectory() ? Color.BLUE_WHITE : Color.IXDAR);
        }
        terminal.history.addLine("dir: " + terminal.directory, Color.GREEN);
        return new String[] { "cd " };

    }
}
