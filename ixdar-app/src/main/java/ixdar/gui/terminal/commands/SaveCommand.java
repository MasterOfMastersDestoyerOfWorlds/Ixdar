package ixdar.gui.terminal.commands;

import java.io.File;
import java.io.IOException;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.file.FileManagement;

@CommandAnnotation(id = "sv", acts = Subsystem.FILES)
public class SaveCommand extends TerminalCommand {
    public static final String IX = ".ix";

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
        if (newFileName.contains(IX)) {
            newFileName = newFileName.split(IX)[0];
        }
        String firstPart = newFileName.split("_")[0];
        File dirFile = new File(FileManagement.SOLUTIONS_FOLDER, firstPart);
        if (!dirFile.exists()) {
            dirFile.mkdir();
        }
        File newFile = new File(dirFile, newFileName + IX);
        try {
            newFile.createNewFile();
            //FileManagement.copyFileContents(Main.tempFile, newFile);
        } catch (IOException e) {
            terminal.error("could not save file: " + newFileName);
        }

        return new String[] { "ld " + newFileName };
    }
}
