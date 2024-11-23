package shell.terminal.commands;

import shell.exceptions.IdDoesNotExistException;
import shell.exceptions.RangeParseException;
import shell.file.FileManagement;
import shell.render.color.Color;
import shell.shell.Range;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class MoveCommand extends TerminalCommand {
    @Override
    public String fullName() {
        return "move";
    }

    @Override
    public String shortName() {
        return "mv";
    }

    @Override
    public String usage() {
        return "usage: mv|move [point at destination(int)] [target point to move(int)]";
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
            terminal.history.addLine("exception: could not parse range: " + e.message, Color.RED);
        }
        return null;
    }
}
