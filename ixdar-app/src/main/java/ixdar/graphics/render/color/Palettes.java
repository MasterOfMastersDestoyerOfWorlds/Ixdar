package ixdar.graphics.render.color;

/** The palettes Ixdar draws with, each entry a named {@link Color}. */
public final class Palettes {

    // No red or purple: those tell the ring tool's draft modes, geodesic and crease, and the
    // marked ring is hot pink.
    public static final Palette RING = new Palette(Color.GREEN_TEAL, Color.BLUE_LIGHT, Color.YELLOW_ORANGE,
            Color.ORANGE, Color.TEAL_LIGHT, Color.BROWN_ORANGE, Color.YELLOW_LIGHT, Color.GREEN_YELLOW);

    public static final Palette REGION = new Palette(Color.BLUE_TEAL_DARK, Color.YELLOW, Color.PINK_RED,
            Color.GREEN, Color.ORANGE_DARK, Color.PURPLE, Color.GREEN_LIGHT,
            Color.TEAL_VERY_LIGHT, Color.PURPLE_PINK_LIGHT, Color.BROWN_LIGHT, Color.BLUE, Color.TEAL_GREEN);

    public static final Palette OVERLAY_GROUP = new Palette(Color.YELLOW, Color.GREEN_TEAL_LIGHT,
            Color.PURPLE_PINK, Color.TEAL, Color.PURPLE_LIGHT, Color.WHITE, Color.RED, Color.BLUE_TEAL_LIGHT);

    // Twenty distinct colours, one per patch or tag id: the decomposer's patches and a mesh tag
    // without a colour of its own.
    public static final Palette PATCH = new Palette(Color.RED_PINK_LIGHT, Color.ORANGE_VERY_LIGHT,
            Color.GREEN_GREY_LIGHT, Color.GREY_BLUE_VERY_DARK, Color.RED_VERY_LIGHT, Color.PURPLE_GREY, Color.BLUE_GREY_DARK,
            Color.ORANGE_LIGHT, Color.PINK_RED_LIGHT, Color.PURPLE_DARK, Color.PURPLE_GREY_LIGHT, Color.BLUE_GREY_VERY_LIGHT,
            Color.TEAL_PASTEL, Color.ORANGE_PASTEL, Color.BROWN_RED, Color.BLUE_GREY_LIGHT,
            Color.GREEN_YELLOW_LIGHT, Color.PINK_RED_VERY_LIGHT, Color.PURPLE_GREY_DARK, Color.PURPLE_GREY_VERY_LIGHT);

    // The scalar heat map, low to high at evenly spaced stops; mesh_scalar.fs holds exactly this
    // many stops.
    public static final Palette SCALAR_RAMP = new Palette(Color.BLUE_VERY_DARK, Color.IXDAR,
            Color.RED_ORANGE, Color.YELLOW_VERY_LIGHT);

    public static final Palette EDGE_MARK = new Palette(Color.ORANGE, Color.BLUE_TEAL,
            Color.PINK);

    // The x, y, z and w components of a GLSL vector literal, in that order.
    public static final Palette GLSL_VECTOR_COMPONENT = new Palette(Color.PINK_PASTEL, Color.GREEN_VERY_LIGHT,
            Color.PURPLE_PASTEL, Color.YELLOW_ORANGE_VERY_LIGHT);

    // A DSL graph node's box by its share of the run's total time, coolest (least) first.
    public static final Palette DSL_TIME_SHARE = new Palette(Color.BLUE_VERY_DARK, Color.BLUE_DARK, Color.PURPLE,
            Color.IXDAR);

    // Eighteen bright hues 20 degrees apart, ordered 140 degrees apart so the entries a
    // RegionColouring hands out first contrast: the quad layout's patch fill and the
    // decomposition's flat_color.
    public static final Palette BRIGHT_PATCH = new Palette(Color.RED_BRIGHT, Color.GREEN_TEAL_BRIGHT,
            Color.PURPLE_BRIGHT, Color.YELLOW_BRIGHT, Color.TEAL_BLUE_BRIGHT, Color.PINK_RED_BRIGHT,
            Color.GREEN_BRIGHT, Color.BLUE_PURPLE_BRIGHT, Color.ORANGE_YELLOW_BRIGHT, Color.TEAL_BRIGHT,
            Color.PINK_PURPLE_BRIGHT, Color.GREEN_YELLOW_BRIGHT, Color.BLUE_BRIGHT, Color.RED_ORANGE_BRIGHT,
            Color.TEAL_GREEN_BRIGHT, Color.PURPLE_PINK_BRIGHT, Color.YELLOW_GREEN_BRIGHT, Color.BLUE_TEAL_BRIGHT);

    private Palettes() {
    }
}
