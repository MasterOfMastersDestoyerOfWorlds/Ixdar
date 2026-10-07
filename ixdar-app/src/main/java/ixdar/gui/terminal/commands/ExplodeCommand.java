package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.scenes.ring.RingScene;

/**
 * Terminal command {@code explode}/{@code xp}: switch the editing scene to the region tool and
 * animate its exploded view to an exact amount, the scripted form of the X key.
 */
@CommandAnnotation(id = "xp")
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
     * Show the region tool and send its exploded view toward {@code args[startIdx]}.
     *
     * @param args     full tokenised command line
     * @param startIdx index of the amount in {@code args}
     * @param terminal dispatching terminal (holds the active {@link RingScene})
     * @return {@code null}; the command offers no follow-up suggestions
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        if (!(terminal.modelScene instanceof RingScene scene)) {
            terminal.error("RingScene is not active");
            return null;
        }
        float amount;
        try {
            amount = Float.parseFloat(args[startIdx]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException malformed) {
            terminal.error(usage());
            return null;
        }
        scene.switchTool(scene.regionTool);
        scene.regionTool.explodeTo(amount);
        terminal.history.addLine("exploding the regions to " + scene.regionTool.explosion.target,
                Color.COMMAND);
        return null;
    }
}
