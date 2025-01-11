package shell.terminal.commands;

import shell.terminal.Terminal;
import shell.ui.Canvas3D;
import shell.ui.main.Main;
import shell.ui.tools.Tool;

public class ExitCommand extends TerminalCommand {

    public static String cmd = "ex";

    @Override
    public String fullName() {
        return "exit";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "exit the tool or view";
    }

    @Override
    public String usage() {
        return "usage: ex|exit";
    }

    @Override
    public int argLength() {
        return 0;
    }

    public static void run() {
        if (Main.tool.toolType() == Tool.Type.Free) {
            Canvas3D.activate(true);
            Main.activate(false);
        }
        Main.tool.freeTool();
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        ExitCommand.run();
        return new String[] { "ex" };
    }
}
