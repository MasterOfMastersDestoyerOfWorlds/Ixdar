package unit.render;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

import ixdar.graphics.render.text.Font;
import ixdar.graphics.render.text.FontAtlasDTO;

/**
 * The font atlas pads every glyph for the coarsest mipmap level whose median the text shader uses,
 * so a sample on a quad's edge never averages another glyph's cell into a sliver of ink.
 */
class FontAtlasPaddingTest {

    private static final Path ATLAS = Path.of("src/main/resources/res/opensans.json");

    private static final double BILINEAR_REACH_TEXELS = 1.5;

    /**
     * Every pair of glyph quads is at least two reaches of a bilinear sample at the median's
     * coarsest mipmap level apart, so each stays in its own cell, and every quad is a reach from the
     * atlas border, which the texture wraps across.
     *
     * @throws IOException if the atlas JSON cannot be read
     */
    @Test
    void glyphQuadsLeaveRoomForEveryMipmapLevelTheMedianSamples() throws IOException {
        FontAtlasDTO atlas = new Gson().fromJson(Files.readString(ATLAS, StandardCharsets.UTF_8), FontAtlasDTO.class);
        double reach = BILINEAR_REACH_TEXELS * Math.pow(2, Math.ceil(Font.MEDIAN_MAX_MIP_LEVEL));
        List<FontAtlasDTO.GlyphEntry> drawn = new ArrayList<>();
        for (FontAtlasDTO.GlyphEntry glyph : atlas.glyphs) {
            if (glyph.atlasBounds != null) {
                drawn.add(glyph);
            }
        }
        assertTrue(drawn.size() > 1, "the atlas lists drawn glyphs");
        for (int first = 0; first < drawn.size(); first++) {
            FontAtlasDTO.Rect bounds = drawn.get(first).atlasBounds;
            double border = Math.min(Math.min(bounds.left, bounds.bottom),
                    Math.min(atlas.atlas.width - bounds.right, atlas.atlas.height - bounds.top));
            assertTrue(border >= reach, "glyph " + drawn.get(first).unicode + " is " + border
                    + " texels from the atlas border, under the " + reach + " texel reach");
            for (int second = first + 1; second < drawn.size(); second++) {
                FontAtlasDTO.Rect other = drawn.get(second).atlasBounds;
                double gap = Math.max(Math.max(other.left - bounds.right, bounds.left - other.right),
                        Math.max(other.bottom - bounds.top, bounds.bottom - other.top));
                assertTrue(gap >= 2 * reach, "glyphs " + drawn.get(first).unicode + " and "
                        + drawn.get(second).unicode + " are " + gap + " texels apart, under twice the "
                        + reach + " texel reach");
            }
        }
    }
}
