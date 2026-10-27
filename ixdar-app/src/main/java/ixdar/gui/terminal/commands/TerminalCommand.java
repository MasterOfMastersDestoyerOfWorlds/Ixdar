package ixdar.gui.terminal.commands;

import ixdar.annotations.command.OptionList;
import ixdar.annotations.command.TerminalOption;
import ixdar.graphics.render.color.Palette;
import ixdar.gui.terminal.Terminal;
import ixdar.platform.Platforms;

/**
 * Base class for terminal commands: subclasses provide their {@link #fullName()},
 * {@link #shortName()}, {@link #desc()}, {@link #usage()}, {@link #argLength()}, and
 * {@link #run(String[], int, Terminal)} implementation; this class supplies the shared
 * help renderer and option/min-argument defaults.
 */
public abstract class TerminalCommand implements TerminalOption {

    public static final String HELP_RESOURCE_FOLDER = "terminal/help";

    /**
     * Print the {@code -h}/{@code --help} block for {@code command} into the terminal history:
     * the command name, its description, its {@link #HELP_RESOURCE_FOLDER} resource (or an error
     * when it has none), then its usage line, in that order even when the platform loads
     * asynchronously.
     *
     * @param terminal terminal whose history receives the rendered help text
     * @param command command whose help is being printed
     */
    public static void help(Terminal terminal, TerminalOption command) {
        String helpFile = command.fullName() + ".help";
        Palette palette = terminal.scene.palette;
        terminal.history.addWord(command.fullName(), palette.linkText);
        terminal.history.addLine(command.desc(), palette.text);
        int platformId = Platforms.gl().getPlatformID();
        Platforms.get().loadSourceAsync(HELP_RESOURCE_FOLDER, helpFile, platformId, text -> {
            if (text.isEmpty()) {
                terminal.error("no help file " + HELP_RESOURCE_FOLDER + "/" + helpFile);
            } else {
                for (String line : text.split("\n")) {
                    terminal.history.addLine(line, palette.mutedText);
                }
            }
            terminal.history.addLine(command.usage(), palette.text);
        });
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
