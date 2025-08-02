package shell.terminal.commands;

import shell.terminal.Terminal;
import shell.ui.main.Main;

public class CalculateKnotCommand extends TerminalCommand {

    public static String cmd = "ck";

    @Override
    public String fullName() {
        return "calculateknot";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "run the ixdar knot calculation";
    }

    @Override
    public String usage() {
        return "usage: ck|calculateknot";
    }

    @Override
    public int argLength() {
        return 0;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        //Main.calculateSubPaths();
        return new String[] { "ck" };
    }
}
