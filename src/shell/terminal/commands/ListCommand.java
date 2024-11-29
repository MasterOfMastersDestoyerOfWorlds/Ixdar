package shell.terminal.commands;

import java.io.File;

import shell.objects.PointCollection;
import shell.render.color.Color;
import shell.terminal.Terminal;
import shell.ui.input.KeyActions;

public class ListCommand extends TerminalCommand {

    public static String cmd = "ls";

    public static OptionList keyOptionAliases = new OptionList("keys", "keymap", "shortcuts", "bindings",
            "keybindings");

    public static OptionList commandOptionAliases = new OptionList("command", "cmds", "cmd", "commands",
            "commandlist");

    public static OptionList pointCollectionOptionAliases = new OptionList("pc", "pointcollection", "pointcollections",
            "object", "obj", "objects", "objs");

    @Override
    public String fullName() {
        return "list";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "list information about an object";
    }

    @Override
    public String usage() {
        return "usage: ls|listfiles";
    }

    @Override
    public int argLength() {
        return -1;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        if (args.length == startIdx) {
            File[] solutions = new File(terminal.directory).listFiles();
            for (int i = 0; i < solutions.length; i++) {
                File f = solutions[i];
                terminal.history.addLine(f.getName(), f.isDirectory() ? Color.BLUE_WHITE : Color.IXDAR);
            }
            terminal.history.addLine("dir: " + terminal.directory, Color.GREEN);
            return new String[] { "cd " };
        } else {
            String target = args[startIdx];
            if (target.equals("values")) {

            } else if (target.equals("questions")) {

            } else if (keyOptionAliases.contains(target)) {
                for (KeyActions k : KeyActions.values()) {
                    terminal.history.addLine(k.toString(), Color.GREEN);
                }
            } else if (commandOptionAliases.contains(target)) {
                for (TerminalCommand tc : Terminal.commandList) {
                    terminal.history.addWord(tc.shortName(), Color.COMMAND);
                    terminal.history.addLine(" - " + tc.desc(), Color.GREEN);
                }
            } else if (pointCollectionOptionAliases.contains(target)) {
                for (PointCollection pc : Terminal.pointCollectionList) {
                    terminal.history.addWord(pc.shortName(), Color.COMMAND);
                    terminal.history.addLine(" - " + pc.desc(), Color.GREEN);

                }
            }

            return null;
        }

    }
}
