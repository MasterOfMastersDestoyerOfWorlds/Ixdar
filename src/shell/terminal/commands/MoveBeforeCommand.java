package shell.terminal.commands;

import shell.exceptions.IdDoesNotExistException;
import shell.exceptions.TerminalParseException;
import shell.file.FileManagement;
import shell.shell.Range;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class MoveBeforeCommand extends TerminalCommand {

    public static String cmd = "mb";

    @Override
    public String fullName() {
        return "movebefore";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "move before a point a sequence of points; order by id";
    }

    @Override
    public String usage() {
        return "usage: mb|movebefore [point at destination(id)] [id range to move(range)]";
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
            Main.orgShell.moveBefore(idTarget, idDest);
            FileManagement.rewriteSolutionFile(Main.file, Main.orgShell);
            return new String[] { "mb " + idTarget + " " };

        } catch (NumberFormatException e) {
            terminal.error("arguments are not integers: " + this.usage());
        } catch (IdDoesNotExistException e) {
            terminal.error("no point with id " + e.ID + " exists");
        } catch (TerminalParseException e) {
            terminal.error("could not parse target range: " + args[startIdx + 1]);
        }
        return null;
    }
}
