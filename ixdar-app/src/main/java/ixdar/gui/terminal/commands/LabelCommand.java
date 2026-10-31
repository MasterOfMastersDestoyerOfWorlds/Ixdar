package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.geometry.mesh.data.labels.MeshLabels;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.PaneTypes;
import ixdar.platform.Platforms;
import ixdar.scenes.main.Toggle;
import ixdar.tools.regions.RegionLayer;
import ixdar.tools.regions.RingRegionTool;
import ixdar.tools.ring.RingTool;

/**
 * Terminal command {@code label}/{@code lb}: name the region tool's selected regions as one part,
 * each kept as the point the selection picked inside it, then hand the keys back to the scene.
 */
@CommandAnnotation(id = "lb", acts = Subsystem.RINGS)
public class LabelCommand extends TerminalCommand {

    public static String cmd = "lb";

    /**
     * Full command word: {@code "label"}.
     *
     * @return fully-qualified command name used at the prompt
     */
    @Override
    public String fullName() {
        return "label";
    }

    /**
     * Short alias for the command: {@code "lb"}.
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
        return "name the region tool's selected regions as one part, saved with the rings";
    }

    /**
     * Usage hint displayed when the command is mis-invoked or {@code -h} is passed.
     *
     * @return usage string
     */
    @Override
    public String usage() {
        return "usage: lb|label <part name, such as claw_left>";
    }

    /**
     * The command takes the part name alone.
     *
     * @return {@code 1}
     */
    @Override
    public int argLength() {
        return 1;
    }

    /**
     * Give each selected region the part name, in place of any it had, by its picked point; a
     * name that is no identifier, names a ring, or meets no selection is refused. The scene's labels
     * are then shown, part names and colours, so the new name shows.
     *
     * @param args     full tokenised command line
     * @param startIdx index of the part name in {@code args}
     * @param terminal dispatching terminal, whose scene shows the ring regions
     * @return {@code null}; the command offers no follow-up suggestions
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        RegionLayer layer = terminal.dataset(Subsystem.RINGS);
        RingRegionTool tool = layer.scene.regionTool;
        RingRegions regions = layer.regions;
        String name = args[startIdx];
        String refusal = MeshLabels.nameRefusal(name);
        if (refusal.isEmpty() && RingTool.isRingLabel(name)) {
            refusal = name + " is how rings are named; give the part a name of its own";
        }
        if (refusal.isEmpty() && (regions == null || tool.selectedCount() == 0
                || tool.selectedRegions.length != regions.regionCount)) {
            refusal = "no region is selected; click the regions to name in the region tool first";
        }
        if (!refusal.isEmpty()) {
            tool.lastError = refusal;
            terminal.error(refusal);
            return null;
        }
        int named = 0;
        for (float[] point : tool.pickedPoints) {
            int region = regions.regionByActiveFace[regions.nearestActiveFace(point[0], point[1],
                    point[2])];
            layer.partLabels.unlabel(regions, region);
            layer.partLabels.add(name, point);
            named++;
        }
        layer.scene.showLabels = true;
        layer.labelsShownChanged();
        layer.labelsChanged();
        tool.clearSelection();
        tool.lastError = "";
        tool.lastRow = "named " + named + " region(s) " + name + "; unsaved";
        Platforms.get().log(RingRegionTool.LOG_PREFIX + tool.lastRow);
        terminal.history.addLine(tool.lastRow, terminal.scene.palette.linkText);
        Toggle.setPanelFocus(PaneTypes.KnotView);
        return null;
    }
}
