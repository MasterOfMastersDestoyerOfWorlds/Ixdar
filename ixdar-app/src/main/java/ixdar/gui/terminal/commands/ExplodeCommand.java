package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.tools.regions.RegionLayer;

/**
 * Terminal command {@code explode}/{@code xp}: animate the ring regions' exploded view to an exact
 * amount, the scripted form of the region tool's X key.
 */
@CommandAnnotation(id = "xp", acts = Subsystem.RINGS)
public class ExplodeCommand extends TerminalCommand {

    public static String cmd = "xp";

    /**
     * Full command word: {@code "explode"}.
     *
     * @return fully-qualified command name used at the prompt
     */
    @Override
    public String fullName() {
        return "explode";
    }

    /**
     * Short alias for the command: {@code "xp"}.
     *
     * @return short command name used at the prompt
     */
    @Override
    public String shortName() {
        return cmd;
    }

    /**
     * One-line description shown in help output.
     *
     * @return human-readable summary of the command
     */
    @Override
    public String desc() {
        return "explode the ring regions apart by an amount from 0 (assembled) to 1";
    }

    /**
     * Usage hint displayed when the command is mis-invoked or {@code -h} is passed.
     *
     * @return usage string
     */
    @Override
    public String usage() {
        return "usage: xp|explode <amount 0..1>";
    }

    /**
     * The command takes the amount alone.
     *
     * @return {@code 1}
     */
    @Override
    public int argLength() {
        return 1;
    }

    /**
     * Send the ring regions' exploded view toward {@code args[startIdx]}, in whichever tool is
     * active.
     *
     * @param args     full tokenised command line
     * @param startIdx index of the amount in {@code args}
     * @param terminal dispatching terminal, whose scene shows the ring regions
     * @return {@code null}; the command offers no follow-up suggestions
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        RegionLayer regions = terminal.dataset(Subsystem.RINGS);
        float amount;
        try {
            amount = Float.parseFloat(args[startIdx]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException malformed) {
            terminal.error(usage());
            return null;
        }
        regions.explosion.animateTo(amount);
        terminal.history.addLine("exploding the regions to " + regions.explosion.target,
                Color.TEAL_DARK);
        return null;
    }
}
