package ixdar.gui.terminal.commands;

import java.io.File;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.gui.terminal.Terminal;

@CommandAnnotation(id = "cd")
public class ChangeDirectoryCommand extends TerminalCommand {

    public static String cmd = "cd";

    @Override
    public String fullName() {
        return "changedirectory";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "change the current working directory";
    }

    @Override
    public String usage() {
        return "usage: cd|changedirectory [directory name]";
    }

    @Override
    public int argLength() {
        return 1;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        if (args[startIdx].equals("../")) {
            String parent = new File(terminal.directory).getParent();
            if (parent != null) {
                terminal.directory = parent;
            }
            return new String[] { "ls" };
        }
        String dirLoc = terminal.directory + "/" + args[startIdx];
        File newDir = new File(dirLoc);
        if (newDir.exists() && newDir.isDirectory()) {
            terminal.directory = dirLoc;
            return new String[] { "ls" };
        }
        terminal.error("directory not found: " + dirLoc);
        return null;

    }
}
