package ixdar.gui.terminal.commands;

import org.joml.Vector3f;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.annotations.command.Subsystem;
import ixdar.automation.endpoints.ui.SelectionBounds;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.gui.ui.PaneTypes;
import ixdar.platform.Platforms;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.regions.RegionLayer;
import ixdar.tools.regions.RingRegionTool;

/**
 * Terminal command {@code select}/{@code sel}: select ring regions by number or part name in the
 * region tool, as clicks on them would, and frame the camera on the selection.
 */
@CommandAnnotation(id = "sel", acts = Subsystem.RINGS)
public class SelectCommand extends TerminalCommand {

    public static String cmd = "sel";

    /**
     * Full command word: {@code "select"}.
     *
     * @return fully-qualified command name used at the prompt
     */
    @Override
    public String fullName() {
        return "select";
    }

    /**
     * Short alias for the command: {@code "sel"}.
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
        return "select ring regions by number or part name in the region tool and frame them";
    }

    /**
     * Usage hint displayed when the command is mis-invoked or {@code -h} is passed.
     *
     * @return usage string
     */
    @Override
    public String usage() {
        return "usage: sel|select <region numbers and part names, comma-separated, such as 3,claw_left>";
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
     * Switch to the region tool and replace its selection with the listed regions, each picked at
     * one of its faces as a click would, then frame the camera on them; unknown names are refused.
     *
     * @param args     full tokenised command line
     * @param startIdx index of the list in {@code args}
     * @param terminal dispatching terminal, whose scene shows the ring regions
     * @return {@code null}; the command offers no follow-up suggestions
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        RegionLayer layer = terminal.dataset(Subsystem.RINGS);
        RingScene scene = layer.scene;
        RingRegionTool tool = scene.regionTool;
        scene.switchTool(tool);
        // The layer builds the regions on its frame; running it now lets one line select them.
        layer.perFrame();
        RingRegions regions = layer.regions;
        MeshTopology surface = scene.halfEdgeSurface();
        String asked = args[startIdx];
        String refusal = regions == null || surface == null || regions.mesh != surface
                ? "no regions yet: wait for the model to load, then select" : "";
        boolean[] wanted = new boolean[refusal.isEmpty() ? regions.regionCount : 0];
        for (String field : refusal.isEmpty() ? asked.split(SelectionBounds.NUMBER_SEPARATOR) : new String[0]) {
            String name = field.strip();
            int region = name.matches("\\d+") ? Integer.parseInt(name) : -1;
            int part = layer.partLabels.names.indexOf(name);
            int[] labelByRegion = layer.partLabels.labelByRegion;
            for (int labelled = 0; part >= 0 && labelled < labelByRegion.length
                    && labelled < regions.regionCount; labelled++) {
                wanted[labelled] |= labelByRegion[labelled] == part;
            }
            if (part < 0 && (region < 0 || region >= regions.regionCount)) {
                refusal = "no region or part '" + name + "'; there are " + regions.regionCount
                        + " regions, numbered from 0 as the items list them, and the parts "
                        + layer.partLabels.names;
            } else if (part < 0) {
                wanted[region] = true;
            }
        }
        boolean anyWanted = false;
        for (boolean picked : wanted) {
            anyWanted |= picked;
        }
        if (refusal.isEmpty() && !anyWanted) {
            refusal = "'" + asked + "' labels no region now";
        }
        if (!refusal.isEmpty()) {
            tool.lastError = refusal;
            terminal.error(refusal);
            return null;
        }
        tool.releaseExtraction();
        tool.pickedPoints.clear();
        for (int activeFace = 0; activeFace < regions.regionByActiveFace.length; activeFace++) {
            int region = regions.regionByActiveFace[activeFace];
            if (region >= 0 && wanted[region]) {
                wanted[region] = false;
                tool.pickedPoints.add(RingRegionTool.faceCentroid(surface, surface.faceIdAt(activeFace)));
            }
        }
        tool.reselect();
        SelectionBounds bounds = new SelectionBounds();
        bounds.regionFaces(regions, tool.selectedRegions);
        Vector3f center = new Vector3f();
        float radius = bounds.sphere(center);
        scene.camera.frame(center, radius, Camera3D.DEFAULT_FRAME_PADDING, scene.camera.aspectRatio());
        tool.lastError = "";
        tool.lastRow = "selected and framed " + asked + ": " + tool.selectedCount() + " of "
                + regions.regionCount + " regions selected";
        Platforms.get().log(RingRegionTool.LOG_PREFIX + tool.lastRow + ", select=\"" + tool.selectQuery + "\"");
        terminal.history.addLine(tool.lastRow, Color.TEAL_DARK);
        Toggle.setPanelFocus(PaneTypes.KnotView);
        return null;
    }
}
