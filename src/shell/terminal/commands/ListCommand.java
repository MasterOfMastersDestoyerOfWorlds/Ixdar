package shell.terminal.commands;

import java.io.File;

import shell.Toggle;
import shell.objects.PointCollection;
import shell.render.color.Color;
import shell.terminal.Terminal;
import shell.ui.input.KeyActions;
import shell.ui.tools.Tool;

public class ListCommand extends TerminalCommand {

    public static String cmd = "ls";

    public static OptionList keyOptionAliases = new OptionList("keys", "keymap", "shortcuts", "bindings",
            "keybindings");

    public static OptionList commandOptionAliases = new OptionList("command", "cmds", "cmd", "commands",
            "commandlist");

    public static OptionList toggleOptionAliases = new OptionList("toggle", "tgls", "tgl", "toggels", "toggles",
            "toggel",
            "togglelist");

    public static OptionList pointCollectionOptionAliases = new OptionList("pc", "pointcollection", "pointcollections",
            "object", "obj", "objects", "objs");

    public static OptionList toolOptionAliases = new OptionList("tools", "views", "tool", "view", "tl", "vw");

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
            } else if (toolOptionAliases.contains(target)) {
                for (Tool t : Terminal.tools) {
                    terminal.history.addWord(t.shortName(), Color.COMMAND);
                    terminal.history.addLine(" - " + t.desc(), Color.GREEN);
                }
            } else if (toggleOptionAliases.contains(target)) {
                for (Toggle t : Toggle.values()) {
                    terminal.history.addWord(t.shortName(), Color.COMMAND);
                    terminal.history.addLine(" - " + t.name() + " : " + t.value, Color.GREEN);
                }
            } else if (Terminal.commandMap.containsKey(target)) {

            }
            return null;
        }

    }
}
