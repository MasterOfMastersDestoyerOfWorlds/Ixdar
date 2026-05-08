package ixdar.gui.terminal.commands;

import java.io.File;
import java.io.IOException;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.file.FileManagement;
import ixdar.scenes.main.MainScene;

@CommandAnnotation(id = "nw")
public class NewIxCommand extends TerminalCommand {

    public static String cmd = "nw";

    /**
     * TODO: document {@code fullName}.
     *
     * @return TODO: describe
     */
    @Override
    public String fullName() {
        return "newix";
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
        return "creates a new blank ixdar file with no points and a new directory";
    }

    /**
     * TODO: document {@code usage}.
     *
     * @return TODO: describe
     */
    @Override
    public String usage() {
        return "usage: nw|newix [base filename of new file(filename)]";
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
        MainScene.canvas.activate(false);
        try {
            MainScene.main(new String[] { fileName });
        } catch (IOException e) {
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
