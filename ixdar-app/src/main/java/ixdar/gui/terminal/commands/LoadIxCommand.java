package ixdar.gui.terminal.commands;

import java.io.File;
import java.io.IOException;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.file.FileManagement;
import ixdar.scenes.main.MainScene;

/**
 * Terminal command {@code ld}/{@code loadix} that loads an existing {@code .ix} file
 * into {@link MainScene} and starts its calculations.
 */
@CommandAnnotation(id = "ld")
public class LoadIxCommand extends TerminalCommand {
    public static final String IX = ".ix";

    public static String cmd = "ld";

    /**
     * Full command word: {@code "loadix"}.
     *
     * @return fully-qualified command name used at the prompt
     */
    @Override
    public String fullName() {
        return "loadix";
    }

    /**
     * Short alias for the command: {@code "ld"}.
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
        return "load an ixdar file and begin calculations";
    }

    /**
     * Usage hint displayed when the command is mis-invoked or {@code -h} is passed.
     *
     * @return usage string
     */
    @Override
    public String usage() {
        return "usage: ld|loadix [file to load(filename)]";
    }

    /**
     * Exact number of trailing arguments expected: a single filename.
     *
     * @return {@code 1}
     */
    @Override
    public int argLength() {
        return 1;
    }

    /**
     * Refresh the test-file cache for {@code fileName}, run {@link MainScene#main} on it,
     * then activate the scene.
     *
     * @param fileName base name of the {@code .ix} file (without extension)
     * @throws TerminalParseException if reading/loading the file fails
     */
    public static void run(String fileName) throws TerminalParseException {
        FileManagement.updateTestFileCache(fileName);
        try {
            MainScene.main(new String[] { fileName });
        } catch (IOException e) {
            e.printStackTrace();
            throw new TerminalParseException(e.getMessage());
        }
        MainScene.activate(true);

    }

    /**
     * Resolve the {@code .ix} file (first under the standard solutions folder, then under
     * {@code terminal.directory}), invoke {@link #run(String)} on it when it exists, and
     * return {@code "ls "} as the next-suggested command. Reports an error to the terminal
     * if the file cannot be found or fails to load.
     *
     * @param args full tokenised command line
     * @param startIdx index of the filename argument in {@code args}
     * @param terminal dispatching terminal (used for directory context and error reporting)
     * @return follow-up suggestion array, or {@code null} on failure
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        String fileName = args[startIdx];
        if (fileName.contains(IX)) {
            fileName = fileName.split(IX)[0];
        }
        String firstPart = fileName.split("_")[0];
        String dir = FileManagement.solutionsFolder + firstPart + "\\";
        File solutionsFolder = new File(dir);
        if (!solutionsFolder.exists()) {
            dir = terminal.directory + "/";
        }
        String dirLoc = dir + fileName + IX;
        File newDir = new File(dirLoc);
        if (newDir.exists() && newDir.isFile()) {
            try {
                run(fileName);
                return new String[] { "ls " };
            } catch (TerminalParseException e) {
                terminal.error(e.message);
            }
        }
        terminal.error("file not found: " + dirLoc);

        return null;
    }
}
