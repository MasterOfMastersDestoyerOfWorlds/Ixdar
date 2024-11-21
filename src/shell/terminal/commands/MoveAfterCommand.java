package shell.terminal.commands;

import java.util.ArrayList;

import shell.exceptions.IdDoesNotExistException;
import shell.file.FileManagement;
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
    public String run(String[] args, int startIdx, ArrayList<String> history) {
        try {
            int idTarget = Integer.parseInt(args[startIdx + 1]);
            int idDest = Integer.parseInt(args[startIdx]);
            Main.orgShell.moveAfter(idTarget, idDest);
            FileManagement.rewriteSolutionFile(Main.file, Main.orgShell);
            return "ma " + idTarget + " ";

        } catch (NumberFormatException e) {
            history.add("exception: arguments are not integers: " + this.usage());
        } catch (IdDoesNotExistException e) {
            history.add("exception: no point with id " + e.ID + " exists");
        }
        return "";
    }
}
