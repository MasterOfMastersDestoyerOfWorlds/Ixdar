package shell.terminal.commands;

import java.io.File;

import shell.file.FileManagement;
import shell.terminal.Terminal;
import shell.ui.Canvas3D;
import shell.ui.main.Main;

public class NewIxCommand extends TerminalCommand {

    public static String cmd = "nw";

    @Override
    public String fullName() {
        return "newix";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "create a blank ixdar file with a name";
    }

    @Override
    public String usage() {
        return "usage: nw|newix [base filename of new file(filename)]";
    }

    @Override
    public int argLength() {
        return 1;
    }

    public static void run(String fileName) {
        FileManagement.updateTestFileCache(fileName);
        Canvas3D.activate(false);
        Main.main(new String[] { fileName });
        Main.activate(true);

    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        String fileName = args[startIdx];
        String dirLoc = terminal.directory + "/" + fileName;
        File newDir = new File(dirLoc);
        if (newDir.exists() && newDir.isFile()) {
            run(fileName);
            return new String[] { "ls " };
        }
        terminal.error("file not found: " + dirLoc);

        return null;
    }
}
