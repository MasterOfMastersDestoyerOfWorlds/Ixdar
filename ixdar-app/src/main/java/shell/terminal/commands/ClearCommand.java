package shell.terminal.commands;

import shell.render.text.HyperString;
import shell.terminal.Terminal;

public class ClearCommand extends TerminalCommand {

    public static String cmd = "cl";

    @Override
    public String fullName() {
        return "clear";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "clear the terminal";
    }

    @Override
    public String usage() {
        return "usage: cl|clear";
    }

    @Override
    public int argLength() {
        return 0;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        terminal.history = new HyperString();
        return new String[] { cmd };
    }
}
