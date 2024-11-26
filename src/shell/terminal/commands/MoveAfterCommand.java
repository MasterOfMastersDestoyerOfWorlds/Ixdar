package shell.terminal.commands;

import shell.exceptions.IdDoesNotExistException;
import shell.exceptions.RangeParseException;
import shell.file.FileManagement;
import shell.shell.Range;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class MoveAfterCommand extends TerminalCommand {

    public static String cmd = "ma";

    @Override
    public String fullName() {
        return "moveafter";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "move after a point a sequence of points; order by id";
    }

    @Override
    public String usage() {
        return "usage: ma|moveafter  [point at destination(id)] [id range to move(range)]";
    }

    @Override
    public int argLength() {
        return 2;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        try {
            Range idTarget = Range.parse(args[startIdx + 1]);
            int idDest = Integer.parseInt(args[startIdx]);
            Main.orgShell.moveAfter(idTarget, idDest);
            FileManagement.rewriteSolutionFile(Main.file, Main.orgShell);
            return new String[] { "ma " + idTarget + " " };

        } catch (NumberFormatException e) {
            terminal.error("arguments are not integers: " + this.usage());
        } catch (IdDoesNotExistException e) {
            terminal.error("no point with id " + e.ID + " exists");
        } catch (RangeParseException e) {
            terminal.error("could not parse target range: " + args[startIdx + 1]);
        }
        return null;
    }
}
