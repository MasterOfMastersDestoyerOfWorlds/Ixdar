package shell.terminal.commands;

import java.util.ArrayList;

import org.apache.commons.math3.util.Pair;

import shell.knot.Knot;
import shell.knot.Segment;
import shell.point.PointND;
import shell.render.color.Color;
import shell.render.text.HyperString;
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
            Knot vp1 = Main.shell.pointMap.get(id1);
            Knot vp2 = Main.shell.pointMap.get(id2);
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
            Knot vp = Main.shell.pointMap.get(id1);
            if (vp == null) {
                terminal.error("Point with id: " + vp + " does not exist.");
            } else if (numPrint > vp.sortedSegments.size()) {
                terminal.error("Point does not have " + numPrint + " segments to display");
            } else {
                for (int i = 0; i < numPrint; i++) {
                    HyperString seg = vp.sortedSegments.get(i).toHyperString(Color.BLUE_WHITE, false, true);
                    seg.addWord(" || ");
                    terminal.history.addHyperString(seg);
                }
                terminal.history.newLine();
            }
        } else if (questionName.equals("sortedAll")) {
            int numPrint = Main.shell.sortedSegments.size();
            if (args.length - startIdx > 1) {
                numPrint = Integer.parseInt(args[startIdx + 1]);
            }
            if (numPrint > Main.shell.sortedSegments.size()) {
                terminal.error("There are not " + numPrint + " Points");
            } else {
                int i = 0;
                for (Segment s : Main.shell.sortedSegments) {
                    HyperString seg = s.toHyperString(Color.BLUE_WHITE, false, true);
                    terminal.history.addHyperString(seg);
                    terminal.history.newLine();
                    i++;
                    if (i > numPrint) {
                        break;
                    }
                }
                terminal.history.newLine();
            }
        } else if (questionName.equals("sorted")) {
            int numPrint = Main.shell.pointMap.size();
            if (args.length - startIdx > 1) {
                numPrint = Integer.parseInt(args[startIdx + 1]);
            }
            if (numPrint > Main.shell.pointMap.size()) {
                terminal.error("There are not " + numPrint + " Points");
            } else {
                ArrayList<Segment> segements = new ArrayList<>();
                for (int i = 0; i < numPrint; i++) {
                    Knot vp = Main.shell.pointMap.get(i);
                    if (vp.sortedSegments.size() > 1) {
                        if (!!vp.isSingleton()) {
                            segements.add(vp.sortedSegments.get(0));
                            segements.add(vp.sortedSegments.get(1));
                        }
                    }
                }
                segements.sort(null);
                for (Segment s : segements) {
                    HyperString seg = s.toHyperString(Color.BLUE_WHITE, false, true);
                    terminal.history.addHyperString(seg);
                    terminal.history.newLine();
                }
                terminal.history.newLine();
            }
        } else if (questionName.equals("innerC")) {
            int id1 = Integer.parseInt(args[startIdx + 1]);
            int numPrint = 2;
            if (args.length - startIdx > 2) {
                numPrint = Integer.parseInt(args[startIdx + 2]);
            }
            Knot vp = Main.shell.pointMap.get(id1);
            if (vp == null) {
                terminal.error("Point with id: " + vp + " does not exist.");
            }
            ArrayList<Long> seen = new ArrayList<>();
            ArrayList<Long> green = new ArrayList<>();

            for (Knot innerVp : vp.knotPointsFlattened) {
                for (int i = 0; i < numPrint; i++) {
                    Segment seg = innerVp.sortedSegments.get(i);
                    if (seen.contains(seg.id)) {
                        green.add(seg.id);
                    }
                    seen.add(seg.id);
                }
            }
            for (Knot innerVp : vp.knotPointsFlattened) {
                for (int i = 0; i < numPrint; i++) {
                    Segment segment = innerVp.sortedSegments.get(i);
                    HyperString str = null;
                    if (green.contains(segment.id)) {
                        str = segment.toHyperString(Color.GREEN, false, true);
                    } else {
                        str = segment.toHyperString(Color.BLUE_WHITE, false, true);
                    }
                    str.addWord(" || ");
                    terminal.history.addHyperString(str);
                }
                terminal.history.newLine();
            }
            terminal.history.newLine();
        }
        return null;
    }
}
