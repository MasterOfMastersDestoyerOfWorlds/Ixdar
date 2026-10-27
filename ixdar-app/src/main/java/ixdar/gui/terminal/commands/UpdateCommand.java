package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.OptionList;
import ixdar.annotations.command.Subsystem;
import ixdar.geometry.tsp.shell.Shell;
import ixdar.graphics.render.color.Palette;
import ixdar.gui.terminal.Terminal;
import ixdar.geometry.tsp.PointSetPath;

@CommandAnnotation(id = "updt", acts = Subsystem.KNOT)
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
        PointSetPath pointSetPath = terminal.dataset(Subsystem.KNOT);
        boolean force = (args.length != startIdx) && forceOptionAliases.contains(args[startIdx]);
        Palette palette = terminal.scene.palette;
        if (pointSetPath.subPaths.size() == 1) {
            Shell ans = pointSetPath.subPaths.get(0);
            double oldLength = pointSetPath.tsp.getLength();
            double newLength = ans.getLength();
            if (oldLength > newLength || force) {
                PointSetPath.appendAns(pointSetPath.file, ans);
                pointSetPath.tsp = ans;
                terminal.history.addLine(oldLength + "New Shortest Tour Found", palette.success);
            }
            terminal.history.addWord("Stored shortest tour length was:", palette.text);
            terminal.history.addLine(oldLength + "", palette.mutedText);
            terminal.history.addWord("New tour length is:", palette.text);
            terminal.history.addLine(newLength + "", palette.mutedText);
        }
        return new String[] { cmd };
    }
}
