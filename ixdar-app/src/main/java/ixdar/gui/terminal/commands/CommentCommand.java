package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.file.FileManagement;
import ixdar.scenes.main.MainScene;

@CommandAnnotation(id = "cmt")
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
        String commentText = "";
        for (int i = startIdx; i < args.length; i++) {
            commentText += " " + args[i];
        }
        FileManagement.appendComment(MainScene.file, commentText);
        return new String[] {};
    }
}
