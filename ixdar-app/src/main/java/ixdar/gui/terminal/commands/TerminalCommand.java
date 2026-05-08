package ixdar.gui.terminal.commands;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.command.TerminalOption;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.Platforms;
import ixdar.platform.file.TextFile;

/**
 * Base class for terminal commands: subclasses provide their {@link #fullName()},
 * {@link #shortName()}, {@link #desc()}, {@link #usage()}, {@link #argLength()}, and
 * {@link #run(String[], int, Terminal)} implementation; this class supplies the shared
 * help renderer and option/min-argument defaults.
 */
public abstract class TerminalCommand implements TerminalOption {

    /**
     * Print the {@code -h}/{@code --help} block for {@code command} into the terminal history:
     * the command name, its description, the contents of {@code ./src/shell/terminal/help/<fullName>.help}
     * (or an error if that file is missing), then its usage line.
     *
     * @param terminal terminal whose history receives the rendered help text
     * @param command command whose help is being printed
     */
    public static void help(Terminal terminal, TerminalOption command) {
        String commandName = command.fullName();
        String fileLoc = "./src/shell/terminal/help/" + commandName + ".help";
        terminal.history.addWord(commandName, Color.COMMAND);
        terminal.history.addLine(command.desc(), Color.GREEN);
        try {
            TextFile file = Platforms.get().loadFile(fileLoc);
            for (String line : file.getLines()) {
                terminal.history.addLine(line, Color.LIGHT_GRAY);
            }
        } catch (Exception e) {
            terminal.error("helpfile: " + commandName + ".help not found at: " + fileLoc);
        }
        terminal.history.addLine(command.usage(), Color.GREEN);
    }

    /**
     * Minimum number of trailing arguments this command accepts. Default {@code -1}
     * means the command does not enforce a minimum (it relies on {@link #argLength()} instead).
     *
     * @return minimum required argument count, or {@code -1} if unconstrained
     */
    @Override
    public int minArgLength() {
        return -1;
    }

    /**
     * Sub-options recognised by this command (used for completion and parsing).
     * Default is {@code null}, meaning the command takes no nested options.
     *
     * @return option list, or {@code null} when none are defined
     */
    @Override
    public OptionList options() {
        return null;
    }

    /**
     * Execute this command against {@code terminal}.
     *
     * @param args full tokenised command line (the command word at index 0)
     * @param startIdx index of the first command-specific argument in {@code args}
     * @param terminal terminal that dispatched the command and receives any output
     * @return suggested follow-up command lines for tab-completion, or {@code null} if none
     */
    public abstract String[] run(String[] args, int startIdx, Terminal terminal);

}
