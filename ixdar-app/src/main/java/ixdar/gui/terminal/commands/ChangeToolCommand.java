package ixdar.gui.terminal.commands;

import java.util.ArrayList;
import java.util.Arrays;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.tool.ToolRegistry_Tools;
import ixdar.gui.terminal.Terminal;
import ixdar.scenes.Scene;
import ixdar.tools.Tool;

/**
 * Terminal command {@code ct}/{@code changetool} that switches the current scene to a tool of the
 * generated tool registry its scene type hosts.
 */
@CommandAnnotation(id = "ct")
public class ChangeToolCommand extends TerminalCommand {

    public static String cmd = "ct";

    /**
     * Full command word: {@code "changetool"}.
     *
     * @return fully-qualified command name used at the prompt
     */
    @Override
    public String fullName() {
        return "changetool";
    }

    /**
     * Short alias for the command: {@code "ct"}.
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
        return "changes which tool is currently being used to view the ix file";
    }

    /**
     * Usage hint displayed when the command is mis-invoked or {@code -h} is passed.
     *
     * @return usage string
     */
    @Override
    public String usage() {
        return "usage: ct|changetool [tool's name]";
    }

    /**
     * Any number of trailing words, which together name the tool, as some names have spaces.
     *
     * @return {@code -1}
     */
    @Override
    public int argLength() {
        return -1;
    }

    /**
     * Switch the current scene to the registered tool the words after {@code ct} name, by id or by
     * a hosted tool's short name: the scene's own instance, else one the registry builds for it.
     * A tool another scene type hosts is refused.
     *
     * @param args full tokenised command line
     * @param startIdx index of the tool name's first word in {@code args}
     * @param terminal dispatching terminal
     * @return one {@code ct} suggestion per registered tool the current scene can host
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        String name = String.join(" ", Arrays.copyOfRange(args, startIdx, args.length));
        Scene scene = Scene.instance;
        String toolId = name;
        for (Tool tool : scene.tools) {
            if (tool.shortName().equals(name)) {
                toolId = tool.toolName();
            }
        }
        ArrayList<String> toolCommands = new ArrayList<>();
        for (String id : ToolRegistry_Tools.MAP.keySet()) {
            if (ToolRegistry_Tools.HOSTED_BY.get(id).test(scene)) {
                toolCommands.add(cmd + " " + id);
            }
        }
        String[] suggestions = toolCommands.toArray(new String[0]);
        if (!ToolRegistry_Tools.MAP.containsKey(toolId)) {
            terminal.error("no tool named " + name);
            return suggestions;
        }
        if (!ToolRegistry_Tools.HOSTED_BY.get(toolId).test(scene)) {
            terminal.error(toolId + " is a " + ToolRegistry_Tools.HOST_NAMES.get(toolId) + " tool");
            return suggestions;
        }
        Tool hosted = null;
        for (Tool tool : scene.tools) {
            if (tool.toolName().equals(toolId)) {
                hosted = tool;
            }
        }
        if (hosted == null) {
            hosted = ToolRegistry_Tools.MAP.get(toolId).apply(scene);
            scene.tools.add(hosted);
        }
        scene.switchTool(hosted);
        return suggestions;
    }
}
