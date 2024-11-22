package shell.terminal.commands;

import shell.exceptions.IdDoesNotExistException;
import shell.file.FileManagement;
import shell.render.color.Color;
import shell.terminal.Terminal;
import shell.ui.main.Main;

public class MoveAfterCommand extends TerminalCommand {

    @Override
    public String fullName() {
        return "moveafter";
    }

    @Override
    public String shortName() {
        return "ma";
    }

    @Override
    public String usage() {
        return "usage: ma|moveafter  [point at destination(int)] [target point to move(int)]";
    }

    @Override
    public int argLength() {
        return 2;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        try {
            int idTarget = Integer.parseInt(args[startIdx + 1]);
            int idDest = Integer.parseInt(args[startIdx]);
            Main.orgShell.moveAfter(idTarget, idDest);
            FileManagement.rewriteSolutionFile(Main.file, Main.orgShell);
            return new String[] { "ma " + idTarget + " " };

        } catch (NumberFormatException e) {
            terminal.history.addLine("exception: arguments are not integers: " + this.usage(), Color.RED);
        } catch (IdDoesNotExistException e) {
            terminal.history.addLine("exception: no point with id " + e.ID + " exists", Color.RED);
        }
        return null;
    }
}
