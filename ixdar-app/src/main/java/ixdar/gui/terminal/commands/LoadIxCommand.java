package ixdar.gui.terminal.commands;

import java.io.File;
import java.io.IOException;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.file.FileManagement;
import ixdar.scenes.main.MainScene;

@CommandAnnotation(id = "ld")
public class LoadIxCommand extends TerminalCommand {
    public static final String IX = ".ix";

    public static String cmd = "ld";

    /**
     * TODO: document {@code fullName}.
     *
     * @return TODO: describe
     */
    @Override
    public String fullName() {
        return "loadix";
    }

    /**
     * TODO: document {@code shortName}.
     *
     * @return TODO: describe
     */
    @Override
    public String shortName() {
        return cmd;
    }

    /**
     * TODO: document {@code desc}.
     *
     * @return TODO: describe
     */
    @Override
    public String desc() {
        return "load an ixdar file and begin calculations";
    }

    /**
     * TODO: document {@code usage}.
     *
     * @return TODO: describe
     */
    @Override
    public String usage() {
        return "usage: ld|loadix [file to load(filename)]";
    }

    /**
     * TODO: document {@code argLength}.
     *
     * @return TODO: describe
     */
    @Override
    public int argLength() {
        return 1;
    }

    /**
     * TODO: document {@code run}.
     *
     * @param fileName TODO: describe
     * @throws TerminalParseException TODO: describe
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
     * TODO: document {@code run}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @param terminal TODO: describe
     * @return TODO: describe
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
