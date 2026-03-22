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

    public static String cmd = "ld";

    @Override
    public String fullName() {
        return "loadix";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "load an ixdar file and begin calculations";
    }

    @Override
    public String usage() {
        return "usage: ld|loadix [file to load(filename)]";
    }

    @Override
    public int argLength() {
        return 1;
    }

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

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        String fileName = args[startIdx];
        if (fileName.contains(".ix")) {
            fileName = fileName.split(".ix")[0];
        }
        String firstPart = fileName.split("_")[0];
        String dir = FileManagement.solutionsFolder + firstPart + "\\";
        File solutionsFolder = new File(dir);
        if (!solutionsFolder.exists()) {
            dir = terminal.directory + "/";
        }
        String dirLoc = dir + fileName + ".ix";
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
