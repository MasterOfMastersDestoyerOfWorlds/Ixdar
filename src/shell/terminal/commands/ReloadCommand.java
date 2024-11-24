package shell.terminal.commands;

import java.io.File;

import shell.file.FileManagement;
import shell.terminal.Terminal;
import shell.ui.Canvas3D;
import shell.ui.main.Main;

public class ReloadCommand extends TerminalCommand {

    public static String cmd = "rld";

    @Override
    public String fullName() {
        return "reload";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String usage() {
        return "usage: rld|reload";
    }

    @Override
    public int argLength() {
        return 0;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        String fileName = terminal.loadedFile.getName();
        File newDir = terminal.loadedFile;
        if (newDir.exists() && newDir.isFile()) {
            FileManagement.updateTestFileCache(fileName);
            Canvas3D.activate(false);
            Main.main(new String[] { fileName });
            Main.activate(true);
            return new String[] { "ls " };
        }
        terminal.error("file not found: " + fileName);

        return null;
    }
}
