package ixdar.gui.terminal.commands;

import java.io.File;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.annotations.command.OptionList;
import ixdar.annotations.command.TerminalOption;
import ixdar.annotations.tool.ToolRegistry_Tools;
import ixdar.geometry.tsp.input.PointCollection;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

@CommandAnnotation(id = "ls", acts = {Subsystem.TERMINAL, Subsystem.VIEW})
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
                terminal.history.addLine(f.getName(), f.isDirectory() ? Color.BLUE_GREY : Color.IXDAR);
            }
            terminal.history.addLine("dir: " + terminal.directory, Color.GREEN);
            return new String[] { "cd " };
        } else {
            String target = args[startIdx];
            Scene view = terminal.dataset(Subsystem.VIEW);
            if (target.equals("values")) {

            } else if (target.equals("questions")) {

            } else if (keyOptionAliases.contains(target)) {
                for (int section = 0; section < view.controls.size(); section++) {
                    if (!view.controls.get(section).isEmpty()) {
                        terminal.history.addLine(view.controlSectionName(section), Color.IXDAR);
                    }
                    for (ControlHint hint : view.controls.get(section)) {
                        terminal.history.addLine(hint.toString(), Color.GREEN);
                    }
                }
            } else if (commandOptionAliases.contains(target)) {
                for (TerminalOption tc : Terminal.commandList) {
                    if (terminal.refusal(tc) != null) {
                        continue;
                    }
                    terminal.history.addWord(tc.shortName(), Color.TEAL_DARK);
                    terminal.history.addLine(" - " + tc.desc(), Color.GREEN);
                }
            } else if (pointCollectionOptionAliases.contains(target)) {
                for (PointCollection pc : Terminal.pointCollectionList) {
                    terminal.history.addWord(pc.shortName(), Color.TEAL_DARK);
                    terminal.history.addLine(" - " + pc.desc(), Color.GREEN);
                }
            } else if (toolOptionAliases.contains(target)) {
                for (String id : ToolRegistry_Tools.MAP.keySet()) {
                    if (!ToolRegistry_Tools.HOSTED_BY.get(id).test(view)) {
                        continue;
                    }
                    String shortName = id;
                    String desc = "";
                    for (Tool t : view.tools) {
                        if (t.toolName().equals(id)) {
                            shortName = t.shortName();
                            desc = t.desc();
                        }
                    }
                    terminal.history.addWord(shortName, Color.TEAL_DARK);
                    terminal.history.addLine(" - " + desc, Color.GREEN);
                }
            } else if (toggleOptionAliases.contains(target)) {
                for (Toggle t : Toggle.values()) {
                    terminal.history.addWord(t.shortName(), Color.TEAL_DARK);
                    terminal.history.addLine(" - " + t.name() + " : " + t.value, Color.GREEN);
                }
            } else if (Terminal.commandMap.containsKey(target)) {

            }
            return null;
        }

    }
}
