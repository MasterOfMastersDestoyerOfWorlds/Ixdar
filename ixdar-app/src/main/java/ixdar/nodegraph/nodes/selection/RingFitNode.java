package ixdar.nodegraph.nodes.selection;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.query.RestingSphere;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.rings.RegionClusterFit;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.api.SteppableNode;

/**
 * The {@code ring_fit} node: moves the rings and bisectors drawn on a surface into its grooves
 * with {@link RegionClusterFit}'s air-flow passes. Stepped, each step is one pass.
 */
@MeshNodeAnnotation(id = RingFitNode.ID, desktopOnly = true, category = NodeCategory.SELECTION)
public final class RingFitNode implements SteppableNode {

    public static final String ID = "ring_fit";

    public static final InputPort GEOMETRY = InputPort.manifoldGeometry("geometry");
    public static final InputPort METRIC = new InputPort("metric", PortType.SURFACE_METRIC, null);
    public static final InputPort CREASES = new InputPort("creases", PortType.SURFACE_CREASES, null);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    /** The fit a stepped run takes a pass at a time, every pass so far recorded, or {@code null}. */
    public RegionClusterFit stepping;

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, METRIC, CREASES);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Fits the rings and bisectors drawn on a surface into its grooves: air let out over"
                + " the surface dies at the rings, the grooves are walls it cannot cross, and each"
                + " pass moves every ring toward the side whose air pushes on it less, until a pass"
                + " moves nothing.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input: the surface with the rings as drawn, from the last spline_ring"
                        + " or spline_cut. Output: the same surface with every movable ring at its"
                        + " fitted loop, under the same labels, its anchors moved onto the loop.",
                METRIC.name, "The surface's metric, from surface_metric, which the fitted rings are"
                        + " traced again on.",
                CREASES.name, "The surface's grooves, from surface_creases; measured here when"
                        + " unwired.");
    }

    /**
     * The fit this node runs, built on the rings as drawn and not yet begun: the walls
     * {@link RestingSphere} finds stop the air, each side of a ring pushes with the air arriving
     * there, and no ring is held on a wall.
     *
     * @param drawn   the surface with the rings as drawn
     * @param creases the surface's grooves, or {@code null} to measure them here
     * @throws IllegalArgumentException when the geometry carries no rings
     * @return the fit, for {@link RegionClusterFit#run} or a stepped {@link RegionClusterFit#begin}
     */
    public static RegionClusterFit fitOf(GeometryBundle drawn, SurfaceCreases creases) {
        if (drawn == null || drawn.mesh() == null) {
            throw new IllegalArgumentException(ID + " has no geometry; wire the last ring's geometry");
        }
        RegionClusterFit fit = new RegionClusterFit(drawn,
                creases != null ? creases : SurfaceCreases.of(drawn.mesh()));
        boolean[] walls = RestingSphere.walls(fit.mesh, false);
        System.arraycopy(walls, 0, fit.grooveBottom, 0, walls.length);
        System.arraycopy(walls, 0, fit.closedBottom, 0, walls.length);
        fit.airFlow = true;
        fit.closedWalls = true;
        fit.wallsUnpinned = true;
        return fit;
    }

    /**
     * The fit of the bound geometry, refused before any pass runs when the metric the fitted rings
     * need is missing.
     *
     * @param ctx the node's context, inputs bound
     * @throws IllegalArgumentException when the geometry carries no rings or no metric is wired
     * @return the fit, not yet begun
     */
    private static RegionClusterFit fitOfInputs(NodeContext ctx) {
        if (ctx.getInput(METRIC.name, SurfaceMetric.class) == null) {
            throw new IllegalArgumentException(ID + " has no metric; pass metric=surface.metric from"
                    + " the surface_metric statement the rings were drawn on");
        }
        return fitOf(ctx.getInput(GEOMETRY.name, GeometryBundle.class),
                ctx.getInput(CREASES.name, SurfaceCreases.class));
    }

    @Override
    public void evaluate(NodeContext ctx) {
        RegionClusterFit whole = fitOfInputs(ctx).run();
        ctx.setOutput(GEOMETRY_OUT.name, whole.fitted(whole.drawn,
                ctx.getInput(METRIC.name, SurfaceMetric.class)));
    }

    @Override
    public List<String> stepLevels() {
        return List.of("pass");
    }

    /**
     * Starts a stepped fit of the bound rings, every pass recorded so the scene can show any of
     * them, with the air measured as the rings are drawn.
     *
     * @param ctx the node's context, inputs bound
     * @throws IllegalArgumentException when the geometry carries no rings or no metric is wired
     */
    @Override
    public void beginSteps(NodeContext ctx) {
        stepping = fitOfInputs(ctx);
        stepping.recordPasses = true;
        stepping.begin();
    }

    /**
     * One pass of the fit.
     *
     * @param ctx   the node's context
     * @param level 0, the only level
     * @return the pass's number, how many rings it moved and each ring's length in edges, or
     *         {@code null} once a pass has moved nothing
     */
    @Override
    public String step(NodeContext ctx, int level) {
        RegionClusterFit fit = stepping;
        int movesBefore = fit.moves;
        if (!fit.stepPass()) {
            return null;
        }
        StringBuilder told = new StringBuilder("pass ").append(fit.passes).append(": ")
                .append(fit.moves - movesBefore).append(" ring(s) moved;");
        for (int ring = 0; ring < fit.ringLabels.length; ring++) {
            told.append(' ').append(fit.ringLabels[ring]).append(' ').append(fit.loops[ring].length)
                    .append(" edges");
        }
        return told.toString();
    }

    /**
     * Runs every remaining pass and publishes the surface with the rings where the fit left them.
     *
     * @param ctx the node's context
     */
    @Override
    public void finishSteps(NodeContext ctx) {
        while (stepping.stepPass()) {
            // Each pass moves what it can; the fit ends when a pass moves nothing.
        }
        ctx.setOutput(GEOMETRY_OUT.name, stepping.fitted(stepping.drawn,
                ctx.getInput(METRIC.name, SurfaceMetric.class)));
    }

    @Override
    public Object stepShown() {
        return stepping;
    }

    @Override
    public Object stepOverlay() {
        return null;
    }
}
