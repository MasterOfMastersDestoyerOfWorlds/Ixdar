package shell.terminal.commands;

import java.io.File;

import shell.terminal.Terminal;

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
