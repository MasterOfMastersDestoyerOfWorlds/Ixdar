package ixdar.gui.terminal.commands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.geometry.mesh.data.paths.SurfaceRing;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.nodes.selection.RingDslWriter;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.scenes.ring.RingScene;

/**
 * Terminal command {@code ring}/{@code rg}: author a ring on the shown surface from three or more
 * points, close it into a tightened geodesic, and save it into the working graph.
 */
@CommandAnnotation(id = "rg")
public class RingCommand extends TerminalCommand {

    public static String cmd = "rg";
    public static final String ADD = "add";
    public static final String CLOSE = "close";
    public static final String SAVE = "save";
    public static final String CLEAR = "clear";
    public static final int ADD_ARGUMENT_COUNT = 4;

    /**
     * Full command word: {@code "ring"}.
     *
     * @return fully-qualified command name used at the prompt
     */
    @Override
    public String fullName() {
        return "ring";
    }

    /**
     * Short alias for the command: {@code "rg"}.
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
        return "author a ring through surface points and save it into the working graph";
    }

    /**
     * Usage hint displayed when the command is mis-invoked or {@code -h} is passed.
     *
     * @return usage string
     */
    @Override
    public String usage() {
        return "usage: rg|ring add <x> <y> <z> | close | save [path.dsl] | clear";
    }

    /**
     * Argument count is not fixed: {@code add} takes three coordinates and the rest take none.
     *
     * @return {@code -1}
     */
    @Override
    public int argLength() {
        return -1;
    }

    /**
     * Apply the subcommand at {@code args[startIdx]} to the terminal's active ring scene.
     *
     * @param args     full tokenised command line
     * @param startIdx index of the subcommand in {@code args}
     * @param terminal dispatching terminal (holds the active {@link RingScene})
     * @return {@code null}; the command offers no follow-up suggestions
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        if (!(terminal.modelScene instanceof RingScene scene)) {
            terminal.error("RingScene is not active");
            return null;
        }
        if (startIdx >= args.length) {
            terminal.error(usage());
            return null;
        }
        String subcommand = args[startIdx].toLowerCase(Locale.ROOT);
        try {
            switch (subcommand) {
                case ADD -> add(args, startIdx, terminal, scene);
                case CLOSE -> close(terminal, scene);
                case SAVE -> save(args, startIdx, terminal, scene);
                case CLEAR -> clear(terminal, scene);
                default -> terminal.error(usage());
            }
        } catch (RuntimeException failure) {
            terminal.error(failure.getMessage() == null ? failure.toString()
                    : failure.getMessage());
        }
        return null;
    }

    private void add(String[] args, int startIdx, Terminal terminal, RingScene scene) {
        if (args.length - startIdx != ADD_ARGUMENT_COUNT) {
            terminal.error(usage());
            return;
        }
        int count = scene.addRingWaypoint(
                Float.parseFloat(args[startIdx + 1]),
                Float.parseFloat(args[startIdx + 2]),
                Float.parseFloat(args[startIdx + 3]));
        terminal.history.addLine("ring point " + count + ": "
                + SurfaceWaypoints.format(scene.ringWaypointsXyz, count), Color.COMMAND);
    }

    private void close(Terminal terminal, RingScene scene) {
        SurfaceRing ring = scene.closeRing();
        terminal.history.addLine("ring closed: " + ring.markedEdgeCount + " edges, length "
                + ring.length + " (seed " + ring.seedLength + "), centroid "
                + SurfaceWaypoints.format(
                        new float[] { ring.centroidX, ring.centroidY, ring.centroidZ }, 1),
                Color.BRIGHT_GREEN);
    }

    private void save(String[] args, int startIdx, Terminal terminal, RingScene scene) {
        if (scene.ring == null) {
            terminal.error("close the ring before saving it");
            return;
        }
        String target = args.length - startIdx > 1 ? args[startIdx + 1] : scene.workingDslPath();
        if (target == null) {
            terminal.error("this scene has no working .dsl on disk; pass one: ring save <path>");
            return;
        }
        try {
            Path path = Path.of(target);
            String source = Files.exists(path)
                    ? new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                    : "";
            if (source.isBlank()) {
                terminal.error(target + " holds no graph for the ring to read its geometry from");
                return;
            }
            String updated = RingDslWriter.append(source, scene.ringWaypointsXyz,
                    scene.ringWaypointCount, true, false);
            RingDslWriter.writeAtomically(path, updated);
            terminal.history.addLine("ring saved to " + path, Color.BRIGHT_GREEN);
            terminal.history.addLine(RingDslWriter.appendedStatement(source, updated),
                    Color.LIGHT_GRAY);
        } catch (IOException failure) {
            terminal.error("could not write " + target + ": " + failure.getMessage());
        }
    }

    private void clear(Terminal terminal, RingScene scene) {
        scene.clearRing();
        terminal.history.addLine("ring cleared", Color.COMMAND);
    }
}
