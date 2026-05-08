package ixdar.gui.terminal.commands;

import java.io.File;
import java.io.IOException;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.file.FileManagement;
import ixdar.scenes.main.MainScene;

/**
 * Terminal command {@code nw}/{@code newix} that creates a new blank {@code .ix} file
 * (no points, fresh directory) and activates it on {@link MainScene}.
 */
@CommandAnnotation(id = "nw")
public class NewIxCommand extends TerminalCommand {

    public static String cmd = "nw";

    /**
     * Full command word: {@code "newix"}.
     *
     * @return fully-qualified command name used at the prompt
     */
    @Override
    public String fullName() {
        return "newix";
    }

    /**
     * Short alias for the command: {@code "nw"}.
     *
     * @return short command name used at the prompt
     */
    @Override
    public String shortName() {
        return cmd;
    }

    /**
     * One-line description shown in help output.
     *
     * @return human-readable summary of the command
     */
    @Override
    public String desc() {
        return "creates a new blank ixdar file with no points and a new directory";
    }

    /**
     * Usage hint displayed when the command is mis-invoked or {@code -h} is passed.
     *
     * @return usage string
     */
    @Override
    public String usage() {
        return "usage: nw|newix [base filename of new file(filename)]";
    }

    /**
     * Exact number of trailing arguments expected: a single base filename.
     *
     * @return {@code 1}
     */
    @Override
    public int argLength() {
        return 1;
    }

    /**
     * Refresh the test-file cache for {@code fileName}, deactivate the existing canvas,
     * run {@link MainScene#main} on the new file, then activate the scene.
     *
     * @param fileName base filename for the new {@code .ix} file
     * @throws TerminalParseException if scene initialisation fails with an {@link java.io.IOException}
     */
    public static void run(String fileName) throws TerminalParseException {
        FileManagement.updateTestFileCache(fileName);
        MainScene.canvas.activate(false);
        try {
            MainScene.main(new String[] { fileName });
        } catch (IOException e) {
            throw new TerminalParseException(e.getMessage());
        }
        MainScene.activate(true);

    }

    /**
     * Resolve the target file under {@code terminal.directory}, invoke {@link #run(String)}
     * on it when it exists, and return {@code "ls "} as the next-suggested command. Reports
     * an error to the terminal if the path is missing or loading fails.
     *
     * @param args full tokenised command line
     * @param startIdx index of the filename argument in {@code args}
     * @param terminal dispatching terminal (used for directory context and error reporting)
     * @return follow-up suggestion array, or {@code null} on failure
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        String fileName = args[startIdx];
        String dirLoc = terminal.directory + "/" + fileName;
        File newDir = new File(dirLoc);
        if (newDir.exists() && newDir.isFile()) {
            try {
                run(fileName);
                return new String[] { "ls " };
            } catch (TerminalParseException e) {
                terminal.error(e.message);
                return null;
            }
        }
        terminal.error("file not found: " + dirLoc);

        return null;
    }
}
