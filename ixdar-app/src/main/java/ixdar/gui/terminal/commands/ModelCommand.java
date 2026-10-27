package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.gui.terminal.Terminal;
import ixdar.models.ModelCatalog;
import ixdar.models.ModelChoice;
import ixdar.models.ModelCollection;

/**
 * Terminal command {@code model}/{@code ml}: switch the scene's models to the one whose display
 * name or path matches the argument, and recompute. Automation takes the same path through
 * {@code ixdar-cli model}, which also waits for the outcome.
 */
@CommandAnnotation(id = "ml", acts = Subsystem.MODEL)
public class ModelCommand extends TerminalCommand {

    /** Short alias for the command: {@code "ml"}. */
    public static String cmd = "ml";

    /**
     * Full command word: {@code "model"}.
     *
     * @return fully-qualified command name used at the prompt
     */
    @Override
    public String fullName() {
        return "model";
    }

    /**
     * Short alias for the command: {@code "ml"}.
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
        return "switch the scene's model and recompute";
    }

    /**
     * Usage hint displayed when the command is mis-invoked or {@code -h} is passed.
     *
     * @return usage string
     */
    @Override
    public String usage() {
        return "usage: ml|model [model name or path]";
    }

    /**
     * Exact number of trailing arguments expected: a single model token.
     *
     * @return {@code 1}
     */
    @Override
    public int argLength() {
        return 1;
    }

    /**
     * Resolve {@code args[startIdx]} against the models the scene offers (exact display name
     * first, then a case-insensitive substring of display name or path), request the load on the
     * render thread, and report the outcome. Errors when nothing matches.
     *
     * @param args full tokenised command line
     * @param startIdx index of the model token in {@code args}
     * @param terminal dispatching terminal, whose scene shows the models
     * @return {@code null}; the command offers no follow-up suggestions
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        ModelCollection models = terminal.dataset(Subsystem.MODEL);
        String token = args[startIdx];
        ModelChoice match = ModelCatalog.resolve(models.offered.get(), token);
        if (match == null) {
            terminal.error("no model matching: " + token);
            return null;
        }
        models.requestLoad.accept(match);
        terminal.history.addLine("loading " + match.displayName, terminal.scene.palette.linkText);
        return null;
    }

}
