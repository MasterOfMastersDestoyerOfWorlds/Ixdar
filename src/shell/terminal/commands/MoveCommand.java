package shell.terminal.commands;

import shell.exceptions.IdDoesNotExistException;
import shell.file.FileManagement;
import shell.render.color.Color;
import shell.render.text.HyperString;
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
    public String run(String[] args, int startIdx, HyperString history) {
        try {
            int idTarget = Integer.parseInt(args[startIdx + 1]);
            int idDest = Integer.parseInt(args[startIdx]);
            Main.orgShell.moveBefore(idTarget, idDest);
            FileManagement.rewriteSolutionFile(Main.file, Main.orgShell);
            return "mb " + idTarget + " ";

        } catch (NumberFormatException e) {
            history.addLine("exception: arguments are not integers: " + this.usage(), Color.RED);
        } catch (IdDoesNotExistException e) {
            history.addLine("exception: no point with id " + e.ID + " exists", Color.RED);
        }
        return "";
    }
}
