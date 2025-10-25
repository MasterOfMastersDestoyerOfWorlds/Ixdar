package shell.terminal.commands;

import shell.terminal.Terminal;
import shell.ui.main.Main;
import shell.ui.tools.Tool;

public class ChangeToolCommand extends TerminalCommand {

    public static String cmd = "ct";

    @Override
    public String fullName() {
        return "changetool";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "changes which tool is currently being used to view the ix file";
    }

    @Override
    public String usage() {
        return "usage: ct|changetool [tool's name]";
    }

    @Override
    public int argLength() {
        return 1;
    }

    public static void run(Tool t) {
        t.reset();
        Main.tool = t;
    }

    public static <E extends Tool> void run(Class<E> type) {
        Tool t = Terminal.toolClassMap.get(type);
        run(t);
    }

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
