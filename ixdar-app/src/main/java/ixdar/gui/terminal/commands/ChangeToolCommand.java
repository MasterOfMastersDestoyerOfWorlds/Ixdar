package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.tools.Tool;
import ixdar.scenes.main.MainScene;

/**
 * Terminal command {@code ct}/{@code changetool} that swaps the active editor tool on
 * {@link MainScene} for the named {@link Tool}.
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
     * Exact number of trailing arguments expected: a single tool name.
     *
     * @return {@code 1}
     */
    @Override
    public int argLength() {
        return 1;
    }

    /**
     * Switch the active {@link MainScene} tool to {@code t}, resetting it first.
     *
     * @param t tool instance to activate
     */
    public static void run(Tool t) {
        t.reset();
        MainScene.tool = t;
    }

    /**
     * Switch the active tool by class, resolved through {@link Terminal#toolClassMap}.
     *
     * @param <E> tool subtype
     * @param type concrete tool class to look up and activate
     */
    public static <E extends Tool> void run(Class<E> type) {
        Tool t = Terminal.toolClassMap.get(type);
        run(t);
    }

    /**
     * Look up the tool named at {@code args[startIdx]} in {@link Terminal#toolMap}, activate it
     * if found, and return one {@code "ct &lt;tool&gt;"} suggestion per registered tool for tab cycling.
     *
     * @param args full tokenised command line
     * @param startIdx index of the tool-name argument in {@code args}
     * @param terminal dispatching terminal
     * @return suggestion list of {@code ct} invocations across all known tools
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        String toolName = args[startIdx];
        if (Terminal.toolMap.containsKey(toolName)) {
            ChangeToolCommand.run(Terminal.toolMap.get(toolName));
        }
        String[] toolCommands = new String[Terminal.toolMap.keySet().size()];
        int i = 0;
        for (String tool : Terminal.toolMap.keySet()) {
            toolCommands[i] = cmd + " " + tool;
            i++;
        }
        return toolCommands;
    }
}
