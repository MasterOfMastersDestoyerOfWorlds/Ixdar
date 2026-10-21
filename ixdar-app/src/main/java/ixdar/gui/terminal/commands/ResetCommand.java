package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.gui.terminal.Terminal;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;

/**
 * Terminal command {@code rst}/{@code reset} that resets a sub-system of the view (camera,
 * current tool, toggles, or all of them) to its initial state.
 */
@CommandAnnotation(id = "rst", acts = Subsystem.VIEW)
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
        return "usage: rst|reset camera|tool|toggles|all";
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
     * Reset the sub-system {@code option} names: the camera framing everything, the active tool's
     * selection, the toggles, or all three with the default tool made active again.
     *
     * @param option which target to reset (camera, tool, toggles, or all)
     * @param view the view whose camera, tool or toggles are reset
     */
    public static void run(ResetOption option, Scene view) {
        switch (option) {
        case All:
            view.frameAll();
            if (view.activeTool != view.defaultTool) {
                view.switchTool(view.defaultTool);
            } else if (view.activeTool != null) {
                view.activeTool.deactivate();
                view.activeTool.activate();
            }
            Toggle.resetAll();
            break;
        case Camera:
            view.frameAll();
            break;
        case Tool:
            if (view.activeTool != null) {
                view.activeTool.deactivate();
                view.activeTool.activate();
            }
            break;
        case Toggles:
            Toggle.resetAll();
            break;
        default:
            break;
        }
    }

    /**
     * Match {@code args[startIdx]} against the aliases of every {@link ResetOption} and
     * dispatch to {@link #run(ResetOption, Scene)} on the first hit. Reports a usage error to
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
        run(option, terminal.dataset(Subsystem.VIEW));
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
