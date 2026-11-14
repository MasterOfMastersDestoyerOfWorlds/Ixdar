package unit.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

import ixdar.graphics.render.text.FontAtlasDTO;
import ixdar.platform.input.Keys;
import ixdar.tools.ControlHint;

/**
 * A control hint draws its key and modifiers as key glyphs, and its text label where the key
 * glyph atlas has no glyph.
 */
class KeyGlyphsTest {

    private static final Path ATLAS = Path.of("src/main/resources/res/keyglyphs.json");

    private static final String DESCRIPTION = "does something";

    /** A key with no glyph is drawn as its text label. */
    @Test
    void aKeyWithNoGlyphStaysText() {
        assertEquals("double-click", new ControlHint(Keys.DOUBLE_CLICK, DESCRIPTION).glyphLabel());
        assertEquals("super", new ControlHint(Keys.LEFT_SUPER, DESCRIPTION).glyphLabel());
    }

    /** The modifiers held become glyphs ahead of the key's glyph. */
    @Test
    void modifiersAndKeyBecomeGlyphs() {
        ControlHint redo = new ControlHint("redo", Keys.Z, true, true, DESCRIPTION, () -> {
        });
        assertEquals("" + Keys.LEFT_CONTROL.glyph + '+' + Keys.LEFT_SHIFT.glyph + '+' + Keys.Z.glyph,
                redo.glyphLabel());
        ControlHint pan = new ControlHint(Keys.DRAG, false, true, DESCRIPTION);
        assertEquals("" + Keys.LEFT_SHIFT.glyph + '+' + Keys.DRAG.glyph, pan.glyphLabel());
        assertEquals(String.valueOf(Keys.ENTER.glyph), new ControlHint(Keys.ENTER, DESCRIPTION).glyphLabel());
    }

    /**
     * Every glyph a key names is in the atlas, so none is dropped when drawn.
     *
     * @throws IOException if the atlas JSON cannot be read
     */
    @Test
    void theAtlasHoldsEveryGlyph() throws IOException {
        FontAtlasDTO atlas = new Gson().fromJson(Files.readString(ATLAS, StandardCharsets.UTF_8), FontAtlasDTO.class);
        Set<Integer> held = new HashSet<>();
        for (FontAtlasDTO.GlyphEntry glyph : atlas.glyphs) {
            held.add(glyph.unicode);
        }
        for (Keys key : Keys.values()) {
            if (key.glyph != 0) {
                assertTrue(held.contains((int) key.glyph), key.name());
            }
        }
    }
}
