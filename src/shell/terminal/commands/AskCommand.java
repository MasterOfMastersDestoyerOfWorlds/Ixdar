package shell.terminal.commands;

import org.apache.commons.math3.util.Pair;

import shell.knot.VirtualPoint;
import shell.objects.PointND;
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
        } else if (questionName.equals("min")) {
            Pair<PointND, Pair<PointND, PointND>> isMinima = Main.orgShell.isLocalMinima();
            if (isMinima == null) {
                terminal.history.addLine("Calculated answer is a local minima.", Color.BLUE_WHITE);
            } else {
                PointND mP = isMinima.getFirst();
                PointND fP = isMinima.getSecond().getFirst();
                PointND sP = isMinima.getSecond().getSecond();
                terminal.history.addLine("Calculated answer is not a local minima. Move point: " + mP.getID()
                        + " between point: " + fP.getID() + " and point: " + sP.getID(), Color.RED);
                return new String[] { "mv " + mP.getID() + " " + fP.getID() + " " + sP.getID() };
            }
        } else if (questionName.equals("closest")) {
            int id1 = Integer.parseInt(args[startIdx + 1]);
            int numPrint = 5;
            if (args.length - startIdx > 2) {
                numPrint = Integer.parseInt(args[startIdx + 2]);
            }
            VirtualPoint vp = Main.shell.pointMap.get(id1);
            if (vp == null) {
                terminal.error("Point with id: " + vp + " does not exist.");
            }
            for (int i = 0; i < numPrint; i++) {
                terminal.history.addHyperString(vp.sortedSegments.get(i).toHyperString(Color.BLUE_WHITE, false, true));
            }
        }
        return null;
    }
}
