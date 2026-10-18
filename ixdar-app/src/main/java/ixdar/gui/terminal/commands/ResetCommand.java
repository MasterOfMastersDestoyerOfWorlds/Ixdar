package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.gui.terminal.Terminal;
import ixdar.scenes.main.Toggle;
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
     * Apply the reset action selected by {@code option} to the appropriate sub-system(s) of
     * {@code editor}.
     *
     * @param option which target to reset (camera, tool, toggles, or all)
     * @param editor the editor whose camera and tool are reset
     */
    public static void run(ResetOption option, MainScene editor) {
        switch (option) {
        case All:
            editor.editorCamera.reset();
            editor.activeTool.deactivate();
            editor.activeTool.activate();
            break;
        case Camera:
            editor.editorCamera.reset();
        case Tool:
            editor.activeTool.deactivate();
            editor.activeTool.activate();
        case Toggles:
            Toggle.resetAll();
        default:
            break;
        }
    }

    /**
     * Match {@code args[startIdx]} against the aliases of every {@link ResetOption} and
     * dispatch to {@link #run(ResetOption, MainScene)} on the first hit. Reports a usage error to
     * the terminal if no option matches.
     *
     * @param args full tokenised command line
     * @param startIdx index of the target-name argument in {@code args}
     * @param terminal dispatching terminal (used for error reporting)
     * @return always {@code null} (this command provides no completion suggestions)
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        if (!(terminal.scene instanceof MainScene editor)) {
            terminal.error(shortName() + " is a MainScene command");
            return null;
        }
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
        run(option, editor);
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
