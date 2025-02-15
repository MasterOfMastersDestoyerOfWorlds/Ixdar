package shell.terminal.commands;

import shell.file.FileManagement;
import shell.terminal.Terminal;
import shell.ui.main.Main;

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
        FileManagement.appendComment(Main.file, commentText);
        return new String[] {};
    }
}
