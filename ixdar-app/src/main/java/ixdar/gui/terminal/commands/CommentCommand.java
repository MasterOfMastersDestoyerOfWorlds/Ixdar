package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.geometry.tsp.PointSetPath;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.file.FileManagement;

@CommandAnnotation(id = "cmt", acts = {Subsystem.FILES, Subsystem.KNOT})
public class CommentCommand extends TerminalCommand {

    public static String cmd = "cmt";

    @Override
    public String fullName() {
        return "comment";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "append a comment to the currently loaded ixdar file";
    }

    @Override
    public String usage() {
        return "usage: cmt|comment comment's text here";
    }

    @Override
    public int argLength() {
        return -1;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        PointSetPath pointSetPath = terminal.dataset(Subsystem.KNOT);
        String commentText = "";
        for (int i = startIdx; i < args.length; i++) {
            commentText += " " + args[i];
        }
        FileManagement.appendComment(pointSetPath.file, commentText);
        return new String[] {};
    }
}
