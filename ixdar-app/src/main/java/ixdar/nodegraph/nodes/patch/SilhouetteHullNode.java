package ixdar.nodegraph.nodes.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.grid2d.PolygonScan;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.geometry.volume.VoxelGrid;
import ixdar.graphics.cameras.Camera3D;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * The volume a multiview template's traced outlines enclose: the lattice points nearly every view
 * keeps, rounded by total-variation smoothing that every outline's rays still touch, as a smoothed
 * signed distance for volume_to_mesh.
 */
@MeshNodeAnnotation(id = SilhouetteHullNode.NODE_ID, category = NodeCategory.PRIMITIVES)
public class SilhouetteHullNode implements MeshNode {
    public static final String NODE_ID = "silhouette_hull";
    public static final int MINIMUM_OUTLINE_POINTS = 8;
    public static final int MARGIN_VOXELS = 2;
    public static final int PASSES = 2;
    public static final int MINIMUM_AGREEING_VIEWS = 3;
    public static final int ROUNDING_ITERATIONS = 200;
    public static final int DISTANCE_SMOOTHING_PASSES = 16;
    public static final int DEFAULT_RESOLUTION = 64;
    public static final float DEFAULT_ROUNDING = 8f;
    public static final float MINIMUM_BALL_FACTOR = 3f;
    public static final float SOLID_LEVEL = 0.5f;
    public static final float SURFACE_OFFSET = 0.5f;
    public static final InputPort SVG = new InputPort("svg", PortType.STRING, "");
    public static final InputPort RESOLUTION = new InputPort("resolution", PortType.INT, DEFAULT_RESOLUTION, 16f, 256f);
    public static final InputPort MISSES = new InputPort("misses", PortType.INT, 1, 0f, 8f);
    public static final InputPort ROUNDING = new InputPort("rounding", PortType.FLOAT, DEFAULT_ROUNDING, 0f, 40f);
    public static final OutputPort VOLUME_OUT = new OutputPort("volume", PortType.VOLUME);

    @Override
    public List<InputPort> inputs() {
        return List.of(SVG, RESOLUTION, MISSES, ROUNDING);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(VOLUME_OUT);
    }

