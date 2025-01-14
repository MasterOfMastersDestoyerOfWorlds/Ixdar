package shell.terminal.commands;

import shell.knot.VirtualPoint;
import shell.render.color.Color;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class AskCommand extends TerminalCommand {

    public static String cmd = "ask";

    @Override
    public String fullName() {
        return "ask";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "ask a question about the current file current file";
    }

    @Override
    public String usage() {
        return "usage: ask [question type(dist)] [args]";
    }

    @Override
    public int argLength() {
        return -1;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        String questionName = args[startIdx];
        if (questionName.equals("dist")) {
            int id1 = Integer.parseInt(args[startIdx + 1]);
            int id2 = Integer.parseInt(args[startIdx + 2]);
            VirtualPoint vp1 = Main.shell.pointMap.get(id1);
            VirtualPoint vp2 = Main.shell.pointMap.get(id2);
            terminal.history.addLine("distance from " + id1 + " to " + id2 + " " + vp1.getSegment(vp2).distance,
                    Color.BLUE_WHITE);
            return new String[] { "ask dist " + id1 + " " + id2 };
        }
        return null;
    }
}
