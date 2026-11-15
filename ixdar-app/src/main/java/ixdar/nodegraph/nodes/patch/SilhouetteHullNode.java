package ixdar.nodegraph.nodes.patch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.grid2d.DistanceTransform;
import ixdar.geometry.grid2d.PolygonScan;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
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
    public static final InputPort OUTLINES = new InputPort("outlines", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort RESOLUTION = new InputPort("resolution", PortType.INT, DEFAULT_RESOLUTION, 16f, 256f);
    public static final InputPort MISSES = new InputPort("misses", PortType.INT, 1, 0f, 8f);
    public static final InputPort ROUNDING = new InputPort("rounding", PortType.FLOAT, DEFAULT_ROUNDING, 0f, 40f);
    public static final String NO_SYMMETRY = "none";
    public static final String MIRROR_X = "x";
    public static final float OFF_PICTURE = -1e9f;
    public static final InputPort SYMMETRY = new InputPort("symmetry", PortType.STRING, NO_SYMMETRY);
    public static final OutputPort VOLUME_OUT = new OutputPort("volume", PortType.VOLUME);

    @Override
    public List<InputPort> inputs() {
        return List.of(SVG, OUTLINES, RESOLUTION, MISSES, ROUNDING, SYMMETRY);
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
                SYMMETRY.name, "\"x\" for a subject symmetric across the template's x = centre plane: a lattice point is "
                        + "kept by the mean of its own and its mirror image's depth inside the outlines (each view's "
                        + "signed pixel distance, the deciding view's when misses allow some), so traces that "
                        + "disagree a little cannot turn or lopside the hull. \"none\" (default) votes as traced.",
                SVG.name, "An SVG template (fit_references' svg, or `multiview --svg` with outlines drawn in) with "
                        + "one closed outline in each cell that constrains the hull, in any cells (front, side, top, "
                        + "back, 3/4).",
                OUTLINES.name, "Closed world curves tagged with their cell, carrying the template whose cameras see "
                        + "them: fit_references' or svg_to_curves' geometry. Read instead of svg when connected.",
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
        GeometryBundle outlines = ctx.getInput(OUTLINES.name, GeometryBundle.class);
        StrokeSketch placed = StrokeSketch.templateOf(outlines, NODE_ID);
        if (source.isEmpty() && placed == null) {
            ctx.setOutput(VOLUME_OUT.name, null);
            return;
        }
        int resolution = Math.max(MARGIN_VOXELS * 4, FieldBroadcast.intScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, RESOLUTION.name, RESOLUTION.defaultValue), DEFAULT_RESOLUTION));
        int misses = Math.max(0, FieldBroadcast.intScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, MISSES.name, MISSES.defaultValue), 1));
        float rounding = Math.max(0f, FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, ROUNDING.name, ROUNDING.defaultValue), DEFAULT_ROUNDING));
        StrokeSketch sketch = placed != null ? placed : StrokeSketch.load(source, NODE_ID);
        float[][] cells = placed != null ? placed.cellOutlines((CurveGeometry) outlines.slots().get(CurveGeometry.SLOT))
                : sketch.cellOutlines();
        source = placed != null ? NODE_ID + " " + OUTLINES.name : source;
        int width = sketch.cellWidth;
        int height = sketch.cellHeight;
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
        String symmetry = ctx.getInputValue(SYMMETRY.name) instanceof String text ? text.trim() : NO_SYMMETRY;
        boolean mirrored = MIRROR_X.equals(symmetry);
        if (!mirrored && !symmetry.isEmpty() && !NO_SYMMETRY.equals(symmetry)) {
            throw new IllegalArgumentException(NODE_ID + " symmetry must be x or none: " + symmetry);
        }
        Camera3D[] cameras = new Camera3D[viewCount];
        Matrix4f[] projections = new Matrix4f[viewCount];
        boolean[][] masks = new boolean[viewCount][];
        float[][] depths = new float[viewCount][];
        float[] viewDepths = new float[viewCount];
        for (int view = 0; view < viewCount; view++) {
            cameras[view] = sketch.cellCamera(viewCells.get(view));
            projections[view] = new Matrix4f(cameras[view].projection).mul(cameras[view].view);
            masks[view] = PolygonScan.covered(cells[viewCells.get(view)], 0f, 0f, 1f, width, height);
            depths[view] = DistanceTransform.signed(masks[view], width, height);
        }
        float frameHalf = sketch.distance * (float) Math.tan(Math.toRadians(sketch.fieldOfViewDegrees) / 2.0)
                * Math.max(1f, width / (float) height);
        Vector3f lower = new Vector3f(sketch.center).sub(frameHalf, frameHalf, frameHalf);
        Vector3f upper = new Vector3f(sketch.center).add(frameHalf, frameHalf, frameHalf);
        int required = Math.max(Math.min(viewCount, MINIMUM_AGREEING_VIEWS), viewCount - misses);
        VoxelGrid grid = null;
        boolean[] hull = new boolean[0];
        Vector3f point = new Vector3f();
        Vector4f clip = new Vector4f();
        for (int pass = 0; pass < PASSES; pass++) {
            Vector3f extent = new Vector3f(upper).sub(lower);
            float spacing = extent.get(extent.maxComponent()) / (resolution - 2 * MARGIN_VOXELS - 1);
            int[] size = new int[VoxelGrid.AXES];
            for (int axis = 0; axis < VoxelGrid.AXES; axis++) {
                size[axis] = (int) Math.ceil(extent.get(axis) / spacing) + 1 + 2 * MARGIN_VOXELS;
            }
            float margin = MARGIN_VOXELS * spacing;
            lower.sub(margin, margin, margin);
            grid = new VoxelGrid(size, lower, spacing);
            hull = new boolean[grid.values.length];
            Vector3f foundLow = new Vector3f(Float.MAX_VALUE);
            Vector3f foundHigh = new Vector3f(-Float.MAX_VALUE);
            for (int index = 0; index < hull.length; index++) {
                grid.position(index, point);
                float score = 0f;
                for (int side = 0; side < (mirrored ? 2 : 1); side++) {
                    float x = side == 0 ? point.x : 2f * sketch.center.x - point.x;
                    for (int view = 0; view < viewCount; view++) {
                        projections[view].transform(clip.set(x, point.y, point.z, 1f));
                        int column = (int) Math.floor((clip.x / clip.w + 1f) / 2f * width);
                        int row = (int) Math.floor((1f - clip.y / clip.w) / 2f * height);
                        viewDepths[view] = column >= 0 && row >= 0 && column < width && row < height
                                ? depths[view][row * width + column] : OFF_PICTURE;
                    }
                    Arrays.sort(viewDepths);
                    score += viewDepths[viewCount - required];
                }
                hull[index] = score > 0f;
                if (hull[index]) {
                    foundLow.min(point);
                    foundHigh.max(point);
                }
            }
            if (foundLow.x > foundHigh.x) {
                throw new IllegalArgumentException(NODE_ID + ": the outlines' views keep no common volume; "
                        + "raise misses or check the traces");
            }
            lower.set(foundLow).sub(spacing, spacing, spacing);
            upper.set(foundHigh).add(spacing, spacing, spacing);
        }
        float[] floor = new float[hull.length];
        float[] ceiling = new float[hull.length];
        float[] indicator = new float[hull.length];
        for (int index = 0; index < hull.length; index++) {
            indicator[index] = hull[index] ? 1f : 0f;
            ceiling[index] = indicator[index];
        }
        Matrix4f model = new Matrix4f();
        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
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

        float[] rounded = rounding > 0f ? grid.totalVariation(indicator, MINIMUM_BALL_FACTOR / rounding, floor,
                ceiling, ROUNDING_ITERATIONS) : indicator;

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
