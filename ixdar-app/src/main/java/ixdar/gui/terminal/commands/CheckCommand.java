package ixdar.gui.terminal.commands;

import java.io.File;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.graphics.render.color.Palette;
import ixdar.gui.terminal.Terminal;

@CommandAnnotation(id = "chk", acts = Subsystem.TERMINAL)
public class CheckCommand extends TerminalCommand {

    public static String cmd = "chk";

    @Override
    public String fullName() {
        return "check";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "check a value or ask a question about an object";
    }

    @Override
    public String usage() {
        return "usage: chk|check [value to display or question to ask(Value|Question)]";
    }

    @Override
    public int argLength() {
        return 0;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        Palette palette = terminal.scene.palette;
        File[] solutions = new File(terminal.directory).listFiles();
        for (int i = 0; i < solutions.length; i++) {
            File f = solutions[i];
            terminal.history.addLine(f.getName(), f.isDirectory() ? palette.mutedText : palette.accent);
        }
        terminal.history.addLine("dir: " + terminal.directory, palette.text);
        return new String[] { "cd " };

    }
}
