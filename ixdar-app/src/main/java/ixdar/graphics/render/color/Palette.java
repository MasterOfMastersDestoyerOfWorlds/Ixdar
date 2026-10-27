package ixdar.graphics.render.color;

import java.util.HashMap;

/**
 * Every colour a scene draws with, named by function rather than scene concept (a road and a mesh
 * edge are both {@link #line}), plus id cycles and ramps read by index. Instances live in {@link Palettes}.
 */
public class Palette {

    public static final float OVERFLOW_SHADE = 0.55f;
    private static final byte[] HOVER_CHANNELS = { 1, 1, 1, 0 };
    private static final float HOVER_RADS_PER_SECOND = 6f;

    public final String name;

    public Color text;
    public Color mutedText;
    public Color headingText;
    public Color linkText;
    /** What is chosen or current: the selected model, a kept member, a matched segment. */
    public Color selected;
    /** What does nothing now: an actionless control, a skipped branch, an invalid draft. */
    public Color disabled;
    /** What the eye is drawn to: a marked ring, a cut, a critical path, the cursor. */
    public Color highlight;
    /** What hovered things pulse towards, through {@link #hovered}. */
    public Color hover;
    /** The scene's own mark: the logo, the player, a keyword, a primary marker. */
    public Color accent;
    public Color success;
    public Color warning;
    public Color error;
    public Color background;
    public Color panel;
    public Color panelBorder;
    /** The fill of drawn solids: a mesh, a quad patch's base, an unlabelled region. */
    public Color surface;
    /** Every drawn line: roads, mesh and graph edges, a tour, region boundaries. */
    public Color line;
    public Color gridLine;

    /**
     * Distinct hues for a scene's categories, read through a named index constant in the scene:
     * the GLSL vector components, the quad layout's directions, part labels and patch fills.
     */
    public Color[] categories;
    public Color[] ring;
    public Color[] region;
    public Color[] patch;
    public Color[] overlayGroup;
    public Color[] edgeMark;
    /** The scalar heat map, low to high at evenly spaced stops; mesh_scalar.fs holds exactly this many. */
    public Color[] scalar;
    /** A DSL graph node's box by its share of the run's total time, coolest (least) first. */
    public Color[] timeShare;

    private final HashMap<Color, ColorLerp> hoverLerps = new HashMap<>();

    /**
     * An empty palette; {@link Palettes} sets every field.
     *
     * @param name the palette's name, which the palette command and route show and select by
     */
    public Palette(String name) {
        this.name = name;
    }

    /**
     * The entry an id takes in a cycle, wrapping around: id {@code cycle.length} takes entry 0 again.
     *
     * @param cycle one of the palette's cycles
     * @param index any id, negative ones wrap from the end
     * @return the entry
     */
    public static Color entry(Color[] cycle, int index) {
        return cycle[Math.floorMod(index, cycle.length)];
    }

    /**
     * The entry an id takes in a cycle, each further round past it a darker shade, so an id past the
     * end never repeats an earlier id's colour exactly.
     *
     * @param cycle one of the palette's cycles
     * @param index id, 0 or more
     * @return the entry, scaled by {@link #OVERFLOW_SHADE} once per completed round
     */
    public static Color shadedEntry(Color[] cycle, int index) {
        return entry(cycle, index).shaded((float) Math.pow(OVERFLOW_SHADE, index / cycle.length));
    }

    /**
     * The one hover rule: a hovered thing's colour pulses towards {@link #hover} and back, colour
     * channels only so it keeps its opacity. Lerps are cached per colour so everything hovered in that
     * colour pulses in step.
     *
     * @param color the hovered thing's own colour
     * @return a colour that pulses while drawn
     */
    public ColorLerp hovered(Color color) {
        return hoverLerps.computeIfAbsent(color, c -> new ColorLerp(c, hover, HOVER_CHANNELS,
                HOVER_RADS_PER_SECOND));
    }
}
