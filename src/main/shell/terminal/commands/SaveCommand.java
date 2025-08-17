package shell.terminal.commands;

import java.io.File;
import java.io.IOException;

import shell.file.FileManagement;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class SaveCommand extends TerminalCommand {

    public static String cmd = "sv";

    @Override
    public String fullName() {
        return "save";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "save the temporary changes to a new file and folder";
    }

    @Override
    public String usage() {
        return "usage: sv|save [name of new file(filename)]";
    }

    @Override
    public int argLength() {
        return 1;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        String newFileName = args[startIdx];
        if (newFileName.contains(".ix")) {
            newFileName = newFileName.split(".ix")[0];
        }
        String firstPart = newFileName.split("_")[0];
        String dir = FileManagement.solutionsFolder + firstPart + "\\";
        String fullPath = dir + newFileName + ".ix";
        File dirFile = new File(dir);
        if (!dirFile.exists()) {
            dirFile.mkdir();
        }
        File newFile = new File(fullPath);
        try {
            newFile.createNewFile();
            FileManagement.copyFileContents(Main.tempFile, newFile);
        } catch (IOException e) {
            terminal.error("could not save file: " + newFileName);
        }

        return new String[] { "ld " + newFileName };
    }
}
