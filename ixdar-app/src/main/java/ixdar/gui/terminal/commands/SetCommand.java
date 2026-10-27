package ixdar.gui.terminal.commands;

import java.util.Map;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.models.ModelChoice;
import ixdar.models.ModelCollection;
import ixdar.scenes.model.ModelScene;

/**
 * Terminal command {@code set}: set scene overrides by node path ({@code set
 * layout/field.curvature_bias=-0.5}) and rerun the current model with them; with no argument, list
 * them. An empty value clears a key. Automation sets the same overrides through
 * {@code ixdar-cli model <name> --set}.
 */
@CommandAnnotation(id = "set", acts = Subsystem.MODEL)
public class SetCommand extends TerminalCommand {

    /** Command word: {@code "set"}. */
    public static String cmd = "set";

    /**
     * Full command word: {@code "set"}.
     *
     * @return command name used at the prompt
     */
    @Override
    public String fullName() {
        return cmd;
    }

    /**
     * Short alias: the same word.
     *
     * @return command name used at the prompt
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
        return "set graph overrides by path and rerun the model";
    }

    /**
     * Usage hint displayed when the command is mis-invoked or {@code -h} is passed.
     *
     * @return usage string
     */
    @Override
    public String usage() {
        return "usage: set [path.port=value ...]  (empty value clears; no argument lists)";
    }

    /**
     * Any number of assignments, none listing the current overrides.
     *
     * @return {@code -1}, unconstrained
     */
    @Override
    public int argLength() {
        return -1;
    }

    /**
     * Sets each {@code path.port=value} on the scene's models, checked against the graph the
     * current model runs, then reloads the current model; lists the overrides when given none.
     *
     * @param args     full tokenised command line
     * @param startIdx index of the first assignment in {@code args}
     * @param terminal dispatching terminal, whose scene shows the models
     * @return {@code null}; the command offers no follow-up suggestions
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        ModelCollection models = terminal.dataset(Subsystem.MODEL);
        Map<String, Object> overrides = models.overrides;
        Color subject = terminal.scene.palette.linkText;
        if (startIdx >= args.length) {
            for (Map.Entry<String, Object> override : overrides.entrySet()) {
                terminal.history.addLine(override.getKey() + "=" + override.getValue(), subject);
            }
            if (overrides.isEmpty()) {
                terminal.history.addLine("no overrides", subject);
            }
            return null;
        }
        for (int index = startIdx; index < args.length; index++) {
            int equals = args[index].indexOf('=');
            if (equals <= 0) {
                terminal.error("expected path.port=value, got " + args[index]);
                return null;
            }
            String key = args[index].substring(0, equals);
            String value = args[index].substring(equals + 1);
            if (value.isBlank()) {
                overrides.remove(key);
                continue;
            }
            try {
                models.validateOverrideKey.accept(key);
            } catch (IllegalArgumentException invalid) {
                terminal.error(invalid.getMessage());
                return null;
            }
            overrides.put(key, ModelScene.parseOverrideValue(value));
        }
        ModelChoice current = models.current();
        if (current != null) {
            models.requestLoad.accept(current);
            terminal.history.addLine("rerunning " + current.displayName, subject);
        }
        return null;
    }
}
