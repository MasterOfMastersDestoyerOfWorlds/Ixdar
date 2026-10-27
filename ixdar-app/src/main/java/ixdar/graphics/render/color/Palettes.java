package ixdar.graphics.render.color;

import java.util.List;

/**
 * Every colour choice Ixdar draws with, the one place outside {@link Color} a colour constant is
 * named: the named {@link Palette} instances, each wearable by every scene, and the options list.
 */
public final class Palettes {

    // The default: today's dark scenes.
    public static final Palette DARK = new Palette("Dark");

    // Cooler and lighter than the default: blue ground, teal accents.
    public static final Palette SLATE = new Palette("Slate");

    // Dark ink on cream: the trade game's map.
    public static final Palette PAPER = new Palette("Paper");

    // Every scene steps through all of these.
    public static final List<Palette> OPTIONS = List.of(DARK, SLATE, PAPER);

    // No red or purple: those are the ring tool's draft modes (categories 0 and 2), and the marked
    // ring is the highlight.
    private static final Color[] RING = { Color.GREEN_TEAL, Color.BLUE_LIGHT, Color.YELLOW_ORANGE,
        Color.ORANGE, Color.TEAL_LIGHT, Color.BROWN_ORANGE, Color.YELLOW_LIGHT, Color.GREEN_YELLOW };

    private static final Color[] REGION = { Color.BLUE_TEAL_DARK, Color.YELLOW, Color.PINK_RED,
        Color.GREEN, Color.ORANGE_DARK, Color.PURPLE, Color.GREEN_LIGHT,
        Color.TEAL_VERY_LIGHT, Color.PURPLE_PINK_LIGHT, Color.BROWN_LIGHT, Color.BLUE, Color.TEAL_GREEN };

    private static final Color[] OVERLAY_GROUP = { Color.YELLOW, Color.GREEN_TEAL_LIGHT,
        Color.PURPLE_PINK, Color.TEAL, Color.PURPLE_LIGHT, Color.WHITE, Color.RED, Color.BLUE_TEAL_LIGHT };

    // Twenty distinct colours, one per patch or tag id.
    private static final Color[] PATCH = { Color.RED_PINK_LIGHT, Color.ORANGE_VERY_LIGHT,
        Color.GREEN_GREY_LIGHT, Color.GREY_BLUE_VERY_DARK, Color.RED_VERY_LIGHT, Color.PURPLE_GREY,
        Color.BLUE_GREY_DARK, Color.ORANGE_LIGHT, Color.PINK_RED_LIGHT, Color.PURPLE_DARK,
        Color.PURPLE_GREY_LIGHT, Color.BLUE_GREY_VERY_LIGHT, Color.TEAL_PASTEL, Color.ORANGE_PASTEL,
        Color.BROWN_RED, Color.BLUE_GREY_LIGHT, Color.GREEN_YELLOW_LIGHT, Color.PINK_RED_VERY_LIGHT,
        Color.PURPLE_GREY_DARK, Color.PURPLE_GREY_VERY_LIGHT };

    // mesh_scalar.fs holds exactly this many stops.
    private static final Color[] SCALAR = { Color.BLUE_VERY_DARK, Color.IXDAR, Color.RED_ORANGE,
        Color.YELLOW_VERY_LIGHT };

    private static final Color[] EDGE_MARK = { Color.ORANGE, Color.BLUE_TEAL, Color.PINK };

    // Eighteen bright hues 20 degrees apart, ordered 140 degrees apart so the first entries contrast.
    private static final Color[] BRIGHT = { Color.RED_BRIGHT, Color.GREEN_TEAL_BRIGHT,
        Color.PURPLE_BRIGHT, Color.YELLOW_BRIGHT, Color.TEAL_BLUE_BRIGHT, Color.PINK_RED_BRIGHT,
        Color.GREEN_BRIGHT, Color.BLUE_PURPLE_BRIGHT, Color.ORANGE_YELLOW_BRIGHT, Color.TEAL_BRIGHT,
        Color.PINK_PURPLE_BRIGHT, Color.GREEN_YELLOW_BRIGHT, Color.BLUE_BRIGHT, Color.RED_ORANGE_BRIGHT,
        Color.TEAL_GREEN_BRIGHT, Color.PURPLE_PINK_BRIGHT, Color.YELLOW_GREEN_BRIGHT, Color.BLUE_TEAL_BRIGHT };

    // Deep inks in the same order of hue (red, green, purple, yellow first), readable on paper.
    private static final Color[] INK = { Color.RED_DARK, Color.GREEN_DARK, Color.PURPLE,
        Color.ORANGE_YELLOW_DARK, Color.BLUE_TEAL_DARK, Color.PINK_RED, Color.TEAL_DARK, Color.PURPLE_DARK,
        Color.ORANGE_DARK, Color.BLUE_GREY_DARK, Color.IXDAR, Color.BROWN, Color.BLUE, Color.BROWN_RED };

