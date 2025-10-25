package shell.terminal.commands;

import shell.Toggle;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class ResetCommand extends TerminalCommand {
    public enum ResetOption {
        Camera("camera"),
        Tool("tool"),
        Toggles("tgls", "tgl", "toggle", "toggles"),
        All("all");

        public String[] option;

        private ResetOption(String... option) {
            this.option = option;
        }

        @Override
        public String toString() {
            String str = "";
            for (int i = 0; i < option.length; i++) {
                str += option[i] + " ";
            }
            return str;
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
        case Toggles:
            Toggle.resetAll();
        default:
            break;
        }
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        ResetOption option = null;
        for (ResetOption r : ResetOption.values()) {
            for (int i = 0; i < r.option.length; i++) {
                if (r.option[i].equals(args[startIdx])) {
                    option = r;
                    break;
                }
            }
            if (option != null) {
                break;
            }
        }
        if (option == null) {
            terminal.error("cannot reset object: " + this.usage());
            return null;
        }
        run(option);
        return null;
    }
}
