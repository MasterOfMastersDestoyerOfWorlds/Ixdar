package shell.terminal.commands;

import shell.terminal.Terminal;
import shell.ui.main.Main;

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

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        Main.tool.back();
        return new String[] { "ex" };
    }
}
