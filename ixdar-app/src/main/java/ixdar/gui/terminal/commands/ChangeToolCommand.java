package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.tools.Tool;
import ixdar.scenes.main.MainScene;

@CommandAnnotation(id = "ct")
public class ChangeToolCommand extends TerminalCommand {

    public static String cmd = "ct";

    /**
     * TODO: document {@code fullName}.
     *
     * @return TODO: describe
     */
    @Override
    public String fullName() {
        return "changetool";
    }

    /**
     * TODO: document {@code shortName}.
     *
     * @return TODO: describe
     */
    @Override
    public String shortName() {
        return cmd;
    }

    /**
     * TODO: document {@code desc}.
     *
     * @return TODO: describe
     */
    @Override
    public String desc() {
        return "changes which tool is currently being used to view the ix file";
    }

    /**
     * TODO: document {@code usage}.
     *
     * @return TODO: describe
     */
    @Override
    public String usage() {
        return "usage: ct|changetool [tool's name]";
    }

    /**
     * TODO: document {@code argLength}.
     *
     * @return TODO: describe
     */
    @Override
    public int argLength() {
        return 1;
    }

    /**
     * TODO: document {@code run}.
     *
     * @param t TODO: describe
     */
    public static void run(Tool t) {
        t.reset();
        MainScene.tool = t;
    }

    /**
     * TODO: document {@code run}.
     *
     * @param <E> TODO: describe
     * @param type TODO: describe
     */
    public static <E extends Tool> void run(Class<E> type) {
        Tool t = Terminal.toolClassMap.get(type);
        run(t);
    }

    /**
     * TODO: document {@code run}.
     *
     * @param args TODO: describe
     * @param startIdx TODO: describe
     * @param terminal TODO: describe
     * @return TODO: describe
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
