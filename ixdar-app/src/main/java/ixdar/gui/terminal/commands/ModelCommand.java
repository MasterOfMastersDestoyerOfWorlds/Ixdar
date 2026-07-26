package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.scenes.model.ModelChoice;
import ixdar.scenes.model.ModelScene;

/**
 * Terminal command {@code model}/{@code ml}: switch the active {@link ModelScene} to the
 * model whose display name or path matches the argument, and recompute. Mirrors the
 * {@code ld} (load-ix) pattern, but drives an in-scene model swap instead of loading a file
 * into MainScene.
 */
@CommandAnnotation(id = "ml")
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
     * Resolve {@code args[startIdx]} against the terminal's active model scene (exact display
     * name first, then a case-insensitive substring of display name or path), request the load
     * on the render thread, and report the outcome. Errors when no scene is attached or nothing
     * matches.
     *
     * @param args full tokenised command line
     * @param startIdx index of the model token in {@code args}
     * @param terminal dispatching terminal (holds the active {@link ModelScene})
     * @return {@code null}; the command offers no follow-up suggestions
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        ModelScene scene = terminal.modelScene;
        if (scene == null) {
            terminal.error("no model scene is active");
            return null;
        }
        String token = args[startIdx];
        ModelChoice match = resolve(scene, token);
        if (match == null) {
            terminal.error("no model matching: " + token);
            return null;
        }
        scene.requestModelLoad(match.path);
        terminal.history.addLine("loading " + match.displayName, Color.COMMAND);
        return null;
    }

    private static ModelChoice resolve(ModelScene scene, String token) {
        for (ModelChoice choice : scene.availableModels()) {
            if (choice.displayName.equalsIgnoreCase(token)) {
                return choice;
            }
        }
        String lower = token.toLowerCase();
        for (ModelChoice choice : scene.availableModels()) {
            if (choice.displayName.toLowerCase().contains(lower)
                    || choice.path.toLowerCase().contains(lower)) {
                return choice;
            }
        }
        return null;
    }
}
