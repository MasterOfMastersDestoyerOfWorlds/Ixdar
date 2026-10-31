package ixdar.tools.sculpt;

import java.util.Locale;
import java.util.regex.Matcher;

import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.graphics.cameras.Camera3D;
import ixdar.nodegraph.nodes.modifier.StrokeSculptNode;
import ixdar.tools.Item;

/**
 * One {@code stroke_sculpt} pass of a sculpt: its statement's id, the geometry it reads, its SVG's
 * path and text and its symmetry, and the item the stroke-sculpt tool lists it as. A pass never
 * changes once built, so the tool's undo snapshots share passes.
 */
public final class SculptPass extends Item {

    public static final String PASS_STRENGTH = "1.0";

    /** Statement id the pass is written under, which its output is read by. */
    public final String id;

    /** The {@code id.port} reference the pass reads its geometry from. */
    public final String upstream;

    /** The {@code svg} argument its statement carries, where a save writes its drawing. */
    public final String svgPath;

    /** The drawing's SVG text, empty for a pass with no stroke yet or whose file is unreadable. */
    public final String svg;

    /** The pass's {@code symmetry} option: an axis, or {@link StrokeSculptNode#NO_SYMMETRY}. */
    public final String symmetry;

    /** Whether new strokes still go on the pass; committing it closes it. */
    public final boolean open;

    /**
     * A pass as given, labelled by its id with its stroke count as its size.
     *
     * @param id       statement id
     * @param upstream the {@code id.port} reference it reads geometry from
     * @param svgPath  the {@code svg} argument its statement carries
     * @param svg      the drawing's SVG text, or empty
     * @param symmetry an axis, or {@link StrokeSculptNode#NO_SYMMETRY}
     * @param open     whether new strokes still go on it
     */
    public SculptPass(String id, String upstream, String svgPath, String svg, String symmetry, boolean open) {
        this.id = id;
        this.upstream = upstream;
        this.svgPath = svgPath;
        this.svg = svg;
        this.symmetry = symmetry;
        this.open = open;
        label = id;
        Matcher shapes = StrokeSketch.SHAPE_TAG.matcher(svg);
        while (shapes.find()) {
            size++;
        }
        detail = String.format(Locale.ROOT, "%s, %d stroke(s), symmetry %s, svg %s", open ? "open" : "committed",
                size, symmetry, svgPath);
    }

    /**
     * This pass with one more stroke drawn on a live view, its own grid cell recording the view's
     * camera so the saved pass lifts it through that view again.
     *
     * @param kind         {@link StrokeSketch#SILHOUETTE}, {@code RIDGE}, {@code VALLEY} or {@code FILL}
     * @param strength     the stroke's weight in [-1, 1]
     * @param widthPixels  falloff width in view pixels
     * @param pixels       the stroke's points in view pixels from the top left, packed xy
     * @param view         the camera the stroke was drawn through
     * @param orthographic whether the view projects orthographically
     * @param width        the view's width in pixels
     * @param height       the view's height in pixels
     * @throws IllegalArgumentException when the pass's cells differ from the view in size or projection
     * @return the open pass holding the stroke
     */
    public SculptPass withStroke(String kind, float strength, float widthPixels, float[] pixels, Camera3D view,
            boolean orthographic, int width, int height) {
        StrokeSketch sketch = svg.isEmpty() ? new StrokeSketch() : StrokeSketch.parse(svg, id);
        sketch.addStroke(kind, strength, widthPixels, pixels, view, orthographic, width, height);
        return new SculptPass(id, upstream, svgPath, sketch.toSvg(), symmetry, true);
    }

    /**
     * The pass's DSL statement, one line, at the node's default strength.
     *
     * @return {@code id = stroke_sculpt(geometry=..., svg="...", symmetry="...", strength=1.0)}
     */
    public String statement() {
        return String.format(Locale.ROOT, "%s = %s(%s=%s, %s=\"%s\", %s=\"%s\", %s=%s)", id, StrokeSculptNode.NODE_ID,
                StrokeSculptNode.GEOMETRY.name, upstream, StrokeSculptNode.SVG.name, svgPath,
                StrokeSculptNode.SYMMETRY.name, symmetry, StrokeSculptNode.STRENGTH.name, PASS_STRENGTH);
    }
}
