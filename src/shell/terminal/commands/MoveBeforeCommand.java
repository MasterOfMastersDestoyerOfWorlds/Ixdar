package shell.terminal.commands;

import shell.exceptions.IdDoesNotExistException;
import shell.exceptions.RangeParseException;
import shell.file.FileManagement;
import shell.render.color.Color;
import shell.shell.Range;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class MoveBeforeCommand extends TerminalCommand {
    @Override
    public String fullName() {
        return "movebefore";
    }

    @Override
    public String shortName() {
        return "mb";
    }

    @Override
    public String usage() {
        return "usage: mb|movebefore [point at destination(int)] [id range to move(range)]";
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
            terminal.history.addLine("exception: arguments are not integers: " + this.usage(), Color.RED);
        } catch (IdDoesNotExistException e) {
            terminal.history.addLine("exception: no point with id " + e.ID + " exists", Color.RED);
        } catch (RangeParseException e) {
            terminal.history.addLine("exception: could not parse target range: " + args[startIdx + 1], Color.RED);
        }
        return null;
    }
}