    private static final Color[] DARK_TIME_SHARE = { Color.BLUE_VERY_DARK, Color.BLUE_DARK, Color.PURPLE,
        Color.IXDAR };

    private static final Color[] PAPER_TIME_SHARE = { Color.WHITE_YELLOW, Color.YELLOW_ORANGE_VERY_LIGHT,
        Color.ORANGE_PASTEL, Color.ORANGE_LIGHT };

    static {
        DARK.text = Color.WHITE;
        DARK.mutedText = Color.BLUE_GREY;
        DARK.headingText = Color.YELLOW_ORANGE;
        DARK.linkText = Color.TEAL_BLUE_LIGHT;
        DARK.selected = Color.GREEN_TEAL_LIGHT;
        DARK.disabled = Color.GREY_DARK;
        DARK.highlight = Color.PINK_LIGHT;
        DARK.hover = Color.IXDAR;
        DARK.accent = Color.RED_PINK_LIGHT;
        DARK.success = Color.GREEN_TEAL;
        DARK.warning = Color.ORANGE;
        DARK.error = Color.RED;
        DARK.background = Color.GREY_VERY_DARK;
        DARK.panel = Color.BLUE_TEAL_VERY_DARK;
        DARK.panelBorder = Color.BLUE_GREY;
        DARK.surface = Color.BLUE_VERY_DARK;
        DARK.line = Color.BLUE_GREY;
        DARK.gridLine = Color.GREY_DARK;
        DARK.categories = BRIGHT;
        DARK.ring = RING;
        DARK.region = REGION;
        DARK.patch = PATCH;
        DARK.overlayGroup = OVERLAY_GROUP;
        DARK.edgeMark = EDGE_MARK;
        DARK.scalar = SCALAR;
        DARK.timeShare = DARK_TIME_SHARE;
    }

    static {
        SLATE.text = Color.WHITE;
        SLATE.mutedText = Color.BLUE_GREY_LIGHT;
        SLATE.headingText = Color.TEAL_LIGHT;
        SLATE.linkText = Color.YELLOW_ORANGE_VERY_LIGHT;
        SLATE.selected = Color.GREEN_LIGHT;
        SLATE.disabled = Color.BLUE_GREY_DARK;
        SLATE.highlight = Color.PINK;
        SLATE.hover = Color.TEAL_BRIGHT;
        SLATE.accent = Color.TEAL;
        SLATE.success = Color.GREEN_TEAL_LIGHT;
        SLATE.warning = Color.ORANGE;
        SLATE.error = Color.RED_LIGHT;
        SLATE.background = Color.BLUE_VERY_DARK;
        SLATE.panel = Color.GREY_BLUE_VERY_DARK;
        SLATE.panelBorder = Color.BLUE_GREY_LIGHT;
        SLATE.surface = Color.BLUE_GREY_DARK;
        SLATE.line = Color.BLUE_GREY_LIGHT;
        SLATE.gridLine = Color.BLUE_GREY_DARK;
        SLATE.categories = BRIGHT;
        SLATE.ring = RING;
        SLATE.region = REGION;
        SLATE.patch = PATCH;
        SLATE.overlayGroup = OVERLAY_GROUP;
        SLATE.edgeMark = EDGE_MARK;
        SLATE.scalar = SCALAR;
        SLATE.timeShare = DARK_TIME_SHARE;
    }

    static {
        PAPER.text = Color.BROWN_VERY_DARK;
        PAPER.mutedText = Color.BROWN_GREY;
        PAPER.headingText = Color.BLUE_GREY_DARK;
        PAPER.linkText = Color.BLUE_TEAL_DARK;
        PAPER.selected = Color.PURPLE;
        PAPER.disabled = Color.BROWN_GREY_LIGHT;
        PAPER.highlight = Color.ORANGE_YELLOW_DARK;
        PAPER.hover = Color.PURPLE_DARK;
        PAPER.accent = Color.IXDAR;
        PAPER.success = Color.GREEN_DARK;
        PAPER.warning = Color.ORANGE_DARK;
        PAPER.error = Color.RED;
        PAPER.background = Color.WHITE_ORANGE;
        PAPER.panel = Color.WHITE_YELLOW;
        PAPER.panelBorder = Color.BROWN_GREY_DARK;
        PAPER.surface = Color.BROWN_GREY_LIGHT;
        PAPER.line = Color.BROWN_GREY;
        PAPER.gridLine = Color.BROWN_GREY_VERY_LIGHT;
        PAPER.categories = INK;
        PAPER.ring = RING;
        PAPER.region = REGION;
        PAPER.patch = PATCH;
        PAPER.overlayGroup = OVERLAY_GROUP;
        PAPER.edgeMark = EDGE_MARK;
        PAPER.scalar = SCALAR;
        PAPER.timeShare = PAPER_TIME_SHARE;
    }

    private Palettes() {
    }
}
