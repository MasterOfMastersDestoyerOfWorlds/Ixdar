package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.OptionList;
import ixdar.geometry.shell.Shell;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.file.FileManagement;
import ixdar.scenes.main.MainScene;

@CommandAnnotation(id = "updt")
public class UpdateCommand extends TerminalCommand {

    public static String cmd = "updt";

    public static OptionList forceOptionAliases = new OptionList("f", "force");

    @Override
    public String fullName() {
        return "update";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "update the answer stored in the current ix file";
    }

    @Override
    public String usage() {
        return "usage: updt|update [f|force]";
    }

    @Override
    public int argLength() {
        return -1;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        boolean force = (args.length != startIdx) && forceOptionAliases.contains(args[startIdx]);
        if (MainScene.subPaths.size() == 1) {
            Shell ans = MainScene.subPaths.get(0);
            double oldLength = MainScene.orgShell.getLength();
            double newLength = ans.getLength();
            if (oldLength > newLength || force) {
                FileManagement.appendAns(MainScene.file, ans);
                MainScene.orgShell = ans;
                terminal.history.addLine(oldLength + "New Shortest Tour Found", Color.COMMAND);
            }
            terminal.history.addWord("Stored shortest tour length was:", Color.GREEN);
            terminal.history.addLine(oldLength + "", Color.BLUE_WHITE);
            terminal.history.addWord("New tour length is:", Color.GREEN);
            terminal.history.addLine(newLength + "", Color.BLUE_WHITE);
        }
        return new String[] { cmd };
    }
}
