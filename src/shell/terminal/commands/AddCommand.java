package shell.terminal.commands;

import shell.exceptions.TerminalParseException;
import shell.file.FileManagement;
import shell.objects.PointCollection;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class AddCommand extends TerminalCommand {

    public static String cmd = "add";

    @Override
    public String fullName() {
        return "add";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "add a point or set of points to the current file";
    }

    @Override
    public String usage() {
        return "usage: add [object type(triangle|circle|arc|point|line)] [args]";
    }

    @Override
    public int argLength() {
        return -1;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        PointCollection pc = null;
        String objectName = args[startIdx];
        try {
            for (PointCollection collection : Terminal.pointCollectionList) {
                if (collection.options().contains(objectName)) {
                    pc = collection.parseCollection(args, startIdx + 1);
                    break;
                }
            }
        } catch (TerminalParseException e) {
            terminal.error(e.getMessage());
        }
        if (pc != null) {
            FileManagement.appendLine(Main.tempFile, pc.toFileString());
            return new String[] { "add " + pc.shortName() };
        }
        return null;
    }
}
