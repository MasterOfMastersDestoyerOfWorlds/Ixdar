package shell.terminal.commands;

import java.util.ArrayList;

import shell.exceptions.IdDoesNotExistException;
import shell.file.FileManagement;
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
    public String run(String[] args, int startIdx, ArrayList<String> history) {
        try {
            int idTarget = Integer.parseInt(args[startIdx + 1]);
            int idDest = Integer.parseInt(args[startIdx]);
            Main.orgShell.moveBefore(idTarget, idDest);
            FileManagement.rewriteSolutionFile(Main.file, Main.orgShell);
            return "mb " + idTarget + " ";

        } catch (NumberFormatException e) {
            history.add("exception: arguments are not integers: " + this.usage());
        } catch (IdDoesNotExistException e) {
            history.add("exception: no point with id " + e.ID + " exists");
        }
        return "";
    }
}
