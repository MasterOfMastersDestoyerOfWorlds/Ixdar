package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.Toggle;
import ixdar.scenes.main.MainScene;

/**
 * Terminal command {@code rst}/{@code reset} that resets a sub-system on {@link MainScene}
 * (camera, current tool, toggles, or all of them) to its initial state.
 */
@CommandAnnotation(id = "rst")
public class ResetCommand extends TerminalCommand {

    public static String cmd = "rst";

    /**
     * Full command word: {@code "reset"}.
     *
     * @return fully-qualified command name used at the prompt
     */
    @Override
    public String fullName() {
        return "reset";
    }

    /**
     * Short alias for the command: {@code "rst"}.
     *
     * @return short command name used at the prompt
     */
    @Override
    public String shortName() {
        return cmd;
    }

    /**
     * One-line description shown in help output.
     *
     * @return human-readable summary of the command
     */
    @Override
    public String desc() {
        return "reset an object to its initial state or reinitialize it";
    }

    /**
     * Usage hint displayed when the command is mis-invoked or {@code -h} is passed.
     *
     * @return usage string
     */
    @Override
    public String usage() {
        return "usage: rst|reset camera|tool|all";
    }

    /**
     * Exact number of trailing arguments expected: a single target name.
     *
     * @return {@code 1}
     */
    @Override
    public int argLength() {
        return 1;
    }

    /**
     * Apply the reset action selected by {@code option} to the appropriate {@link MainScene}
     * sub-system(s).
     *
     * @param option which target to reset (camera, tool, toggles, or all)
     */
    public static void run(ResetOption option) {
        switch (option) {
        case All:
            MainScene.camera.reset();
            MainScene.tool.reset();
            break;
        case Camera:
            MainScene.camera.reset();
        case Tool:
            MainScene.tool.reset();
        case Toggles:
            Toggle.resetAll();
        default:
            break;
        }
    }

    /**
     * Match {@code args[startIdx]} against the aliases of every {@link ResetOption} and
     * dispatch to {@link #run(ResetOption)} on the first hit. Reports a usage error to
     * the terminal if no option matches.
     *
     * @param args full tokenised command line
     * @param startIdx index of the target-name argument in {@code args}
     * @param terminal dispatching terminal (used for error reporting)
     * @return always {@code null} (this command provides no completion suggestions)
     */
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
    public enum ResetOption {
        Camera("camera"),
        Tool("tool"),
        Toggles("tgls", "tgl", "toggle", "toggles"),
        All("all");

        public String[] option;

        private ResetOption(String... option) {
            this.option = option;
        }

        /**
         * Render this option as its space-separated list of accepted aliases.
         *
         * @return concatenation of every alias followed by a single space
         */
        @Override
        public String toString() {
            String str = "";
            for (int i = 0; i < option.length; i++) {
                str += option[i] + " ";
            }
            return str;
        }
    }
}
