package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.common.exceptions.IdDoesNotExistException;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.geometry.shell.Range;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.file.FileManagement;
import ixdar.scenes.main.MainScene;

@CommandAnnotation(id = "mb")
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
            MainScene.orgShell.moveBefore(idTarget, idDest);
            FileManagement.rewriteSolutionFile(MainScene.file.getPath(), MainScene.orgShell);
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
