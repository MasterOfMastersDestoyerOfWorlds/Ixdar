package ixdar.gui.terminal.commands;

import java.util.ArrayList;
import java.util.List;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.automation.endpoints.ui.SelectionBounds;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.PaneTypes;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.regions.RegionLayer;
import ixdar.tools.ring.RingTool;

/**
 * Terminal command {@code ringdelete}/{@code rd}: delete confirmed rings by the number drawn beside
 * each or by its live label, in the ring tool, as one edit Ctrl+Z undoes.
 */
@CommandAnnotation(id = "rd", acts = Subsystem.RINGS)
public class RingDeleteCommand extends TerminalCommand {

    public static String cmd = "rd";

    /**
     * Full command word: {@code "ringdelete"}.
     *
     * @return fully-qualified command name used at the prompt
     */
    @Override
    public String fullName() {
        return "ringdelete";
    }

    /**
     * Short alias for the command: {@code "rd"}.
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
        return "delete rings by their drawn numbers or live labels in the ring tool, one undoable edit";
    }

    /**
     * Usage hint displayed when the command is mis-invoked or {@code -h} is passed.
     *
     * @return usage string
     */
    @Override
    public String usage() {
        return "usage: rd|ringdelete <ring numbers or live labels, comma-separated, such as 12,ring_07>";
    }

    /**
     * The command takes the comma-separated list alone.
     *
     * @return {@code 1}
     */
    @Override
    public int argLength() {
        return 1;
    }

    /**
     * Switch to the ring tool and delete the listed rings there, so Ctrl+Z restores them; a list
     * naming any ring that cannot go is refused whole.
     *
     * @param args     full tokenised command line
     * @param startIdx index of the list in {@code args}
     * @param terminal dispatching terminal, whose scene holds the rings
     * @return {@code null}; the command offers no follow-up suggestions
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        RegionLayer layer = terminal.dataset(Subsystem.RINGS);
        RingScene scene = layer.scene;
        RingTool rings = scene.ringTool;
        scene.switchTool(rings);
        List<String> labels = new ArrayList<>();
        for (String field : args[startIdx].split(SelectionBounds.NUMBER_SEPARATOR)) {
            if (!field.isBlank()) {
                labels.add(field.strip());
            }
        }
        if (!rings.deleteLabelledRings(labels)) {
            terminal.error(rings.lastError);
            return null;
        }
        terminal.history.addLine(rings.lastRow, terminal.scene.palette.linkText);
        Toggle.setPanelFocus(PaneTypes.KnotView);
        return null;
    }
}
