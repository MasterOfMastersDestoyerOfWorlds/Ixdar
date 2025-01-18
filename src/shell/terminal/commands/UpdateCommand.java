package shell.terminal.commands;

import shell.Toggle;
import shell.file.FileManagement;
import shell.render.color.Color;
import shell.shell.Shell;
import shell.terminal.Terminal;
import shell.ui.main.Main;

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
        if (Main.subPaths.size() == 1) {
            Shell ans = Main.subPaths.get(0);
            double oldLength = Main.orgShell.getLength();
            double newLength = ans.getLength();
            if (oldLength > newLength || force) {
                FileManagement.appendAns(Main.file, ans);
                Main.orgShell = ans;
                terminal.history.addLine(oldLength + "New Shortest Tour Found", Color.COMMAND);
            }
            terminal.history.addWord("Stored shortest tour length was:", Color.GREEN);
            terminal.history.addLine(oldLength + "", Color.BLUE_WHITE);
            terminal.history.addWord("New tour length is:", Color.GREEN);
            terminal.history.addLine(newLength + "", Color.BLUE_WHITE);
            if (Main.tool.canUseToggle(Toggle.Manifold)) {
                FileManagement.appendCutAns(Main.file, Main.manifolds);
            }
        }
        return new String[] { cmd };
    }
}
