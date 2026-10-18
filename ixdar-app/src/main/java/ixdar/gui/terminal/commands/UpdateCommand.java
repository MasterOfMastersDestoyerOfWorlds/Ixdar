package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.OptionList;
import ixdar.geometry.tsp.shell.Shell;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.geometry.tsp.PointSetPath;
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
        MainScene editor = MainScene.current();
        boolean force = (args.length != startIdx) && forceOptionAliases.contains(args[startIdx]);
        if (editor.subPaths.size() == 1) {
            Shell ans = editor.subPaths.get(0);
            double oldLength = editor.orgShell.getLength();
            double newLength = ans.getLength();
            if (oldLength > newLength || force) {
                PointSetPath.appendAns(editor.file, ans);
                editor.orgShell = ans;
                terminal.history.addLine(oldLength + "New Shortest Tour Found", Color.TEAL_DARK);
            }
            terminal.history.addWord("Stored shortest tour length was:", Color.GREEN);
            terminal.history.addLine(oldLength + "", Color.BLUE_GREY);
            terminal.history.addWord("New tour length is:", Color.GREEN);
            terminal.history.addLine(newLength + "", Color.BLUE_GREY);
        }
        return new String[] { cmd };
    }
}
