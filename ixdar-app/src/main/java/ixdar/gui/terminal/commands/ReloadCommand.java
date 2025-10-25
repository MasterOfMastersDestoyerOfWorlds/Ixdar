package ixdar.gui.terminal.commands;

import java.io.IOException;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.file.FileManagement;
import ixdar.scenes.main.MainScene;

@CommandAnnotation(id = "rld")
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
    public String desc() {
        return "reload current ixdar file and begin calculations";
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
        try {
            MainScene.main(new String[] { fileName });
            FileManagement.updateTestFileCache(fileName);
            MainScene.activate(true);
            return new String[] { "ls " };
        } catch (TerminalParseException e) {
            terminal.error(e.message);
        } catch (IOException e){
            terminal.error("file not found: " + fileName);
        }
        return null;
    }
}