    @Override
    public String description() {
        return "Volume from outlines traced in any cells of a multiview template: carves a lattice keeping points "
                + "that all but `misses` views place inside their outline, rounds the carved hull by total-variation "
                + "smoothing that every outline's rays still touch, and outputs its smoothed signed distance "
                + "(positive inside) for volume_to_mesh at level 0.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                SVG.name, "An SVG written by `ixdar-cli trace-outlines` or `multiview --svg` with one closed outline "
                        + "in each cell that constrains the hull, in any cells (front, side, top, back, 3/4).",
                RESOLUTION.name, "Lattice points along the hull's longest side; the time grows with its cube.",
                MISSES.name, "Views that may place a kept point outside their outline, so a few percent of "
                        + "disagreement between pictures does not erode the shape; at least three views must "
                        + "still agree. 0 is the strict visual hull.",
                ROUNDING.name, "Radius, in lattice steps, below which the hull's edges and bumps are smoothed away; "
                        + "0 keeps the carved points as they are.",
                VOLUME_OUT.name, "Signed distance in lattice steps, positive inside, smoothed; every point off the "
                        + "carved hull at least half a step outside, so the surface never leaves an outline.");
    }

    /**
     * Carve the voted hull, pin each outline ray's deepest kept point, round the hull by total-variation
     * denoising with carved points empty and pins full, and smooth its signed distance.
     *
     * @param ctx node context carrying the svg and the lattice's settings
     * @throws IllegalArgumentException when the SVG holds no outline or the views keep no common volume
     */
    @Override
    public void evaluate(NodeContext ctx) {
        String source = ctx.getInputValue(SVG.name) instanceof String text ? text.trim() : "";
        if (source.isEmpty()) {
            ctx.setOutput(VOLUME_OUT.name, null);
            return;
        }
        int resolution = Math.max(MARGIN_VOXELS * 4, FieldBroadcast.intScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, RESOLUTION.name, RESOLUTION.defaultValue), DEFAULT_RESOLUTION));
        int misses = Math.max(0, FieldBroadcast.intScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, MISSES.name, MISSES.defaultValue), 1));
        float rounding = Math.max(0f, FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, ROUNDING.name, ROUNDING.defaultValue), DEFAULT_ROUNDING));
        StrokeSketch sketch = StrokeSketch.load(source, NODE_ID);
        float[][] cells = sketch.cellOutlines();
        int width = sketch.cellWidth;
        int height = sketch.cellHeight;

        // Views: every cell holding an outline, its camera, projection and outline mask by pixel centre.
        List<Integer> viewCells = new ArrayList<>();
        for (int cell = 0; cell < cells.length; cell++) {
            if (cells[cell].length / 2 >= MINIMUM_OUTLINE_POINTS) {
                viewCells.add(cell);
            }
        }
        int viewCount = viewCells.size();
        if (viewCount == 0) {
            throw new IllegalArgumentException(NODE_ID + ": no closed outline in " + source);
        }
        Camera3D[] cameras = new Camera3D[viewCount];
        Matrix4f[] projections = new Matrix4f[viewCount];
        boolean[][] masks = new boolean[viewCount][];
        for (int view = 0; view < viewCount; view++) {
            cameras[view] = sketch.cellCamera(viewCells.get(view));
            projections[view] = new Matrix4f(cameras[view].projection).mul(cameras[view].view);
            masks[view] = PolygonScan.covered(cells[viewCells.get(view)], 0f, 0f, 1f, width, height);
        }

        // Carve twice: over the cube the cells frame, to find the hull's box, then over that box. A
        // lattice point is kept when all but `misses` views see it inside their outline, never fewer
        // than three: with only front, side and top, one miss would let two refill the third's notch.
        float frameHalf = sketch.distance * (float) Math.tan(Math.toRadians(sketch.fieldOfViewDegrees) / 2.0)
                * Math.max(1f, width / (float) height);
        float[] lower = { sketch.center[0] - frameHalf, sketch.center[1] - frameHalf, sketch.center[2] - frameHalf };
        float[] upper = { sketch.center[0] + frameHalf, sketch.center[1] + frameHalf, sketch.center[2] + frameHalf };
        int required = Math.max(Math.min(viewCount, MINIMUM_AGREEING_VIEWS), viewCount - misses);
        VoxelGrid grid = null;
        boolean[] hull = new boolean[0];
        Vector3f point = new Vector3f();
        Vector4f clip = new Vector4f();
        for (int pass = 0; pass < PASSES; pass++) {
            float spacing = Math.max(upper[0] - lower[0], Math.max(upper[1] - lower[1], upper[2] - lower[2]))
                    / (resolution - 2 * MARGIN_VOXELS - 1);
            int[] size = new int[VoxelGrid.AXES];
            for (int axis = 0; axis < VoxelGrid.AXES; axis++) {
                size[axis] = (int) Math.ceil((upper[axis] - lower[axis]) / spacing) + 1 + 2 * MARGIN_VOXELS;
                lower[axis] -= MARGIN_VOXELS * spacing;
            }
            grid = new VoxelGrid(size, lower, spacing);
            hull = new boolean[grid.values.length];
            float[] found = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
                -Float.MAX_VALUE };
            for (int index = 0; index < hull.length; index++) {
                grid.position(index, point);
                int votes = 0;
                for (int view = 0; view < viewCount && votes + viewCount - view >= required; view++) {
                    projections[view].transform(clip.set(point, 1f));
                    int column = (int) Math.floor((clip.x / clip.w + 1f) / 2f * width);
                    int row = (int) Math.floor((1f - clip.y / clip.w) / 2f * height);
                    votes += column >= 0 && row >= 0 && column < width && row < height
                            && masks[view][row * width + column] ? 1 : 0;
                }
                hull[index] = votes >= required;
                for (int axis = 0; axis < VoxelGrid.AXES && hull[index]; axis++) {
                    found[axis] = Math.min(found[axis], point.get(axis));
                    found[axis + VoxelGrid.AXES] = Math.max(found[axis + VoxelGrid.AXES], point.get(axis));
                }
            }
            if (found[0] > found[VoxelGrid.AXES]) {
                throw new IllegalArgumentException(NODE_ID + ": the outlines' views keep no common volume; "
                        + "raise misses or check the traces");
            }
            for (int axis = 0; axis < VoxelGrid.AXES; axis++) {
                lower[axis] = found[axis] - spacing;
                upper[axis] = found[axis + VoxelGrid.AXES] + spacing;
            }
        }

        // Pins: every outline's rays, one per lattice step across the picture, pin the middle of their
        // longest run of kept points, so each ray still meets the rounded surface (after Kolev and
        // Cremers' silhouette constraint, PAMI 2011, as a pinned point instead of a sum along the ray).
        float[] floor = new float[hull.length];
        float[] ceiling = new float[hull.length];
        float[] indicator = new float[hull.length];
        for (int index = 0; index < hull.length; index++) {
            indicator[index] = hull[index] ? 1f : 0f;
            ceiling[index] = indicator[index];
        }
        Matrix4f model = new Matrix4f();
        float[] origin = new float[VoxelGrid.AXES];
        float[] direction = new float[VoxelGrid.AXES];
        for (int view = 0; view < viewCount; view++) {
            Camera3D camera = cameras[view];
            float stepPixels = Math.max(1f, grid.spacing * height / (2f * camera.position.distance(camera.orbitTarget)
                    * (float) Math.tan(Math.toRadians(camera.fov) / 2.0)));
            for (float row = 0f; row < height; row += stepPixels) {
                for (float column = 0f; column < width; column += stepPixels) {
                    if (masks[view][(int) row * width + (int) column]
                            && camera.rayThroughPixel(model, column, row, width, height, origin, direction)) {
                        int pin = grid.longestRunMiddle(hull, origin, direction);
                        if (pin >= 0) {
                            floor[pin] = 1f;
                        }
                    }
                }
            }
        }

        // Rounding: total-variation denoising of the hull's indicator, held at 0 off the hull and 1 on
        // the pins, fidelity 3 / rounding so a ball smaller than that radius is smoothed away. A pure
        // minimal surface (no fidelity) shrink-wraps onto the pins and creases along them.
        float[] rounded = rounding > 0f ? grid.totalVariation(indicator, MINIMUM_BALL_FACTOR / rounding, floor,
                ceiling, ROUNDING_ITERATIONS) : indicator;

        // The solid u > 1/2 as a signed distance, smoothed (16 passes at resolution 64, growing with the
        // square of the resolution so the same length of the shape is smoothed) to iron out voxel steps;
        // every point off the hull stays at least half a step outside, so the surface never leaves an
        // outline.
        boolean[] solid = new boolean[hull.length];
        for (int index = 0; index < hull.length; index++) {
            solid[index] = rounded[index] > SOLID_LEVEL;
            ceiling[index] = hull[index] ? Float.MAX_VALUE : -SURFACE_OFFSET;
        }
        grid.signedDistance(solid);
        float finer = resolution / (float) DEFAULT_RESOLUTION;
        grid.smooth(Math.round(DISTANCE_SMOOTHING_PASSES * finer * finer), ceiling);
        ctx.setOutput(VOLUME_OUT.name, grid);
    }
}
