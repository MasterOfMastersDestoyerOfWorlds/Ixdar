package shell.terminal.commands;

import shell.terminal.Terminal;
import shell.ui.main.Main;

public class ResetCommand extends TerminalCommand {
    public enum ResetOption {
        Camera("camera"),
        Tool("tool"),
        All("all");

        public String option;

        private ResetOption(String option) {
            this.option = option;
        }

        @Override
        public String toString() {
            return option;
        }
    }

    public static String cmd = "rst";

    @Override
    public String fullName() {
        return "reset";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "reset an object to its initial state or reinitialize it";
    }

    @Override
    public String usage() {
        return "usage: rst|reset camera|tool|all";
    }

    @Override
    public int argLength() {
        return 1;
    }

    public static void run(ResetOption option) {
        switch (option) {
            case All:
                Main.camera.reset();
                Main.tool.reset();
                break;
            case Camera:
                Main.camera.reset();
            case Tool:
                Main.tool.reset();
            default:
                break;
        }
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        ResetOption option = null;
        for (ResetOption r : ResetOption.values()) {
            if (r.option.equals(args[startIdx])) {
                option = r;
            }
        }
        if (option == null) {
            terminal.error("cannot reset object: " + this.usage());
        }
        run(option);
        return null;
    }
}
